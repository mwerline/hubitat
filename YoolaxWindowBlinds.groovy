/**
 *
 *  Copyright (C) 2021 Matt Werline
 *
 *	Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *	in compliance with the License. You may obtain a copy of the License at:
 *
 *		http://www.apache.org/licenses/LICENSE-2.0
 *
 *	Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *	on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *	for the specific language governing permissions and limitations under the License.
 *
 *
 *  IMPORTANT: Use the configure button after device is added to Hubitat
 */
import hubitat.zigbee.zcl.DataType

metadata {
    definition(name: "Yoolax Window Blinds", namespace: "mwerline", author: "Matt Werline", importUrl: "https://raw.githubusercontent.com/mwerline/hubitat/main/YoolaxWindowBlinds.groovy") {
        capability "Actuator"
        capability "Configuration"
        capability "Refresh"
        capability "Window Shade"
        capability "Health Check"
        capability "Switch Level"
        capability "Battery"

        command "pause"
        command "hardOpen"
        command "hardClose"
        command "setMidpoint"

       	attribute "lastCheckin", "String"
        attribute "lastOpened", "String"
        attribute "deviceLevel", "Number"

        fingerprint deviceJoinName: "Yoolax Motorized Window Blinds", model: "D10110", profileId: "0104", endpointId: 01, inClusters: "0000,0001,0003,0004,0005,0020,0102", outClusters: "0003,0019", manufacturer: "yooksmart"
    }
    
    preferences { 
        input name: "openLevel", type: "number", defaultValue: 100, range: "0..100", title: "Max open level", description: "Percentage used for the Shade's Fully Opened Level"    
        input name: "closeLevel", type: "number", defaultValue: 0, range: "0..100", title: "Closed level", description: "Percentage used for the Shade's Fully Closed Level"    
        input name: "midLevel", type: "number", defaultValue: 50, range: "0..100", title: "Midpoint level", description: "Percentage used for the Shade's Midpoint Level"    
        
        input name: "debugOutput", type: "bool", title: "Enable debug logging?", defaultValue: true
		input name: "descTextOutput", type: "bool", title: "Enable descriptionText logging?", defaultValue: true
    }
}

private getCLUSTER_BATTERY_LEVEL() { 0x0001 }
private getCLUSTER_WINDOW_COVERING() { 0x0102 }
private getCOMMAND_OPEN() { 0x00 }
private getCOMMAND_CLOSE() { 0x01 }
private getCOMMAND_PAUSE() { 0x02 }
private getCOMMAND_GOTO_LIFT_PERCENTAGE() { 0x05 }
private getATTRIBUTE_POSITION_LIFT() { 0x0008 }
private getATTRIBUTE_CURRENT_LEVEL() { 0x0000 }
private getCOMMAND_MOVE_LEVEL_ONOFF() { 0x04 }
private getBATTERY_PERCENTAGE_REMAINING() { 0x0021 }

// Utility function to Collect Attributes from event
private List<Map> collectAttributes(Map descMap) {
	List<Map> descMaps = new ArrayList<Map>()

	descMaps.add(descMap)

	if (descMap.additionalAttrs) {
		descMaps.addAll(descMap.additionalAttrs)
	}

	return descMaps
}

// Parse incoming device reports to generate events
def parse(String description) {
    if (debugOutput) log.debug "Parse report description:- '${description}'."
    def now = new Date().format("yyyy MMM dd EEE h:mm:ss a", location.timeZone)

    //  Send Event for device heartbeat    
    sendEvent(name: "lastCheckin", value: now)
    
    // Parse Event
    if (description?.startsWith("read attr -")) {
        Map descMap = zigbee.parseDescriptionAsMap(description)
        
        // Zigbee Window Covering Event
        if (descMap?.clusterInt == CLUSTER_WINDOW_COVERING && descMap.value) {
            if (debugOutput) log.debug "attr: ${descMap?.attrInt}, value: ${descMap?.value}, descValue: ${Integer.parseInt(descMap.value, 16)}, ${device.getDataValue("model")}"

            // Parse Attributes into a List
            List<Map> descMaps = collectAttributes(descMap)
            
            // Get the Current Shade Position
            def liftmap = descMaps.find { it.attrInt == ATTRIBUTE_POSITION_LIFT }
            if (liftmap && liftmap.value) levelEventHandler(zigbee.convertHexToInt(liftmap.value))
        } else if (descMap?.clusterInt == CLUSTER_BATTERY_LEVEL && descMap.value) {
            if(descMap?.value) {
                batteryLevel = Integer.parseInt(descMap.value, 16)
                batteryLevel = convertBatteryLevel(batteryLevel)
                if (debugOutput) log.debug "attr: '${descMap?.attrInt}', value: '${descMap?.value}', descValue: '${batteryLevel}'."
                sendEvent(name: "battery", value: batteryLevel)
            } else {
                if (debugOutput) log.debug "failed to parse battery level attr: '${descMap?.attrInt}', value: '${descMap?.value}'."
            }
        }
    }
}

// Convert Battery Level to (0-100 Scale)
def convertBatteryLevel(rawValue) {
    def batteryLevel = rawValue - 50
    batteryLevel = batteryLevel * 100
    batteryLevel = batteryLevel.intdiv(150)
    return batteryLevel
}

