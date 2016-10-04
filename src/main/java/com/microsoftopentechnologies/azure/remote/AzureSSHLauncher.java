/*
 Copyright 2014 Microsoft Open Technologies, Inc.
 
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at
 
 http://www.apache.org/licenses/LICENSE-2.0
 
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package com.microsoftopentechnologies.azure.remote;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.microsoftopentechnologies.azure.AzureCloud;
import com.microsoftopentechnologies.azure.AzureSlave;
import com.microsoftopentechnologies.azure.AzureComputer;
import com.microsoftopentechnologies.azure.AzureSlaveTemplate;
import com.microsoftopentechnologies.azure.Messages;
import com.microsoftopentechnologies.azure.util.CleanUpAction;
import com.microsoftopentechnologies.azure.util.Constants;
import com.microsoftopentechnologies.azure.util.FailureStage;

import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.remoting.Channel.Listener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.OfflineCause;
import hudson.slaves.SlaveComputer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.jvnet.localizer.Localizable;

/**
 * SSH Launcher class
 *
 * @author Suresh nallamilli (snallami@gmail.com)
 *
 */
public class AzureSSHLauncher extends ComputerLauncher {

    private static final Logger LOGGER = Logger.getLogger(AzureSSHLauncher.class.getName());

    private static final String remoteInitFileName = "init.sh";

    @Override
    public void launch(final SlaveComputer slaveComputer, final TaskListener listener) {
        AzureComputer computer = (AzureComputer) slaveComputer;
        AzureSlave slave = computer.getNode();
        
        LOGGER.log(Level.INFO,"AzureSSHLauncher: launch: launch method called for slave {0}", slaveComputer.getName());

        // Check if VM is already stopped or stopping or getting deleted , if yes then there is no point in trying to connect
        // Added this check - since after restarting jenkins master, jenkins is trying to connect to all the slaves although slaves are suspended.
        // This still means that a delete slave will eventually get cleaned up.
        try {
            if (!slave.isVMAliveOrHealthy()) {
                LOGGER.log(Level.INFO,"AzureSSHLauncher: launch: Slave {0} is shut down, deleted, etc.  Not attempting to connect", slaveComputer.getName());
                return;
            }
        } catch (Exception e1) {
            // ignoring exception purposefully
        }
        
        // Block cleanup while we attempt to start.
        slave.blockCleanUpAction();

        PrintStream logger = listener.getLogger();
        boolean successful = false;
        Session session = null;

        try {
            session = connectToSsh(slave);
        } catch (UnknownHostException e) {
            LOGGER.log(Level.SEVERE, "AzureSSHLauncher: launch: "
                    + "Got unknown host exception. Virtual machine might have been deleted already", e);
        } catch (ConnectException e) {
            LOGGER.log(Level.SEVERE,
                    "AzureSSHLauncher: launch: Got connect exception. Might be due to firewall rules", e);
            handleLaunchFailure(slave, Constants.SLAVE_POST_PROV_CONN_FAIL);
        } catch (Exception e) {
            // Checking if we need to mark template as disabled. Need to re-visit this logic based on tests.
            if (e.getMessage() != null && e.getMessage().equalsIgnoreCase("Auth fail")) {
                LOGGER.log(Level.SEVERE,
                        "AzureSSHLauncher: launch: "
                        + "Authentication failure. Image may not be supporting password authentication", e);
                handleLaunchFailure(slave, Constants.SLAVE_POST_PROV_AUTH_FAIL);
            } else {
                LOGGER.log(Level.SEVERE, "AzureSSHLauncher: launch: Got  exception", e);
                handleLaunchFailure(slave, Constants.SLAVE_POST_PROV_CONN_FAIL + e.getMessage());
            }
        }
        finally {
            if (session == null) {
                slave.setCleanUpAction(CleanUpAction.DELETE, Messages._Slave_Failed_To_Connect());
                return;
            }
        }

        Localizable cleanUpReason = null;
        
        try {
            final Session cleanupSession = session;
            String initScript = slave.getInitScript();

            // Executing script only if script is not executed even once
            if (StringUtils.isNotBlank(initScript)
                    && executeRemoteCommand(session, "test -e ~/.azure-slave-init", logger) != 0) {
                LOGGER.info("AzureSSHLauncher: launch: Init script is not null, preparing to execute script remotely");
                copyFileToRemote(session, new ByteArrayInputStream(initScript.getBytes("UTF-8")), remoteInitFileName);

                // Execute initialization script
                // Make sure to change file permission for execute if needed. TODO: need to test

                String command = "sh " + remoteInitFileName;
                int exitStatus = executeRemoteCommand(session, command, logger, slave.getExecuteInitScriptAsRoot(), slave.getAdminPassword());
                if (exitStatus != 0) {
                    if (slave.getDoNotUseMachineIfInitFails()) {
                        LOGGER.log(Level.SEVERE, "AzureSSHLauncher: launch: init script failed: exit code={0} (marking slave for deletion)", exitStatus);
                        cleanUpReason = Messages._Slave_Failed_Init_Script();
                        return;
                    }
                    else {
                        LOGGER.log(Level.INFO, "AzureSSHLauncher: launch: init script failed: exit code={0} (ignoring)", exitStatus);
                    }
                } else {
                    LOGGER.info("AzureSSHLauncher: launch: init script got executed successfully");
                }
                // Create tracking file
                executeRemoteCommand(session, "touch ~/.azure-slave-init", logger);
            }

            LOGGER.info("AzureSSHLauncher: launch: checking for java runtime");

            if (executeRemoteCommand(session, "java -fullversion", logger) != 0) {
                LOGGER.info("AzureSSHLauncher: launch: Java not found. "
                        + "At a minimum init script should ensure that java runtime is installed");
                handleLaunchFailure(slave, Constants.SLAVE_POST_PROV_JAVA_NOT_FOUND);
                return;
            }

            LOGGER.info("AzureSSHLauncher: launch: java runtime present, copying slaves.jar to remote");
            InputStream inputStream = new ByteArrayInputStream(Jenkins.getInstance().getJnlpJars("slave.jar").
                    readFully());
            copyFileToRemote(session, inputStream, "slave.jar");

            String jvmopts = slave.getJvmOptions();
            String execCommand = "java " + (StringUtils.isNotBlank(jvmopts) ? jvmopts : "") + " -jar slave.jar";
            LOGGER.log(Level.INFO, "AzureSSHLauncher: launch: launching slave agent: {0}", execCommand);

            final ChannelExec jschChannel = (ChannelExec) session.openChannel("exec");
            jschChannel.setCommand(execCommand);
            jschChannel.connect();
            LOGGER.info("AzureSSHLauncher: launch: Connected successfully");

            computer.setChannel(jschChannel.getInputStream(), jschChannel.getOutputStream(), logger, new Listener() {

                @Override
                public void onClosed(Channel channel, IOException cause) {
                    if (jschChannel != null) {
                        jschChannel.disconnect();
                    }

                    if (cleanupSession != null) {
                        cleanupSession.disconnect();
                    }
                }
            });

            LOGGER.info("AzureSSHLauncher: launch: launched slave successfully");
            // There's a chance that it was marked as delete (for instance, if the node
            // was unreachable and then someone hit connect and it worked.  Reset the node cleanup
            // state to the default for the node.
            slave.clearCleanUpAction();
            successful = true;
        } catch (Exception e) {
            LOGGER.log(Level.INFO, "AzureSSHLauncher: launch: got exception ", e);
        } finally {
            if (!successful) {
                if (session != null) {
                    session.disconnect();
                }
                if (cleanUpReason == null) {
                    cleanUpReason = Messages._Slave_Failed_To_Connect();
                }
                // Set the machine to be deleted by the cleanup task
                slave.setCleanUpAction(CleanUpAction.DELETE, cleanUpReason);
            }
        }
    }

