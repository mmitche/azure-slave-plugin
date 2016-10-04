/*
 * Copyright 2016 Microsoft, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.microsoftopentechnologies.azure;

import com.microsoftopentechnologies.azure.util.Constants;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.ComputerPanelBox;
import java.util.HashMap;
import java.util.Map;

/**
 *
 * @author mmitche
 */
@Extension
public class AzureComputerPanelBox extends ComputerPanelBox {
    public Map<String, String> getAccessOptions() {
        Computer computer = super.getComputer();
        AzureComputer azureComputer = (AzureComputer)computer;
        AzureSlave azureSlave = azureComputer.getNode();
        HashMap<String, String> accessInfo = new HashMap<String, String>();
        
        String dnsName = azureSlave.getPublicDNSName();
        
        if (azureSlave.getSlaveLaunchMethod().equalsIgnoreCase("SSH")) {
            // We know it uses SSH, so add that one
            accessInfo.put("SSH (used for startup)", String.format("%s:%d", dnsName, azureSlave.getSshPort()));
        }
        
        if (azureSlave.getOsType().equals(Constants.OS_TYPE_LINUX)) {
            // Could potentially use SSH, so add that
            accessInfo.put("SSH (typical config)", String.format("%s:%d", dnsName, 22));
        }
        else {
            accessInfo.put("RDP (typical config)", String.format("%s:%d", dnsName, 3389));
        }
        
        return accessInfo;
    }
    
    public boolean isApplicable() {
        return super.getComputer() instanceof AzureComputer && 
            ((AzureComputer)super.getComputer()).getNode() != null;
    }
}