// Handle Level Change Reports
def levelEventHandler(currentLevel) {
    def lastLevel = device.currentValue("deviceLevel")
    if (debugOutput) log.debug "levelEventHandler - currentLevel: '${currentLevel}' lastLevel: '${lastLevel}'."

    if (lastLevel == "undefined" || currentLevel == lastLevel) { 
        // Ignore invalid reports
        if (debugOutput) log.debug "undefined lastLevel"
        runIn(3, "updateFinalState", [overwrite:true])
    } else {
        setReportedLevel(currentLevel)
        if (currentLevel == 0 || currentLevel <= closeLevel) {
            sendEvent(name: "windowShade", value: currentLevel == closeLevel ? "closed" : "open")
        } else {
            if (lastLevel < currentLevel) {
                sendEvent([name:"windowShade", value: "opening"])
            } else if (lastLevel > currentLevel) {
                sendEvent([name:"windowShade", value: "closing"])
            }
        }
    }
    if (lastLevel != currentLevel) {
        if (debugOutput) log.debug "newlevel: '${newLevel}' currentlevel: '${currentLevel}' lastlevel: '${lastLevel}'."
        runIn(1, refresh)
    }
}

def setReportedLevel(rawLevel) {
   sendEvent(name: "deviceLevel", value: rawLevel)
   if(rawLevel == closeLevel) {
       sendEvent(name: "level", value: 0)
       sendEvent(name: "position", value: 0)
   } else if (rawLevel ==  midLevel) {
       sendEvent(name: "level", value: 10)
       sendEvent(name: "position", value: 10)       
   } else if (rawLevel ==  openLevel) {
       sendEvent(name: "level", value: 100)
       sendEvent(name: "position", value: 100)       
   } else {
       sendEvent(name: "level", value: rawLevel)
       sendEvent(name: "position", value: rawLevel)              
   }
}

def updateFinalState() {
    def level = device.currentValue("deviceLevel")
    if (debugOutput) log.debug "Running updateFinalState: '${level}'."
    sendEvent(name: "windowShade", value: level == closeLevel ? "closed" : "open")
}
                 
// Open Blinds Command
def open() {
    def currentLevel = device.currentValue("deviceLevel")
    if(currentLevel >= openLevel) {
        if (descTextOutput) log.info "Blinds are already Fully Opened."
    } else if(currentLevel == midLevel) {
        hardOpen()
    } else if(currentLevel > midLevel && currentLevel < closeLevel) {
        hardOpen()
    } else {
        setMidpoint()
    }
}

// Hard Open Blinds Command
def hardOpen() {
    if (descTextOutput) log.info "Fully Opening the Blinds."
    setHardLevel(openLevel)
}

// Hard Close Blinds Command
def close() {
    if (descTextOutput) log.info "Closing the Blinds."
    def currentLevel = device.currentValue("deviceLevel")
    if(currentLevel == closeLevel) {
        if (descTextOutput) log.info "Blinds are already Fully Closed."
//    } else if(currentLevel == midLevel) {
//        hardClose()
//    } else if(currentLevel > closeLevel) {
//        setMidpoint()
    } else {
        hardClose()
    }
}

// Close Blinds Command
def hardClose() {
    if (descTextOutput) log.info "Fully Closing the Blinds."
    setHardLevel(closeLevel)
}

// Close Blinds Command
def setMidpoint() {
    if (descTextOutput) log.info "Moving Blinds to MidPoint Value."
    setHardLevel(midLevel)
}

// Set Level Command
def setHardLevel(value) {
    if (debugOutput) log.debug "Setting the Blinds to level '${value}'."
    value = value.toInteger()
    runIn(5, refresh)
    return zigbee.command(CLUSTER_WINDOW_COVERING, COMMAND_GOTO_LIFT_PERCENTAGE, zigbee.convertToHexString(99 - value, 2))
}

// Set Position Command
def setPosition(value) {
    if (descTextOutput) log.info "Setting the Blinds to level '${value}'."
    setHardLevel(value)
}

// Set Level Command
def setLevel(value, rate = null) {
    if (descTextOutput) log.info "Setting the Blinds to level '${value}'."
    setHardLevel(value)
}

// Return Level adjusted based on the Min/Max settings
def restrictLevelValue(value) {
    return value
}

// Pause the blinds
def pause() {
    if (descTextOutput) log.info "Pausing the Blinds."
    zigbee.command(CLUSTER_WINDOW_COVERING, COMMAND_PAUSE)
}

// Stop Postition Change
def stopPositionChange() {
    pause()
}

// Start Postition Change
def startPositionChange(direction) {
    if(direction == "open") {
        open()
    } else if (direction == "close") {
        close()
    }
}

// Refresh the current state of the blinds
def refresh() {
    if (debugOutput) log.debug "Running refresh()"
    return zigbee.readAttribute(CLUSTER_WINDOW_COVERING, ATTRIBUTE_POSITION_LIFT) + zigbee.readAttribute(CLUSTER_BATTERY_LEVEL, BATTERY_PERCENTAGE_REMAINING)
}

// Configure Device Reporting and Bindings
def configure() {
    if (descTextOutput) log.info "Configuring Device Reporting and Bindings."
    sendEvent(name: "checkInterval", value: 2 * 60 * 60 + 2 * 60, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID])
    def cmds = zigbee.configureReporting(CLUSTER_WINDOW_COVERING, ATTRIBUTE_POSITION_LIFT, DataType.UINT8, 0, 600, 0x01) + zigbee.configureReporting(CLUSTER_BATTERY_LEVEL, 0x0021, DataType.UINT8, 600, 21600, 0x01)
    return refresh() + cmds
}

// Driver Update Event
def updated() {
    if (debugOutput) log.debug "Running updated()"
    unschedule()

    // Disable logging automaticly after 30 minutes
    if (debugOutput) {
        runIn(1800,logsOff)
        log.debug "Debug loging is currently enabled. Will automaticly be disabled in 30 minutes."
    }
}

// Automaticly Disable Debug logging Event Handeler
def logsOff(){
	log.info "Debug Automaticly Disabled."
	device.updateSetting("debugOutput",[value:"false",type:"bool"])
}