    private Session getRemoteSession(String userName, String password, String dnsName, int sshPort) throws Exception {
        LOGGER.log(Level.INFO,
                "AzureSSHLauncher: getRemoteSession: getting remote session for user {0} to host {1}:{2}",
                new Object[] { userName, dnsName, sshPort });
        JSch remoteClient = new JSch();
        try {
            final Session session = remoteClient.getSession(userName, dnsName, sshPort);
            session.setConfig("StrictHostKeyChecking", "no");
            session.setPassword(password);
            // pinging server for every 1 minutes to keep the connection alive
            session.setServerAliveInterval(60 * 1000);
            session.connect();
            LOGGER.log(Level.INFO,
                    "AzureSSHLauncher: getRemoteSession: Got remote session for user {0} to host {1}:{2}",
                    new Object[] { userName, dnsName, sshPort });
            return session;
        } catch (JSchException e) {
            LOGGER.log(Level.SEVERE,
                    "AzureSSHLauncher: getRemoteSession: Got exception while connecting to remote host {0}:{1} {2}",
                    new Object[] { dnsName, sshPort, e.getMessage() });
            throw e;
        }
    }

    private void copyFileToRemote(Session jschSession, InputStream stream, String remotePath) throws Exception {
        LOGGER.log(Level.INFO, "AzureSSHLauncher: copyFileToRemote: Initiating file transfer to {0}", remotePath);
        ChannelSftp sftpChannel = null;

        try {
            sftpChannel = (ChannelSftp) jschSession.openChannel("sftp");
            sftpChannel.connect();
            sftpChannel.put(stream, remotePath);

            if (!sftpChannel.isClosed()) {
                try {
                    LOGGER.warning(
                            "AzureSSHLauncher: copyFileToRemote: Channel is not yet closed , waiting for 10 seconds");
                    Thread.sleep(10 * 1000);
                } catch (InterruptedException e) {
                    //ignore error
                }
            }
            LOGGER.log(Level.INFO, "AzureSSHLauncher: copyFileToRemote: copied file Successfully to {0}", remotePath);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE,
                    "AzureSSHLauncher: copyFileToRemote: Error occurred while copying file to remote host {0}",
                    e.getMessage());
            throw e;
        } finally {
            try {
                if (sftpChannel != null) {
                    sftpChannel.disconnect();
                }
            } catch (Exception e) {
                // ignore silently
            }
        }
    }
    
    /**
     * Helper method for most common call (without root)
     * @param jschSession
     * @param command
     * @param logger
     * @return 
     */
    private int executeRemoteCommand(final Session jschSession, final String command, final PrintStream logger) {
        return executeRemoteCommand(jschSession, command, logger, false, null);
    }

    /**
     * Executes a remote command, as root if desired
     * @param jschSession
     * @param command
     * @param logger
     * @param executeAsRoot
     * @param passwordIfRoot
     * @return
     */
    private int executeRemoteCommand(final Session jschSession, final String command, final PrintStream logger, boolean executeAsRoot, String passwordIfRoot) {
        ChannelExec channel = null;
        try {
            // If root, modify the command to set up sudo -S
            String finalCommand = null;
            if (executeAsRoot) {
                finalCommand = "sudo -S -p '' " + command;
            }
            else {
                finalCommand = command;
            }
            LOGGER.log(Level.INFO, "AzureSSHLauncher: executeRemoteCommand: starting {0}", command);

            channel = (ChannelExec) jschSession.openChannel("exec");
            channel.setCommand(finalCommand);
            channel.setInputStream(null);
            channel.setErrStream(System.err);
            final InputStream inputStream = channel.getInputStream();
            final InputStream errorStream = channel.getErrStream();
            final OutputStream outputStream = channel.getOutputStream();
            channel.connect(60 * 1000);

            // If as root, push the password
            if (executeAsRoot) {
                outputStream.write((passwordIfRoot + "\n").getBytes());
                outputStream.flush();
            }

            // Read from input stream
            try {
                IOUtils.copy(inputStream, logger);
            } finally {
                IOUtils.closeQuietly(inputStream);
            }

            // Read from error stream	
            try {
                IOUtils.copy(errorStream, logger);
            } finally {
                IOUtils.closeQuietly(errorStream);
            }

            if (!channel.isClosed()) {
                try {
                    LOGGER.log(Level.WARNING,
                            "{0}: executeRemoteCommand: Channel is not yet closed, waiting for 10 seconds",
                            this.getClass().getSimpleName());
                    Thread.sleep(10 * 1000);
                } catch (InterruptedException e) {
                    //ignore error
                }
            }

            LOGGER.info("AzureSSHLauncher: executeRemoteCommand: executed successfully");
            return channel.getExitStatus();
        } catch (JSchException jse) {
            LOGGER.log(Level.SEVERE,
                    "AzureSSHLauncher: executeRemoteCommand: got exception while executing remote command\n" + command,
                    jse);
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "IO failure running {0}", command);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Unexpected exception running {0}", command);
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
        // If control reached here then it indicates error
        return -1;
    }

    private Session connectToSsh(final AzureSlave slave) throws Exception {
        LOGGER.info("AzureSSHLauncher: connectToSsh: start");
        Session session = null;
        int maxRetryCount = 6;
        int currRetryCount = 0;

        while (true) {
            currRetryCount++;
            try {
                session = getRemoteSession(slave.getAdminUserName(), slave.getAdminPassword(), slave.getPublicDNSName(),
                        slave.getSshPort());
                LOGGER.info("AzureSSHLauncher: connectToSsh: Got remote connection");
            } catch (Exception e) {
                // Retry till max count and throw exception if not successful even after that
                if (currRetryCount >= maxRetryCount) {
                    throw e;
                }
                // keep retrying till time out
                LOGGER.log(Level.SEVERE,
                        "AzureSSHLauncher: connectToSsh: Got exception while connecting to remote host. "
                        + "Will be trying again after 1 minute {0}", e.getMessage());
                Thread.sleep(1 * 60 * 1000);
                // continue again
                continue;
            }
            return session;
        }
    }

    /**
     * Mark the slave for deletion and queue the corresponding template for verification
     * @param slave
     * @param message 
     */
    private void handleLaunchFailure(AzureSlave slave, String message) {
        // Queue the template for verification in case something happened there.
        AzureCloud azureCloud = slave.getCloud();
        if (azureCloud != null) {
            AzureSlaveTemplate slaveTemplate = azureCloud.getAzureSlaveTemplate(slave.getTemplateName());
            if (slaveTemplate != null) {
                slaveTemplate.handleTemplateProvisioningFailure(message, FailureStage.POSTPROVISIONING);
            }
        }
    }

    @Override
    public Descriptor<ComputerLauncher> getDescriptor() {
        throw new UnsupportedOperationException();
    }
}
