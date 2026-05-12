definition(
    name: "Device Health Monitor",
    namespace: "jdthomas24",
    author: "jdthomas24",
    description: "Monitor device check-in health across Zigbee, Z-Wave, Matter, Hub Mesh, LAN, Virtual and Hub Variable — learns each device's normal pattern and alerts you when something goes quiet. Includes device state column, Hub Mesh overview, state change tracking, and richer notifications.",
    category: "Convenience",
    importUrl: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/refs/heads/main/Device%20Health%20Monitor/Raw%20Code/DeviceHealthMonitor.groovy",
    iconUrl: "",
    iconX2Url: "",
    version: "1.4.6",
    doNotFocus: true
)

preferences {
    page(name: "mainPage")
    page(name: "activitySummaryPage")
    page(name: "problemDevicesPage")
    page(name: "sendNotificationPage")
    page(name: "forceScanPage")
    page(name: "resetHistoryPage")
    page(name: "resetHistoryConfirmPage")
    page(name: "snoozeManagePage")
    page(name: "protocolOverridePage")
    page(name: "hubMeshSummaryPage")
    page(name: "infoPage")
}

// ============================================================
// ===================== LIFECYCLE ===========================
// ============================================================
def installed() {
    if (debugEnabled()) log.debug "Device Health Monitor installed"
    applyCustomLabel()
    initialize()
}

def updated() {
    if (debugEnabled()) log.debug "Device Health Monitor updated"
    applyCustomLabel()
    unschedule()
    unsubscribe()

    if (settings?.enableSnooze == false) {
        state.snoozed = [:]
        if (debugEnabled()) log.debug "Snooze disabled — all active snoozes cleared"
    }

    initialize()
    if (debugEnabled()) runIn(1800, disableDebugLogging)
}

def initialize() {
    if (debugEnabled()) log.debug "Device Health Monitor initializing"
    if (state.history       == null) state.history       = [:]
    if (state.health        == null) state.health        = [:]
    if (state.snoozed       == null) state.snoozed       = [:]
    if (state.verifying     == null) state.verifying     = [:]
    if (state.stateHistory  == null) state.stateHistory  = [:]
    scheduleScanInterval()
    scheduleReportFrequency()
    if (debugEnabled()) log.debug "Monitoring ${getAllMonitoredDevices().findAll { getProtocol(it) != 'Unknown' }.size()} device(s)"
    runIn(5, scanAllDevices)
}

def debugEnabled() { return settings?.debugMode == true }

def disableDebugLogging() {
    log.info "Device Health Monitor: auto-disabling debug logging after 30 minutes"
    app.updateSetting("debugMode", [value: false, type: "bool"])
}

def applyCustomLabel() {
    if (settings?.customAppName) {
        if (app.label != settings?.customAppName) {
            app.updateLabel(settings.customAppName)
        }
    }
}

// ============================================================
// ===================== SNOOZE ==============================
// ============================================================
def snoozeEnabled() { return settings?.enableSnooze == true }

def snoozeDevice(deviceId) {
    if (!snoozeEnabled()) return
    def hours = (settings?.snoozeDurationHours ?: 24).toInteger()
    def until = now() + (hours * 3600000)
    if (!state.snoozed) state.snoozed = [:]
    state.snoozed[deviceId] = until
    state.snoozed = state.snoozed
}

def unsnoozeDevice(deviceId) {
    state.snoozed?.remove(deviceId)
    state.snoozed = state.snoozed ?: [:]
}

def isDeviceSnoozed(deviceId) {
    if (!snoozeEnabled()) return false
    def until = state.snoozed?.get(deviceId)
    if (!until) return false
    if (until >= now()) return true
    def s = state.snoozed ?: [:]
    s.remove(deviceId)
    state.snoozed = s
    return false
}

def getSnoozedHoursRemaining(deviceId) {
    def until = state.snoozed?.get(deviceId)
    if (!until) return 0
    return Math.ceil((until - now()) / 3600000).toInteger()
}

def formatSnoozeRemaining(deviceId) {
    def until   = state.snoozed?.get(deviceId)
    if (!until) return "expired"
    def msLeft  = until - now()
    def days    = (msLeft / 86400000).toInteger()
    def hours   = ((msLeft % 86400000) / 3600000).toInteger()
    def minutes = ((msLeft % 3600000) / 60000).toInteger()
    if (days >= 1)  return "${days}d ${hours}h remaining"
    if (hours >= 1) return "${hours}h ${minutes}m remaining"
    return "${minutes}m remaining"
}

// ============================================================
// ===================== PROTOCOL DETECTION ==================
// ============================================================
def getAllMonitoredDevices() { return monitoredDevices ?: [] }

def getRawProtocol(device) {
    try {
        def driverName = (device.typeName ?: "").toLowerCase()
        if (driverName.contains("hub variable") || driverName.contains("variable connector")) return "Hub Variable"
        if (driverName.contains("virtual")) return "Virtual"
        def devData = device.properties
        if (devData?.controllerType == "LNK") {
            def encoding = device.getDataValue("Encoding")
            if (encoding?.toLowerCase() == "zigbee")                          return "Hub Mesh (Zigbee)"
            if (encoding?.toLowerCase() == "z-wave")                          return "Hub Mesh (Z-Wave)"
            if (device.getDataValue("In Clusters")  != null)                  return "Hub Mesh (Zigbee)"
            if (device.getDataValue("inClusters")   != null)                  return "Hub Mesh (Zigbee)"
            if (device.getDataValue("Out Clusters") != null)                  return "Hub Mesh (Zigbee)"
            if (device.getDataValue("outClusters")  != null)                  return "Hub Mesh (Zigbee)"
            if (device.getDataValue("zigbeeId")     != null)                  return "Hub Mesh (Zigbee)"
            if (device.getDataValue("zigbeeNodeType") != null)                return "Hub Mesh (Zigbee)"
            if (device.getDataValue("zwaveSecurePairingComplete") != null)    return "Hub Mesh (Z-Wave)"
            if (device.getDataValue("secureInClusters")           != null)    return "Hub Mesh (Z-Wave)"
            if (device.getDataValue("Zw Node Info")               != null)    return "Hub Mesh (Z-Wave)"
            if (driverName.contains("zigbee") || driverName.contains("thirdreality") || driverName.contains("third reality")) return "Hub Mesh (Zigbee)"
            if (driverName.contains("z-wave") || driverName.contains("zwave")) return "Hub Mesh (Z-Wave)"
            if (driverName.contains("matter"))                                return "Hub Mesh (Matter)"
            def manufacturer = (device.getDataValue("Manufacturer") ?: "").toLowerCase()
            if (manufacturer in ["centralite", "lumi", "ikea", "sengled",
                                 "osram", "philips", "samsung", "smartthings",
                                 "sonoff", "tuya", "third reality", "thirdreality",
                                 "third_reality"]) {
                return "Hub Mesh (Zigbee)"
            }
            return "Hub Mesh"
        }
        if (devData?.controllerType == "ZGB") return "Zigbee"
        if (devData?.controllerType == "ZWV") return "Z-Wave"
        if (devData?.controllerType == "MAT") return "Matter"
        if (device.getDataValue("Endpoint Id")                != null) return "Zigbee"
        if (device.getDataValue("endpointId")                 != null) return "Zigbee"
        if (device.getDataValue("zigbeeNodeType")             != null) return "Zigbee"
        if (device.getDataValue("zigbeeId")                   != null) return "Zigbee"
        if (device.getDataValue("In Clusters")                != null) return "Z-Wave"
        if (device.getDataValue("inClusters")                 != null) return "Z-Wave"
        if (device.getDataValue("zwaveSecurePairingComplete") != null) return "Z-Wave"
        if (device.getDataValue("secureInClusters")           != null) return "Z-Wave"
        if (device.getDataValue("Zw Node Info")               != null) return "Z-Wave"
        return "LAN"
    } catch (e) {
        if (debugEnabled()) log.debug "getRawProtocol error for ${device.displayName}: ${e.message}"
    }
    return "Unknown"
}

def getProtocol(device) {
    try {
        def override = settings["protocolOverride_${device.id}"]
        if (override && override != "" && override != "Auto-detect") return override
        return getRawProtocol(device)
    } catch (e) {
        if (debugEnabled()) log.debug "getProtocol error for ${device.displayName}: ${e.message}"
    }
    return "Unknown"
}

def getProtocolColor(protocol) {
    switch (protocol) {
        case "Zigbee":             return "#3b82f6"
        case "Hub Mesh (Zigbee)":  return "#3b82f6"
        case "Z-Wave":             return "#8b5cf6"
        case "Hub Mesh (Z-Wave)":  return "#8b5cf6"
        case "Matter":             return "#e65100"
        case "Hub Mesh (Matter)":  return "#e65100"
        case "Hub Mesh":           return "#06b6d4"
        case "LAN":                return "#14b8a6"
        case "Virtual":            return "#ec4899"
        case "Hub Variable":       return "#eab308"
        case "Bluetooth":          return "#06b6d4"
        default:                   return "#c0c4cc"
    }
}

def isUnresolvableProtocol(protocol) {
    return protocol in ["Hub Mesh", "LAN", "Virtual", "Hub Variable"]
}

def usesFilteredSampling(protocol) {
    return protocol in ["Virtual", "Hub Variable"]
}

def isHueDevice(device) {
    def dn  = (device.typeName ?: "").toLowerCase()
    def dni = (device.deviceNetworkId ?: "").toLowerCase()
    if (dni.startsWith("hue/")) return true
    if (dn.startsWith("cocohue")) return true
    if (dn.contains("huebridgebulb") || dn.contains("huebridge")) return true
    return false
}

def findHueBridge() {
    return getAllMonitoredDevices().find { device ->
        def dn  = (device.typeName ?: "").toLowerCase()
        def dni = (device.deviceNetworkId ?: "").toLowerCase()
        (dni.startsWith("hue/") && dn.contains("bridge")) ||
        dn.contains("cocohue bridge") ||
        (dn.contains("huebridge") && !dn.contains("bulb"))
    }
}

def isModeOK() {
    if (!settings?.enableModeRestriction) return true
    if (!settings?.restrictedModes) return true
    return settings.restrictedModes.contains(location.mode)
}

// ============================================================
// ===================== LOW ACTIVITY ========================
// ============================================================
def isLowActivity(deviceId) {
    def data = state.history?.get(deviceId)
    if (!data) return false
    def samples  = data?.samples?.size() ?: 0
    def lastSeen = data?.lastSeen ?: now()
    def ageMs    = now() - lastSeen
    def ageDays  = ageMs / (1000.0 * 60 * 60 * 24)
    return (ageDays >= 7 && samples < 3)
}

// ============================================================
// ===================== STATE TRACKING ======================
// ============================================================

def getMeaningfulAttributes(device) {
    def known = [
        "switch", "contact", "motion", "lock", "presence", "water",
        "smoke", "carbonMonoxide", "tamper", "shock", "valve", "door",
        "windowShade", "sleeping", "printState", "thermostatOperatingState",
        "thermostatMode", "mediaPlaybackStatus", "transportStatus", "chargingState",
        "currentStatus", "printerStatus", "status", "deviceStatus",
        "healthStatus", "connectionStatus", "operatingState", "mode"
    ]

    def driverName  = (device.typeName ?: "").toLowerCase()
    def deviceName  = (device.name ?: "").toLowerCase()
    def displayName = (device.displayName ?: "").toLowerCase()
    def nameCheck   = "${driverName} ${deviceName} ${displayName}"

    def isPrinter = nameCheck.contains("moonraker") || nameCheck.contains("klipper") ||
                    nameCheck.contains("octoprint") || nameCheck.contains("bambu") ||
                    nameCheck.contains("prusa") || nameCheck.contains("3d print") ||
                    nameCheck.contains("printer")
    if (isPrinter) known += ["progress", "currentLayer", "printTime", "remainingTime",
                              "printTimeLeft", "totalLayers", "fileName"]

    def isThermostat = nameCheck.contains("thermostat") || nameCheck.contains("ecobee") ||
                       nameCheck.contains("nest") || nameCheck.contains("honeywell") ||
                       nameCheck.contains("sinope")
    if (isThermostat) known += ["heatingSetpoint", "coolingSetpoint", "thermostatSetpoint",
                                 "temperature", "humidity"]

    def isEV = nameCheck.contains("tesla") || nameCheck.contains("electric vehicle")
    if (isEV) known += ["battery", "batteryLevel", "chargingState", "range", "odometer"]

    def isMedia = nameCheck.contains("sonos") || nameCheck.contains("denon") ||
                  nameCheck.contains("yamaha") || nameCheck.contains("roku") ||
                  nameCheck.contains("apple tv") || nameCheck.contains("media player")
    if (isMedia) known += ["trackDescription", "trackData", "mediaPlaybackStatus",
                            "transportStatus", "volume", "level"]

    // FIX 1: Scan declared capabilities so newly-paired devices whose currentStates
    // hasn't fully populated yet still surface their attributes correctly.
    // On a fresh hub many attributes return null/empty from currentStates and were
    // being silently filtered out, causing devices to miss the override list.
    def found = [] as Set
    try {
        device.capabilities?.each { cap ->
            cap?.attributes?.each { attr ->
                if (attr?.name && attr.name in known) found << attr.name
            }
        }
    } catch (e) {
        if (debugEnabled()) log.debug "getMeaningfulAttributes capability scan error for ${device.displayName}: ${e.message}"
    }

    try {
        device.currentStates?.each { s ->
            if (!s?.name || s?.value == null) return
            def val = s.value.toString().trim()
            if (val in ["", "null", "0"]) return
            def skipAttrs = ["battery", "batteryLastReplaced", "lastCheckin",
                             "temperature", "humidity", "illuminance", "pressure",
                             "carbonDioxide", "energy", "power", "voltage",
                             "current", "frequency", "rssi", "lqi", "driver",
                             "notPresentCounter", "restoredCounter", "firmware"]
            if (s.name in skipAttrs && !(s.name in known)) return
            if (val.isNumber() && !(s.name in known)) return
            found << s.name
        }
    } catch (e) {
        if (debugEnabled()) log.debug "getMeaningfulAttributes currentStates error for ${device.displayName}: ${e.message}"
    }
    return found.toList().sort()
}

// FIX 2: shouldShowStateOverride() replaces the old size() > 1 gate.
// Shows override option for: (a) multi-attribute devices as before,
// (b) known single-primary-attribute types (presence/Life360, lock, water,
// smoke, contact, motion, valve, etc.) where auto-detection could be wrong,
// (c) newly paired devices before currentStates has fully populated.
// hasMultipleMeaningfulAttributes() kept as alias so nothing else breaks.
def shouldShowStateOverride(device) {
    def attrs = getMeaningfulAttributes(device)
    if (attrs.size() > 1) return true
    if (attrs.size() == 0) return false

    def driverName  = (device.typeName ?: "").toLowerCase()
    def deviceName  = (device.name ?: "").toLowerCase()
    def displayName = (device.displayName ?: "").toLowerCase()
    def nameCheck   = "${driverName} ${deviceName} ${displayName}"

    def knownSingleAttrTypes = [
        "life360", "presence", "arrival", "mobile app",
        "lock", "deadbolt",
        "water", "leak",
        "smoke", "carbon monoxide",
        "contact", "door sensor", "window sensor",
        "motion", "motion sensor", "pir",
        "valve", "shock", "vibration", "tamper"
    ]
    if (knownSingleAttrTypes.any { nameCheck.contains(it) }) return true

    def overrideCandidateAttrs = [
        "presence", "lock", "water", "smoke", "carbonMonoxide",
        "contact", "motion", "tamper", "shock", "valve", "door"
    ]
    if (attrs[0] in overrideCandidateAttrs) return true

    return false
}

def hasMultipleMeaningfulAttributes(device) {
    return shouldShowStateOverride(device)
}

def getOverrideStateDisplay(device, attrName) {
    try {
        def val = device.currentValue(attrName)
        if (val == null) return null
        def vs = val.toString().trim()
        if (vs in ["", "null"]) return null
        def vl = vs.toLowerCase()

        switch (attrName) {
            case "switch":
                def isOn = vl == "on"
                return [label: isOn ? "ON" : "OFF",
                        color: isOn ? "#1565c0" : "#c0c4cc", isAlert: false, type: attrName]
            case "contact":
                def isOpen = vl == "open"
                return [label: isOpen ? "Open" : "Closed",
                        color: isOpen ? "#e65100" : "#c0c4cc", isAlert: isOpen, type: attrName]
            case "motion":
                def isActive = vl == "active"
                return [label: isActive ? "Active" : "Inactive",
                        color: isActive ? "#1565c0" : "#c0c4cc", isAlert: false, type: attrName]
            case "lock":
                def isUnlocked = vl == "unlocked"
                return [label: isUnlocked ? "Unlocked" : "Locked",
                        color: isUnlocked ? "#e65100" : "#c0c4cc", isAlert: isUnlocked, type: attrName]
            case "presence":
                def isPresent = vl == "present"
                return [label: isPresent ? "Present" : "Not Present",
                        color: isPresent ? "#1565c0" : "#c0c4cc", isAlert: false, type: attrName]
            case "water":
                def isWet = vl == "wet"
                return [label: isWet ? "Wet" : "Dry",
                        color: isWet ? "#c62828" : "#c0c4cc", isAlert: isWet, type: attrName]
            case "smoke":
            case "carbonMonoxide":
            case "tamper":
                def isDetected = vl == "detected"
                return [label: isDetected ? "${attrName.capitalize()}!" : "Clear",
                        color: isDetected ? "#c62828" : "#c0c4cc", isAlert: isDetected, type: attrName]
            case "valve":
            case "door":
                def isOpen2 = vl in ["open", "opening"]
                return [label: vs.capitalize(),
                        color: isOpen2 ? "#e65100" : "#c0c4cc", isAlert: isOpen2, type: attrName]
            case "printState":
            case "currentStatus":
            case "printerStatus":
                def isPrint = vl in ["printing", "busy"]
                def isPause = vl in ["paused", "pausing"]
                def isError = vl in ["error", "offline", "disconnected", "cancelled"]
                def color   = isPrint ? "#1565c0" : isPause ? "#e65100" :
                              isError ? "#c62828" : "#c0c4cc"
                return [label: vs.capitalize(), color: color, isAlert: isError, type: attrName]
            default:
                def isActive = vl in ["on", "active", "connected", "online", "running",
                                      "enabled", "playing", "present", "open"]
                def isAlert  = vl in ["offline", "disconnected", "error", "fault", "alarm",
                                      "wet", "detected"]
                def color    = isAlert ? "#c62828" : isActive ? "#1565c0" : "#c0c4cc"
                return [label: vs.capitalize(), color: color, isAlert: isAlert, type: attrName]
        }
    } catch (e) {
        if (debugEnabled()) log.debug "getOverrideStateDisplay error for ${device.displayName}: ${e.message}"
    }
    return null
}

def getCurrentStateDisplay(device) {
    try {
        def attrOverride = settings["stateAttrOverride_${device.id}"]
        if (attrOverride && attrOverride != "Auto-detect") {
            def overrideResult = getOverrideStateDisplay(device, attrOverride)
            if (overrideResult) return overrideResult
        }

        def driverName = (device.typeName ?: "").toLowerCase()

        def isContactDevice = driverName.contains("contact") ||
                              driverName.contains("door sensor") ||
                              driverName.contains("window sensor")
        if (isContactDevice) {
            def contact = device.currentValue("contact")
            if (contact != null) {
                def isOpen = contact.toString().toLowerCase() == "open"
                return [label: isOpen ? "Open" : "Closed", color: isOpen ? "#e65100" : "#c0c4cc", isAlert: isOpen, type: "contact"]
            }
        }

        def isMotionDevice = driverName.contains("motion sensor") ||
                             driverName.contains("motion detector") ||
                             driverName.contains("pir")
        if (isMotionDevice) {
            def motion = device.currentValue("motion")
            if (motion != null) {
                def isActive = motion.toString().toLowerCase() == "active"
                return [label: isActive ? "Active" : "Inactive", color: isActive ? "#1565c0" : "#c0c4cc", isAlert: false, type: "motion"]
            }
        }

        def isLockDevice = driverName.contains("lock") && !driverName.contains("unlock")
        if (isLockDevice) {
            def lock = device.currentValue("lock")
            if (lock != null) {
                def isUnlocked = lock.toString().toLowerCase() == "unlocked"
                return [label: isUnlocked ? "Unlocked" : "Locked", color: isUnlocked ? "#e65100" : "#c0c4cc", isAlert: isUnlocked, type: "lock"]
            }
        }

        def isPresenceDevice = driverName.contains("life360") ||
                               driverName.contains("presence") ||
                               driverName.contains("arrival") ||
                               driverName.contains("mobile")
        if (isPresenceDevice) {
            def presence = device.currentValue("presence")
            if (presence != null) {
                def isPresent = presence.toString().toLowerCase() == "present"
                return [label: isPresent ? "Present" : "Not Present", color: isPresent ? "#1565c0" : "#c0c4cc", isAlert: false, type: "presence"]
            }
        }

        def isPrinter = driverName.contains("moonraker") || driverName.contains("klipper") ||
                        driverName.contains("octoprint") || driverName.contains("bambu") ||
                        driverName.contains("prusa") || driverName.contains("3d print") ||
                        driverName.contains("printer")
        if (isPrinter) {
            def printerStatus = device.currentValue("printState") ?:
                                device.currentValue("currentStatus") ?:
                                device.currentValue("printerStatus") ?:
                                device.currentValue("status")
            if (printerStatus != null) {
                def ps      = printerStatus.toString().toLowerCase()
                def isPrint = ps in ["printing", "busy"]
                def isIdle  = ps in ["idle", "ready", "operational", "standby", "complete"]
                def isPause = ps in ["paused", "pausing"]
                def isError = ps in ["error", "offline", "disconnected", "cancelled"]
                def color   = isPrint ? "#1565c0" : isIdle ? "#c0c4cc" :
                              isPause ? "#e65100" : isError ? "#c62828" : "#c0c4cc"
                return [label: printerStatus.toString().capitalize(), color: color, isAlert: isError, type: "printerStatus"]
            }
        }

        def water = device.currentValue("water")
        if (water != null) {
            def isWet = water.toString().toLowerCase() == "wet"
            return [label: isWet ? "Wet" : "Dry", color: isWet ? "#c62828" : "#c0c4cc", isAlert: isWet, type: "water"]
        }

        def smoke = device.currentValue("smoke")
        if (smoke != null) {
            def isDetected = smoke.toString().toLowerCase() == "detected"
            return [label: isDetected ? "Smoke!" : "Clear", color: isDetected ? "#c62828" : "#c0c4cc", isAlert: isDetected, type: "smoke"]
        }

        def co = device.currentValue("carbonMonoxide")
        if (co != null) {
            def isDetected = co.toString().toLowerCase() == "detected"
            return [label: isDetected ? "CO!" : "Clear", color: isDetected ? "#c62828" : "#c0c4cc", isAlert: isDetected, type: "carbonMonoxide"]
        }

        def sw = device.currentValue("switch")
        if (sw != null) {
            def isOn = sw.toString().toLowerCase() == "on"
            return [label: isOn ? "ON" : "OFF", color: isOn ? "#1565c0" : "#c0c4cc", isAlert: false, type: "switch"]
        }

        def presence = device.currentValue("presence")
        if (presence != null) {
            def isPresent = presence.toString().toLowerCase() == "present"
            return [label: isPresent ? "Present" : "Not Present", color: isPresent ? "#1565c0" : "#c0c4cc", isAlert: false, type: "presence"]
        }

        def contact = device.currentValue("contact")
        if (contact != null) {
            def isOpen = contact.toString().toLowerCase() == "open"
            return [label: isOpen ? "Open" : "Closed", color: isOpen ? "#e65100" : "#c0c4cc", isAlert: isOpen, type: "contact"]
        }

        def motion = device.currentValue("motion")
        if (motion != null) {
            def isActive = motion.toString().toLowerCase() == "active"
            return [label: isActive ? "Active" : "Inactive", color: isActive ? "#1565c0" : "#c0c4cc", isAlert: false, type: "motion"]
        }

        def lock = device.currentValue("lock")
        if (lock != null) {
            def isUnlocked = lock.toString().toLowerCase() == "unlocked"
            return [label: isUnlocked ? "Unlocked" : "Locked", color: isUnlocked ? "#e65100" : "#c0c4cc", isAlert: isUnlocked, type: "lock"]
        }

        def tamper = device.currentValue("tamper")
        if (tamper != null) {
            def isDetected = tamper.toString().toLowerCase() == "detected"
            return [label: isDetected ? "Tampered!" : "Clear", color: isDetected ? "#c62828" : "#c0c4cc", isAlert: isDetected, type: "tamper"]
        }

        def shock = device.currentValue("shock")
        if (shock != null) {
            def isDetected = shock.toString().toLowerCase() == "detected"
            return [label: isDetected ? "Detected" : "Clear", color: isDetected ? "#e65100" : "#c0c4cc", isAlert: isDetected, type: "shock"]
        }

        def sleeping = device.currentValue("sleeping")
        if (sleeping != null) {
            def isSleeping = sleeping.toString().toLowerCase() == "sleeping"
            return [label: isSleeping ? "Sleeping" : "Not Sleeping", color: isSleeping ? "#8b5cf6" : "#c0c4cc", isAlert: false, type: "sleeping"]
        }

        def valve = device.currentValue("valve")
        if (valve != null) {
            def isOpen = valve.toString().toLowerCase() == "open"
            return [label: isOpen ? "Open" : "Closed", color: isOpen ? "#e65100" : "#c0c4cc", isAlert: isOpen, type: "valve"]
        }

        def door = device.currentValue("door")
        if (door != null) {
            def isOpen = door.toString().toLowerCase() in ["open", "opening"]
            return [label: door.toString().capitalize(), color: isOpen ? "#e65100" : "#c0c4cc", isAlert: isOpen, type: "door"]
        }

        def shade = device.currentValue("windowShade")
        if (shade != null) {
            def isOpen = shade.toString().toLowerCase() in ["open", "opening", "partially open"]
            return [label: shade.toString().capitalize(), color: isOpen ? "#1565c0" : "#c0c4cc", isAlert: false, type: "windowShade"]
        }

        def protocol = getProtocol(device)
        def isLANType = protocol in ["LAN", "Hub Mesh", "Hub Mesh (Zigbee)", "Hub Mesh (Z-Wave)", "Hub Mesh (Matter)", "Unknown"]
        if (isLANType) {
            def lanResult = getLANStateDisplay(device)
            if (lanResult != null) return lanResult
        }

        def temp = device.currentValue("temperature")
        if (temp != null) {
            def unit = location?.temperatureScale ?: "F"
            return [label: "${temp}°${unit}", color: "#c0c4cc", isAlert: false, type: "temperature"]
        }
        def humidity = device.currentValue("humidity")
        if (humidity != null) {
            return [label: "${humidity}% RH", color: "#c0c4cc", isAlert: false, type: "humidity"]
        }
        def co2 = device.currentValue("carbonDioxide")
        if (co2 != null) {
            return [label: "${co2} ppm CO₂", color: "#c0c4cc", isAlert: false, type: "carbonDioxide"]
        }

    } catch (e) {
        if (debugEnabled()) log.debug "getCurrentStateDisplay error for ${device.displayName}: ${e.message}"
    }
    return null
}

def getLANStateDisplay(device) {
    try {
        def currentStates = device.currentStates
        if (!currentStates) return null
        def stateMap = [:]
        currentStates.each { s ->
            if (s?.name && s?.value != null) stateMap[s.name] = s.value.toString()
        }
        if (!stateMap) return null

        def driverName = (device.typeName ?: "").toLowerCase()

        def isThermostat = driverName.contains("thermostat") || driverName.contains("ecobee") ||
                           driverName.contains("nest") || driverName.contains("honeywell") ||
                           driverName.contains("sinope") ||
                           stateMap.containsKey("thermostatMode") ||
                           stateMap.containsKey("thermostatOperatingState")
        if (isThermostat) {
            if (debugEnabled()) log.debug "getLANStateDisplay: ${device.displayName} matched as thermostat"
            def opState = stateMap["thermostatOperatingState"]
            def mode    = stateMap["thermostatMode"]
            def temp    = stateMap["temperature"]
            def setpt   = stateMap["thermostatSetpoint"] ?: stateMap["coolingSetpoint"] ?: stateMap["heatingSetpoint"]
            if (opState) {
                def isActive = opState.toLowerCase() in ["heating", "cooling", "fan only", "pending heat", "pending cool"]
                def color    = opState.toLowerCase() == "heating" ? "#e65100" :
                               opState.toLowerCase() == "cooling" ? "#1565c0" :
                               isActive ? "#1565c0" : "#c0c4cc"
                def label    = opState.capitalize()
                if (temp)  label += " ${temp}°"
                if (setpt) label += " → ${setpt}°"
                return [label: label, color: color, isAlert: false, type: "thermostat"]
            }
            if (mode) {
                def isOff = mode.toLowerCase() == "off"
                return [label: "Mode: ${mode.capitalize()}", color: isOff ? "#c0c4cc" : "#1565c0", isAlert: false, type: "thermostat"]
            }
        }

        def isMedia = driverName.contains("sonos") || driverName.contains("denon") ||
                      driverName.contains("yamaha") || driverName.contains("roku") ||
                      driverName.contains("apple tv") || driverName.contains("media player") ||
                      stateMap.containsKey("trackDescription") ||
                      stateMap.containsKey("mediaPlaybackStatus") ||
                      stateMap.containsKey("transportStatus")
        if (isMedia) {
            if (debugEnabled()) log.debug "getLANStateDisplay: ${device.displayName} matched as media"
            def playback = stateMap["mediaPlaybackStatus"] ?: stateMap["transportStatus"] ?: stateMap["status"]
            if (playback) {
                def isPlaying = playback.toLowerCase() in ["playing", "play"]
                def color     = isPlaying ? "#1565c0" : "#c0c4cc"
                def track     = stateMap["trackDescription"] ?: stateMap["trackData"] ?: ""
                def label     = playback.capitalize()
                if (track && track.length() > 0 && track != "null") {
                    label += ": " + (track.length() > 20 ? track[0..19] + "…" : track)
                }
                return [label: label, color: color, isAlert: false, type: "media"]
            }
        }

        def isEV = driverName.contains("tesla") || driverName.contains("electric vehicle") ||
                   stateMap.containsKey("chargingState") || stateMap.containsKey("batteryLevel")
        if (isEV) {
            if (debugEnabled()) log.debug "getLANStateDisplay: ${device.displayName} matched as EV"
            def charging = stateMap["chargingState"]
            def battery  = stateMap["battery"] ?: stateMap["batteryLevel"]
            if (charging) {
                def isCharging = charging.toLowerCase() in ["charging", "complete"]
                def color      = charging.toLowerCase() == "complete" ? "#16a34a" :
                                 isCharging ? "#1565c0" : "#c0c4cc"
                def label      = charging.capitalize()
                if (battery) label += " ${battery}%"
                return [label: label, color: color, isAlert: false, type: "ev"]
            }
            if (battery) {
                def pct = battery.isNumber() ? battery.toInteger() : 0
                def color = pct < 20 ? "#c62828" : pct < 40 ? "#e65100" : "#c0c4cc"
                return [label: "Battery ${battery}%", color: color, isAlert: pct < 20, type: "ev"]
            }
        }

        def isShelly = driverName.contains("shelly") ||
                       stateMap.containsKey("power") || stateMap.containsKey("energy")
        if (isShelly && stateMap.containsKey("switch")) {
            def sw    = stateMap["switch"]
            def isOn  = sw?.toLowerCase() == "on"
            def power = stateMap["power"]
            def label = isOn ? "ON" : "OFF"
            if (isOn && power && power.isNumber()) label += " ${power.toDouble().round(1)}W"
            return [label: label, color: isOn ? "#1565c0" : "#c0c4cc", isAlert: false, type: "shelly"]
        }

        if (stateMap.containsKey("door")) {
            def d = stateMap["door"].toLowerCase()
            def isOpen = d in ["open", "opening"]
            return [label: stateMap["door"].capitalize(), color: isOpen ? "#e65100" : "#c0c4cc", isAlert: isOpen, type: "door"]
        }

        if (debugEnabled()) log.debug "getLANStateDisplay: ${device.displayName} — available attrs: ${stateMap.keySet().sort().join(', ')}"
        def rankedAttrs = [
            "status", "deviceStatus", "healthStatus", "connectionStatus",
            "systemStatus", "operatingState", "currentState",
            "active", "enabled", "connected", "running",
            "mode", "level", "speed", "inputSource"
        ]
        for (attr in rankedAttrs) {
            def val = stateMap[attr]
            if (val == null) continue
            def vl = val.toLowerCase().trim()
            if (vl in ["unknown", "null", "", "none", "0", "false", "true"]) continue
            if (val.isNumber()) continue
            if (val.length() > 30) continue
            if (val.contains(": ")) continue
            def isActive = vl in ["on", "active", "connected", "online", "running",
                                   "enabled", "open", "playing", "present", "idle", "ready", "operational"]
            def isAlert  = vl in ["offline", "disconnected", "error", "fault", "alarm", "wet", "detected"]
            def color    = isAlert ? "#c62828" : isActive ? "#1565c0" : "#c0c4cc"
            if (debugEnabled()) log.debug "getLANStateDisplay: ${device.displayName} matched attr '${attr}' = '${val}'"
            return [label: val.capitalize(), color: color, isAlert: isAlert, type: attr]
        }
        if (debugEnabled()) log.debug "getLANStateDisplay: ${device.displayName} — no useful attribute found"

    } catch (e) {
        if (debugEnabled()) log.debug "getLANStateDisplay error for ${device.displayName}: ${e.message}"
    }
    return null
}

def updateStateTracking(device) {
    try {
        def id         = device.id
        def stateInfo  = getCurrentStateDisplay(device)
        if (!stateInfo) return

        def currentVal = stateInfo.label
        def sh         = state.stateHistory ?: [:]
        def tracked    = sh[id]

        if (!tracked) {
            sh[id] = [lastValue: currentVal, lastChanged: now()]
            state.stateHistory = sh
            return
        }

        if (tracked.lastValue != currentVal) {
            sh[id] = [lastValue: currentVal, lastChanged: now()]
            state.stateHistory = sh
            if (debugEnabled()) log.debug "${device.displayName}: state changed to ${currentVal}"
        }
    } catch (e) {
        if (debugEnabled()) log.debug "updateStateTracking error for ${device.displayName}: ${e.message}"
    }
}

// ============================================================
// ===================== HUB MESH GROUPING ===================
// ============================================================
def getHubMeshSourceHub(device) {
    try {
        def hubName = device.getDataValue("hubName") ?: device.getDataValue("HubName")
        if (hubName) return hubName
        def dni = device.deviceNetworkId ?: ""
        if (dni.contains(":")) {
            def prefix = dni.split(":")[0]
            if (prefix && prefix.length() > 2 && !prefix.matches("[0-9A-Fa-f]+")) return prefix
        }
    } catch (e) { }
    return "Remote Hub"
}

def buildHubMeshSummary() {
    def devList  = getAllMonitoredDevices().findAll { p -> getProtocol(p).startsWith("Hub Mesh") }
    def groups = [:]
    devList.each { device ->
        def srcHub = getHubMeshSourceHub(device)
        if (!groups[srcHub]) groups[srcHub] = [total: 0, offline: 0, poor: 0, fair: 0, good: 0, excellent: 0, pending: 0]
        groups[srcHub].total++
        def h = state.health?.get(device.id) ?: "Pending"
        switch (h) {
            case "Offline":   groups[srcHub].offline++;   break
            case "Poor":      groups[srcHub].poor++;      break
            case "Fair":      groups[srcHub].fair++;      break
            case "Good":      groups[srcHub].good++;      break
            case "Excellent": groups[srcHub].excellent++; break
            default:          groups[srcHub].pending++;   break
        }
    }
    return groups
}

// ============================================================
// ===================== MAIN PAGE ===========================
// ============================================================
def mainPage() {
    applyCustomLabel()
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {

        def currentLabel  = app.label ?: "Device Health Monitor"
        def appNameTitle  = "<b>App Display Name</b> — <span style='color:blue;'>${currentLabel}</span>"
        section(appNameTitle, hideable: true, hidden: true) {
            paragraph "Enter a name to rename this app in your Hubitat app list."
            input "customAppName", "text", title: "Custom App Name", required: false
        }

        def devicesSelected = (monitoredDevices?.size() ?: 0) > 0
        def devSectionTitle = devicesSelected
            ? "<b>Monitored Devices</b> — <span style='color:blue;'>${monitoredDevices.size()} selected</span>"
            : "<b>Monitored Devices</b>"

        section(devSectionTitle, hideable: true, hidden: devicesSelected) {
            paragraph "<b>Select the devices you want to monitor.</b> Protocol is detected automatically."
            paragraph "<span style='color:red; font-weight:bold;'>IMPORTANT: After selecting devices, you MUST click 'Done' before viewing reports.</span>"
            input "monitoredDevices", "capability.*",
                  title: "Select devices to monitor",
                  multiple: true, required: false, submitOnChange: true
        }

        if (devicesSelected) {
            def allSelected       = monitoredDevices
            def zigbeeCount       = allSelected.count { getProtocol(it) in ["Zigbee", "Hub Mesh (Zigbee)"] }
            def zwaveCount        = allSelected.count { getProtocol(it) in ["Z-Wave", "Hub Mesh (Z-Wave)"] }
            def matterCount       = allSelected.count { getProtocol(it) in ["Matter", "Hub Mesh (Matter)"] }
            def hubMeshCount      = allSelected.count { getProtocol(it) == "Hub Mesh" }
            def lanCount          = allSelected.count { getProtocol(it) == "LAN" }
            def virtualCount      = allSelected.count { getProtocol(it) == "Virtual" }
            def hubVarCount       = allSelected.count { getProtocol(it) == "Hub Variable" }
            def unknownCount      = allSelected.count { getProtocol(it) == "Unknown" }
            def unresolvableCount = allSelected.count { isUnresolvableProtocol(getRawProtocol(it)) }
            section("") {
                paragraph "Zigbee: <b><span style='color:#3b82f6;'>${zigbeeCount}</span></b> | " +
                          "Z-Wave: <b><span style='color:#8b5cf6;'>${zwaveCount}</span></b> | " +
                          "Matter: <b><span style='color:#e65100;'>${matterCount}</span></b> | " +
                          "Hub Mesh: <b><span style='color:#06b6d4;'>${hubMeshCount}</span></b> | " +
                          "LAN: <b><span style='color:#14b8a6;'>${lanCount}</span></b> | " +
                          "Virtual: <b><span style='color:#ec4899;'>${virtualCount}</span></b> | " +
                          "Hub Variable: <b><span style='color:#eab308;'>${hubVarCount}</span></b>" +
                          (unknownCount > 0 ? " | <span style='color:orange;'>Unknown: <b>${unknownCount}</b> (skipped)</span>" : "") +
                          (unresolvableCount > 0 ? "<br><span style='color:#94a3b8;'>⚠ ${unresolvableCount} device(s) showing as Hub Mesh, LAN, Virtual, or Hub Variable — tap <b>Protocol Overrides</b> to review or correct.</span>" : "") +
                          (allSelected.any { isHueDevice(it) } && !findHueBridge() ? "<br><span style='color:#1a73e8;'>ℹ️ Hue devices detected — add your <b>Hue Bridge</b> to monitored devices to enable Poor/Offline verification.</span>" : "")
            }
        }

        if (!devicesSelected) {
            section("") {
                paragraph "<span style='color:red; font-weight:bold;'>⚠ No devices selected. Select devices above to begin monitoring.</span>"
            }
        }

        def scanIntervalLabel = ["0.5": "Every 30 min", "1": "Hourly", "3": "Every 3 h", "6": "Every 6 h"]
        def currentScan      = scanIntervalLabel[settings?.scanInterval ?: "3"] ?: "Every 3 h"
        def currentThreshold = settings?.offlineThresholdHours ?: 72
        def snoozeOn         = snoozeEnabled()
        def currentSnooze    = settings?.snoozeDurationHours ?: 24
        def modeOn           = settings?.enableModeRestriction == true
        def modeLabel        = modeOn ? (settings?.restrictedModes ? settings.restrictedModes.join(", ") : "none set") : "off"
        def snoozedDeviceCount = state.snoozed?.count { id, until -> until >= now() } ?: 0
        def snoozeLabel = !snoozeOn ? "<span style='color:red;'>off</span>" :
                          snoozedDeviceCount > 0 ? "<span style='color:orange;'>${snoozedDeviceCount} snoozed</span>" :
                          "<span style='color:blue;'>${currentSnooze}h</span>"
        def monitoringTitle = "<b>Monitoring Settings</b> — " +
            "Scan: <span style='color:blue;'>${currentScan}</span> | " +
            "Offline after: <span style='color:blue;'>${currentThreshold}h</span> | " +
            "Snooze: ${snoozeLabel} | " +
            "Mode: <span style='color:${modeOn ? "blue" : "red"};'>${modeOn ? modeLabel : "off"}</span>"

        section(monitoringTitle, hideable: true, hidden: true) {
            paragraph "<b>Scan Interval</b> — how often device activity is checked and health ratings are updated."
            input "scanInterval", "enum",
                  title: "Scan Frequency:",
                  options: ["0.5": "Every 30 Minutes", "1": "Hourly", "3": "Every 3 Hours", "6": "Every 6 Hours"],
                  defaultValue: "3", submitOnChange: true

            paragraph "<b>Offline after inactivity (hours)</b> — devices with no activity beyond this threshold are marked Offline."
            input "offlineThresholdHours", "number", title: "Offline after inactivity (hours):",
                  defaultValue: 72, required: true, submitOnChange: true

            paragraph "<b>Snooze</b> — enable or disable snooze globally."
            input "enableSnooze", "bool", title: "Enable snooze", defaultValue: false, submitOnChange: true
            if (snoozeEnabled()) {
                input "snoozeDurationHours", "number", title: "Snooze duration (hours):",
                      defaultValue: 24, required: true, submitOnChange: true
            }

            paragraph "<b>Mode Restriction</b> — optionally restrict notifications to specific hub modes."
            input "enableModeRestriction", "bool", title: "Enable mode restriction for notifications",
                  defaultValue: false, submitOnChange: true
            if (settings?.enableModeRestriction) {
                input "restrictedModes", "mode",
                      title: "Only send notifications when hub is in one of these modes:",
                      multiple: true, required: false
            }
        }

        // FIX 3: hidden: false so the section never collapses on re-render when
        // enablePush is toggled — same pattern as Battery Monitor 2.0.
        // submitOnChange kept on enablePush so sub-inputs show/hide correctly.
        def notifOn           = settings?.enablePush != false
        def notifSectionTitle = "<b>Notifications</b> — <span style='color:${notifOn ? "blue" : "red"};'>${notifOn ? "ON" : "OFF"}</span>"
        section(notifSectionTitle, hideable: true, hidden: false) {
            paragraph "ℹ️ Enable the toggle below to reveal notification settings including frequency, timing, device targets, and which health groups to include in reports."
            input "enablePush", "bool", title: "Enable notifications", defaultValue: false, submitOnChange: true
            if (settings?.enablePush != false) {
                input "reportFrequency", "enum",
                      title: "Notification Frequency:",
                      options: ["daily": "Daily", "every2": "Every 2 Days", "every3": "Every 3 Days", "weekly": "Weekly"],
                      defaultValue: "daily"
                input "summaryTime", "time", title: "Notification Time:", required: false
                input "notifyDevices", "capability.notification",
                      title: "Notification devices", multiple: true, required: false, submitOnChange: true
                input "enablePushover", "bool", title: "⚙️ Enable Pushover Markup", defaultValue: false
                input "pushoverDevices", "capability.notification",
                      title: "Pushover notification devices", multiple: true, required: false, submitOnChange: true
                input "pushoverPrefix", "text",
                      title: "Pushover tags",
                      description: "e.g. [H][TITLE=Device Health Report][HTML][SELFDESTRUCT=43200]",
                      required: false

                paragraph "<b>Report Sections:</b>"
                input "notifyOffline",      "bool", title: "💀 Include Offline devices",              defaultValue: true
                input "notifyPoor",         "bool", title: "🔴 Include Poor health devices",           defaultValue: true
                input "notifyFair",         "bool", title: "🟠 Include Fair health devices",           defaultValue: true
                input "notifyGood",         "bool", title: "🟢 Include Good health devices",           defaultValue: false
                input "notifyExcellent",    "bool", title: "🟢 Include Excellent health devices",      defaultValue: false
                input "suppressEmptyReport","bool", title: "🔕 Don't send notification if nothing to report", defaultValue: false

                paragraph "<b>Send notification now:</b>"
                href(name: "toSendNotification", page: "sendNotificationPage", title: "📤 Send Notification Now")
            }
        }

        section("<b>Reports:</b>") {
            href(name: "toActivitySummary", page: "activitySummaryPage",
                 title: "<b>Device Activity Summary</b>",
                 description: "All devices, health status, current state")
            href(name: "toProblemDevices", page: "problemDevicesPage",
                 title: "<b>⚠️ Problem Devices</b>",
                 description: "Offline, Poor, and Fair devices")
            if (getAllMonitoredDevices().any { getProtocol(it).startsWith("Hub Mesh") }) {
                href(name: "toHubMeshSummary", page: "hubMeshSummaryPage",
                     title: "<b>🔗 Hub Mesh Overview</b>",
                     description: "Health summary grouped by source hub")
            }
            if (snoozeEnabled()) {
                href(name: "toSnoozeManage", page: "snoozeManagePage",
                     title: "<b>😴 Manage Snoozed Devices</b>",
                     description: "Snooze or clear active snoozes")
            }
            href(name: "toProtocolOverride", page: "protocolOverridePage",
                 title: "<b>🔧 Protocol & State Overrides</b>",
                 description: "Fix misdetected protocols or pin a specific state attribute per device")
        }

        section("<b>Help & Support</b>") {
            href(name: "toInfoPage", page: "infoPage",
                 title: "📖 App Guide & Reference",
                 description: "Health scoring, state tracking, and troubleshooting explained")
            href url: "https://community.hubitat.com/t/release-device-health-monitor/163229",
                 style: "external",
                 title: "💬 Hubitat Community Thread",
                 description: "Questions, feedback, and release notes"
        }

        section("<b>Diagnostics</b>") {
            input "debugMode", "bool",
                  title: "Debug Logging (auto-disables after 30 min)",
                  defaultValue: false, submitOnChange: true
            paragraph "<span style='color:#94a3b8; font-size:11px;'>Device Health Monitor v1.4.6</span>"
        }
    }
}

// ============================================================
// ===================== REPORT SCHEDULING ==================
// ============================================================
def scheduleReportFrequency() {
    unschedule("reportScheduler")
    if (!summaryTime) return
    schedule(summaryTime, reportScheduler)
}

def scheduleScanInterval() {
    unschedule("scanAllDevices")
    def intervalStr = settings?.scanInterval ?: "3"
    def cronExpr = ""
    switch (intervalStr) {
        case "0.5": cronExpr = "0 */30 * * * ?"; break
        case "1":   cronExpr = "0 0 * * * ?";    break
        case "3":   cronExpr = "0 0 */3 * * ?";  break
        case "6":   cronExpr = "0 0 */6 * * ?";  break
        default:    cronExpr = "0 0 */3 * * ?";  break
    }
    schedule(cronExpr, scanAllDevices)
}

def reportScheduler() {
    switch (reportFrequency) {
        case "daily":  scheduledSummary(); break
        case "every2": if (shouldRunEveryXDays(2)) scheduledSummary(); break
        case "every3": if (shouldRunEveryXDays(3)) scheduledSummary(); break
        case "weekly": if (shouldRunWeekly())       scheduledSummary(); break
    }
}

def shouldRunEveryXDays(daysInterval) {
    def today   = new Date().clearTime()
    def lastRun = state.lastReportRun ? new Date(state.lastReportRun).clearTime() : null
    if (!lastRun) { state.lastReportRun = now(); return true }
    def diff = (today.time - lastRun.time) / (1000 * 60 * 60 * 24)
    if (diff >= daysInterval) { state.lastReportRun = now(); return true }
    return false
}

def shouldRunWeekly() {
    def today   = new Date()
    def lastRun = state.lastReportRun ? new Date(state.lastReportRun) : null
    if (!lastRun) { state.lastReportRun = now(); return true }
    if (today.format("u") == "1") {
        def diff = (today.time - lastRun.time) / (1000 * 60 * 60 * 24)
        if (diff >= 7) { state.lastReportRun = now(); return true }
    }
    return false
}

// ============================================================
// ===================== SCAN ================================
// ============================================================
def scanAllDevices() {
    def devList = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }
    if (!devList) return
    if (debugEnabled()) log.debug "Running scan for ${devList.size()} device(s)"

    def activeIds = devList.collect { it.id as String } as Set

    ["history", "health", "verifying", "stateHistory"].each { stateKey ->
        def map = state[stateKey]
        if (map instanceof Map) {
            def stale = map.keySet().findAll { !(it in activeIds) }
            if (stale) {
                stale.each { map.remove(it) }
                state[stateKey] = map
                if (debugEnabled()) log.debug "Purged ${stale.size()} orphaned ${stateKey} entr${stale.size() == 1 ? 'y' : 'ies'}: ${stale.join(', ')}"
            }
        }
    }

    if (state.snoozed instanceof Map) {
        def snoozedCopy  = state.snoozed
        def staleSnoozed = snoozedCopy.keySet().findAll { !(it in activeIds) }
        if (staleSnoozed) {
            staleSnoozed.each { snoozedCopy.remove(it) }
            state.snoozed = snoozedCopy
            if (debugEnabled()) log.debug "Purged ${staleSnoozed.size()} orphaned snooze entr${staleSnoozed.size() == 1 ? 'y' : 'ies'}"
        }
    }

    def intervalStr     = settings?.scanInterval ?: "3"
    def intervalMinutes = (intervalStr.toFloat() * 60).toInteger()
    def minGate         = Math.min(intervalMinutes * 0.5, 30.0)

    devList.each { device ->
        try {
            def id       = device.id
            def data     = state.history[id]
            def protocol = getProtocol(device)
            def filtered = usesFilteredSampling(protocol)

            def lastActivity = device.getLastActivity()
            def lastSeen     = lastActivity ? safeTime(lastActivity) : now()

            if (!data) {
                state.history[id] = [
                    lastSeen:     lastSeen,
                    samples:      [],
                    avgInterval:  null,
                    userInterval: null,
                    protocol:     protocol
                ]
                state.health[id] = "Pending"
            } else {
                def prevLastSeen = data.lastSeen ?: lastSeen
                if (lastSeen > prevLastSeen) {
                    def elapsed = (lastSeen - prevLastSeen) / (1000 * 60)
                    if (elapsed >= minGate) {
                        def recordSample = true
                        if (filtered) {
                            recordSample = elapsed <= (intervalMinutes * 1.5)
                        }
                        if (recordSample) {
                            def alpha      = 0.3
                            def prevSmooth = (data.samples && data.samples.size() > 0) ? data.samples[-1] : elapsed
                            def smoothed   = alpha * elapsed + (1 - alpha) * prevSmooth
                            data.samples << smoothed
                            if (data.samples.size() > 20) data.samples.remove(0)
                            if (data.samples.size() >= 3) {
                                data.avgInterval = data.samples.sum() / data.samples.size()
                            }
                        }
                        data.lastSeen = lastSeen
                    }
                }
                data.protocol    = protocol
                state.history[id] = data
                updateHealth(device)
            }
            updateStateTracking(device)

        } catch (e) {
            log.warn "Scan failed for ${device.displayName}: ${e.message}"
        }
    }
}

// ============================================================
// ===================== HEALTH SCORING ======================
// ============================================================
def updateHealth(device) {
    def id   = device.id
    def data = state.history[id]
    if (!data) return

    def samples = data.samples?.size() ?: 0
    if (samples < 3) {
        state.health[id] = "Pending"
        state.verifying?.remove(id)
        return
    }

    def offlineThreshold     = ((settings?.offlineThresholdHours ?: 72) * 60).toDouble()
    def minutesSinceLastSeen = (now() - (data.lastSeen ?: now())) / (1000 * 60)

    if (minutesSinceLastSeen >= offlineThreshold) {
        state.health[id] = "Offline"
    } else {
        def baseline = (data.userInterval ?: data.avgInterval ?: 60).toDouble()
        def ratio    = minutesSinceLastSeen / baseline
        if      (ratio <= 1.2) state.health[id] = "Excellent"
        else if (ratio <= 2.0) state.health[id] = "Good"
        else if (ratio <= 3.0) state.health[id] = "Fair"
        else                   state.health[id] = "Poor"
    }

    def currentHealth = state.health[id]
    if (!(currentHealth in ["Poor", "Offline"])) {
        state.verifying?.remove(id)
        return
    }

    if (state.verifying == null) state.verifying = [:]
    if (state.verifying[id]) {
        state.verifying.remove(id)
        return
    }

    def protocol    = getProtocol(device)
    def isVirtual   = protocol in ["Virtual", "Hub Variable"]
    def hasRefresh  = false
    def hasPing     = false
    def attempted   = false
    def verifyMethod = ""

    if (isVirtual) {
        verifyMethod = "virtual"
    } else if (isHueDevice(device)) {
        def bridge = findHueBridge()
        if (bridge) {
            try { bridge.refresh(); attempted = true; verifyMethod = "hue_bridge" }
            catch (e) { verifyMethod = "hue_bridge_failed" }
        } else {
            verifyMethod = "hue_no_bridge"
        }
    } else {
        if (!isVirtual) {
            try { hasRefresh = device.hasCapability("Refresh") } catch (e) { }
            try { hasPing    = device.hasCapability("Ping")    } catch (e) { }
        }
        if (hasRefresh) {
            try { device.refresh(); attempted = true; verifyMethod = "refresh" }
            catch (e) { verifyMethod = "failed" }
        } else if (hasPing) {
            try { device.ping(); attempted = true; verifyMethod = "ping" }
            catch (e) { verifyMethod = "failed" }
        } else {
            verifyMethod = "none"
        }
    }
    state.verifying[id] = verifyMethod
}

// ============================================================
// ===================== HEALTH DISPLAY ======================
// ============================================================
def getHealthDisplay(device) {
    def h       = state.health?.get(device.id) ?: "Pending"
    def samples = state.history?.get(device.id)?.samples?.size() ?: 0
    def snoozed = isDeviceSnoozed(device.id as String)

    if (snoozed) {
        def remaining = formatSnoozeRemaining(device.id as String)
        return "😴 <span style='color:#94a3b8;'>Snoozed (${remaining})</span>"
    }
    if (h == "Pending") {
        return "<span style='color:#94a3b8;'>⏳ Pending (${samples}/3 samples)</span>"
    }
    if (h in ["Poor", "Offline"]) {
        def baseDisplay = h == "Poor"
            ? "🔴 Poor"
            : "💀 <span style='color:#991b1b;font-weight:bold;'>Offline</span>"

        def lowActivity = isLowActivity(device.id as String)
        def lowSuffix   = lowActivity ? " <span style='color:#94a3b8;font-size:10px;'>ℹ️ Low Activity Device</span>" : ""

        def verifyMethod = state.verifying?.get(device.id)
        if (verifyMethod == null) return "${baseDisplay}${lowSuffix}"
        switch (verifyMethod) {
            case "refresh":           return "${baseDisplay}${lowSuffix} <span style='color:#1a73e8;font-size:11px;'>🔄 Verifying... (refresh sent)</span>"
            case "ping":              return "${baseDisplay}${lowSuffix} <span style='color:#1a73e8;font-size:11px;'>🔄 Verifying... (ping sent)</span>"
            case "hue_bridge":        return "${baseDisplay}${lowSuffix} <span style='color:#1a73e8;font-size:11px;'>🔄 Verifying... (Hue Bridge refresh sent)</span>"
            case "hue_no_bridge":     return "${baseDisplay}${lowSuffix} <span style='color:#94a3b8;font-size:11px;'>⚠ Cannot verify — add Hue Bridge to monitored devices</span>"
            case "hue_bridge_failed": return "${baseDisplay}${lowSuffix} <span style='color:#94a3b8;font-size:11px;'>⚠ Hue Bridge refresh failed</span>"
            case "virtual":           return "${baseDisplay}${lowSuffix} <span style='color:#94a3b8;font-size:11px;'>⚠ Cannot verify — virtual device</span>"
            case "none":              return "${baseDisplay}${lowSuffix} <span style='color:#94a3b8;font-size:11px;'>⚠ Cannot verify — device does not support ping or refresh</span>"
            case "failed":            return "${baseDisplay}${lowSuffix} <span style='color:#94a3b8;font-size:11px;'>⚠ Verification attempted but command failed</span>"
            default:                  return "${baseDisplay}${lowSuffix}"
        }
    }
    switch (h) {
        case "Excellent": return "🟢 Excellent"
        case "Good":      return "🟢 Good"
        case "Fair":
            def lowActivity = isLowActivity(device.id as String)
            def lowSuffix   = lowActivity ? " <span style='color:#94a3b8;font-size:10px;'>ℹ️ Low Activity Device</span>" : ""
            return "🟠 Fair${lowSuffix}"
        default: return "${h}"
    }
}

def getHealthEmoji(h) {
    switch (h) {
        case "Excellent": return "🟢"
        case "Good":      return "🟢"
        case "Fair":      return "🟠"
        case "Poor":      return "🔴"
        case "Offline":   return "💀"
        default:          return "⏳"
    }
}

// ============================================================
// ===================== SAFE HELPERS ========================
// ============================================================
def safeTime(ts) { return (ts instanceof Number) ? ts : ts?.time }

def formatTimeAgo(ts) {
    if (!ts) return "N/A"
    ts = safeTime(ts)
    def diffMs = now() - ts
    def mins   = (diffMs / (1000 * 60)).toInteger()
    def hours  = (diffMs / (1000 * 60 * 60)).toInteger()
    def days   = (diffMs / (1000 * 60 * 60 * 24)).toInteger()
    def weeks  = (days / 7).toInteger()
    def months = (days / 30).toInteger()
    if (months >= 1) return "${months}mo ago"
    if (weeks  >= 1) return "${weeks}w ago"
    if (days   >= 1) return "${days}d ago"
    if (hours  >= 1) return "${hours}h ago"
    return "${mins}m ago"
}

def formatInterval(minutes) {
    if (!minutes) return "—"
    def m = minutes.toInteger()
    if (m < 60)   return "${m}m"
    if (m < 1440) return "${(m / 60).toInteger()}h ${m % 60}m"
    return "${(m / 1440).toInteger()}d ${((m % 1440) / 60).toInteger()}h"
}

def formatStateDisplay(stateInfo) {
    if (!stateInfo) return "—"
    def label = stateInfo.label
    def color = stateInfo.color
    switch (color) {
        case "#c62828":
            return "<span style='background:#fee2e2; color:#b91c1c; padding:3px 10px; border-radius:10px; font-weight:700; font-size:13px; display:inline-block;'>${label}</span>"
        case "#e65100":
            return "<span style='background:#fff3e0; color:#c2410c; padding:3px 10px; border-radius:10px; font-weight:700; font-size:13px; display:inline-block;'>${label}</span>"
        case "#1565c0":
            return "<span style='background:#dbeafe; color:#1d4ed8; padding:3px 10px; border-radius:10px; font-weight:700; font-size:13px; display:inline-block;'>${label}</span>"
        case "#8b5cf6":
            return "<span style='background:#f3e8ff; color:#7c3aed; padding:3px 10px; border-radius:10px; font-weight:700; font-size:13px; display:inline-block;'>${label}</span>"
        case "#16a34a":
            return "<span style='background:#dcfce7; color:#15803d; padding:3px 10px; border-radius:10px; font-weight:700; font-size:13px; display:inline-block;'>${label}</span>"
        default:
            return "<span style='color:#4b5563;font-weight:600;font-size:13px;'>${label}</span>"
    }
}

def formatStateDisplayInput(stateInfo) {
    if (!stateInfo) return "—"
    def label = stateInfo.label
    def color = stateInfo.color
    switch (color) {
        case "#c62828": return "<b><span style='color:#b91c1c;font-size:13px;'>${label}</span></b>"
        case "#e65100": return "<b><span style='color:#c2410c;font-size:13px;'>${label}</span></b>"
        case "#1565c0": return "<b><span style='color:#1d4ed8;font-size:13px;'>${label}</span></b>"
        case "#8b5cf6": return "<b><span style='color:#7c3aed;font-size:13px;'>${label}</span></b>"
        case "#16a34a": return "<b><span style='color:#15803d;font-size:13px;'>${label}</span></b>"
        default:        return "<b><span style='color:#1f2937;font-size:13px;'>${label}</span></b>"
    }
}

def formatStateDisplayOverride(stateInfo) {
    if (!stateInfo) return "—"
    def label = stateInfo.label
    def color = stateInfo.color
    switch (color) {
        case "#c62828": return "<b><span style='color:#b91c1c;'>[${label}]</span></b>"
        case "#e65100": return "<b><span style='color:#c2410c;'>[${label}]</span></b>"
        case "#1565c0": return "<b><span style='color:#1d4ed8;'>[${label}]</span></b>"
        case "#8b5cf6": return "<b><span style='color:#7c3aed;'>[${label}]</span></b>"
        case "#16a34a": return "<b><span style='color:#15803d;'>[${label}]</span></b>"
        default:        return "<b><span style='color:#374151;'>[${label}]</span></b>"
    }
}

// ============================================================
// ===================== ACTIVITY SUMMARY PAGE ===============
// ============================================================
def activitySummaryPage() {
    dynamicPage(name: "activitySummaryPage", title: "Device Activity Summary", install: false) {
        section("") {
            paragraph rawHtml: true, """
<link rel="stylesheet" href="https://cdn.datatables.net/1.13.6/css/jquery.dataTables.min.css">
<script src="https://cdn.datatables.net/1.13.6/js/jquery.dataTables.min.js"></script>
"""
            href(name: "toForceScan", page: "forceScanPage", title: "🔄 Force Scan Now")
            if (snoozeEnabled()) {
                href(name: "toSnoozeFromSummary", page: "snoozeManagePage", title: "😴 Manage Snoozed Devices")
            }
            paragraph "<div style='background-color:#e8f0fe; border-left:3px solid #1565c0; padding:6px 10px; border-radius:0; font-size:12px; color:#1565c0;'>🔄 Data reflects the last completed scan — states do not update live. Tap <b>Force Scan</b> above to refresh.</div>"

            def devList = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }
            if (!devList) { paragraph "No devices found. Please select devices on the main page first."; return }

            devList = devList.sort { a, b ->
                def healthPriority = ["Offline": 1, "Poor": 2, "Fair": 3, "Good": 4, "Excellent": 5, "Pending": 6]
                def hA = state.health?.get(a.id) ?: "Pending"
                def hB = state.health?.get(b.id) ?: "Pending"
                def pA = healthPriority[hA] ?: 6
                def pB = healthPriority[hB] ?: 6
                if (pA != pB) return pA <=> pB
                return a.displayName.trim() <=> b.displayName.trim()
            }

            def hubIp = location?.hub?.localIP ?: ""

            def table = "<table id='activityTable' style='width:100%; border-collapse: collapse; border: 1px solid #ccc;'>"
            table += "<thead><tr style='font-weight:bold; background-color:#f0f0f0;'>"
            table += "<th style='padding:4px; border:1px solid #ccc; text-align:center;'>Device</th>"
            table += "<th style='padding:4px; border:1px solid #ccc; text-align:center;'>Protocol</th>"
            table += "<th style='padding:4px; border:1px solid #ccc; text-align:center;' title='Health rating based on check-in frequency. Shows sample progress while learning.'>Health</th>"
            table += "<th style='padding:4px; border:1px solid #ccc; text-align:center;' title='Current reported state of the device'>Current State</th>"
            table += "<th style='padding:4px; border:1px solid #ccc; text-align:center;' title='How long ago this state last changed. Updates on each scan cycle, not in real time.'>State Changed</th>"
            table += "<th style='padding:4px; border:1px solid #ccc; text-align:center;' title='How long ago the device last sent any activity to the hub'>Last Check-in</th>"
            table += "<th style='padding:4px; border:1px solid #ccc; text-align:center;'>Avg Check-in</th>"
            table += "</tr></thead><tbody>"

            def rowNum = 0
            devList.each { device ->
                def data        = state.history?.get(device.id)
                def protocol    = getProtocol(device)
                def snoozed     = isDeviceSnoozed(device.id as String)
                def hasOverride = settings["protocolOverride_${device.id}"] &&
                                  settings["protocolOverride_${device.id}"] != "Auto-detect"
                def rowBg       = snoozed ? "#f8f8f8" : (rowNum % 2 == 0) ? "#ffffff" : "#ebebeb"
                def protocolDisplay = hasOverride
                    ? "${protocol} <span style='color:#94a3b8;font-size:10px;'>(override)</span>"
                    : protocol

                def lastSeenMs  = data?.lastSeen ? (data.lastSeen as Long) : 0
                def lastSeenStr = lastSeenMs ? formatTimeAgo(lastSeenMs) : "Never"

                def avgRawMin = data?.userInterval ? (data.userInterval as Long) :
                                data?.avgInterval  ? (data.avgInterval as Long) : 999999
                def avgIntStr = data?.userInterval ? formatInterval(data.userInterval) + " (manual)" :
                                data?.avgInterval  ? formatInterval(data.avgInterval) : "Learning..."

                def h           = state.health?.get(device.id) ?: "Pending"
                def healthOrder = snoozed ? 99 :
                                  (h == "Offline" ? 1 : h == "Poor" ? 2 : h == "Fair" ? 3 :
                                   h == "Good" ? 4 : h == "Excellent" ? 5 : 6)
                def stateInfo    = getCurrentStateDisplay(device)
                def stateDisplay = "—"
                def stateOrderVal = "zzz"
                if (stateInfo) {
                    stateDisplay  = formatStateDisplay(stateInfo)
                    stateOrderVal = stateInfo.label.toLowerCase()
                }

                def tracked       = state.stateHistory?.get(device.id as String)
                def lastChangedMs = tracked?.lastChanged ? (tracked.lastChanged as Long) : 0
                def lastChangedStr = lastChangedMs ? formatTimeAgo(lastChangedMs) : "—"

                rowNum++

                def deviceLink = hubIp
                    ? "<a href='http://${hubIp}/device/edit/${device.id}' target='_blank'>${device.displayName}</a>"
                    : device.displayName

                table += "<tr style='background-color:${rowBg};${snoozed ? "opacity:0.6;" : ""}'>"
                table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${device.displayName.toLowerCase().trim()}'>${deviceLink}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${protocol}'><span style='color:${getProtocolColor(protocol)};font-weight:bold;'>${protocolDisplay}</span></td>"
                table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${healthOrder}'>${getHealthDisplay(device)}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc; text-align:center;' data-order='${stateOrderVal}'>${stateDisplay}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${-lastChangedMs}'>${lastChangedStr}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${-lastSeenMs}'>${lastSeenStr}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${avgRawMin}'>${avgIntStr}</td>"
                table += "</tr>"
            }

            table += "</tbody></table>"

            paragraph rawHtml: true, """
${hubIp ? "<div style='background-color:#fff8e1; border-left:3px solid #e65100; padding:6px 10px; border-radius:0; font-size:12px; color:#e65100; margin-bottom:6px;'>⚠ Device links are accessible on your local network only.</div>" : ""}
<div style='overflow-x:auto; -webkit-overflow-scrolling:touch;'>${table}</div>
<script>
\$(document).ready(function() {
    \$('#activityTable').DataTable({
        paging:     false,
        info:       false,
        searching:  true,
        order:      [[2, 'asc']],
        columnDefs: [
            { type: 'num',    targets: [2, 4, 5, 6] },
            { type: 'string', targets: [3] }
        ]
    });
});
</script>
"""
        }

        section("<b>🔄 Reset Device History</b>", hideable: true, hidden: true) {
            paragraph "Reset check-in history for specific devices."
            href(name: "toResetHistory", page: "resetHistoryPage", title: "🔄 Reset Device History")
        }
    }
}

// ============================================================
// ===================== HUB MESH SUMMARY PAGE ===============
// ============================================================
def hubMeshSummaryPage() {
    dynamicPage(name: "hubMeshSummaryPage", title: "🔗 Hub Mesh Overview", install: false) {
        section("") {
            def devList = getAllMonitoredDevices().findAll { p -> getProtocol(p).startsWith("Hub Mesh") }

            if (!devList) {
                paragraph "No Hub Mesh devices found in your monitored device list."
                return
            }

            def groups = buildHubMeshSummary()
            def hubIp  = location?.hub?.localIP ?: ""

            paragraph rawHtml: true, "<div style='background-color:#f8f0ff; border-left:3px solid #8b5cf6; padding:6px 10px; border-radius:0; font-size:12px; color:#6d28d9; margin-bottom:8px;'>ℹ️ Source hub detection is not supported on current Hubitat firmware (tested on C-8 Pro v2.5.0.126–128). All Hub Mesh devices show as \"Remote Hub\" — this does not affect health monitoring. Grouping will improve if Hubitat exposes hub name data in a future firmware release.</div>"

            def bannerHtml = ""
            groups.each { srcHub, counts ->
                def worstColor = counts.offline > 0 ? "#991b1b" :
                                 counts.poor    > 0 ? "#c62828" :
                                 counts.fair    > 0 ? "#ea580c" : "#16a34a"
                def worstLabel = counts.offline > 0 ? "💀 Offline devices present" :
                                 counts.poor    > 0 ? "🔴 Poor devices present" :
                                 counts.fair    > 0 ? "🟠 Fair devices present" : "🟢 All healthy"
                bannerHtml += "<div style='background:#f0f0f0; border-left:4px solid ${worstColor}; padding:8px 10px; margin-bottom:8px; border-radius:3px;'>"
                bannerHtml += "<b>${srcHub}</b> &nbsp;·&nbsp; ${counts.total} device(s) &nbsp;·&nbsp; <span style='color:${worstColor};'>${worstLabel}</span><br>"
                bannerHtml += "<small>"
                if (counts.offline   > 0) bannerHtml += "💀 Offline: ${counts.offline}&nbsp; "
                if (counts.poor      > 0) bannerHtml += "🔴 Poor: ${counts.poor}&nbsp; "
                if (counts.fair      > 0) bannerHtml += "🟠 Fair: ${counts.fair}&nbsp; "
                if (counts.good      > 0) bannerHtml += "🟢 Good: ${counts.good}&nbsp; "
                if (counts.excellent > 0) bannerHtml += "🟢 Excellent: ${counts.excellent}&nbsp; "
                if (counts.pending   > 0) bannerHtml += "⏳ Pending: ${counts.pending}&nbsp; "
                bannerHtml += "</small></div>"
            }

            paragraph rawHtml: true, bannerHtml

            def table = "<table style='width:100%; border-collapse: collapse; border: 1px solid #ccc;'>"
            table += "<tr style='font-weight:bold; background-color:#f0f0f0;'>"
            table += "<td style='padding:4px; border:1px solid #ccc; text-align:center;'>Device</td>"
            table += "<td style='padding:4px; border:1px solid #ccc; text-align:center;'>Source Hub</td>"
            table += "<td style='padding:4px; border:1px solid #ccc; text-align:center;'>Protocol</td>"
            table += "<td style='padding:4px; border:1px solid #ccc; text-align:center;'>Health</td>"
            table += "<td style='padding:4px; border:1px solid #ccc; text-align:center;' title='Current reported state'>State</td>"
            table += "<td style='padding:4px; border:1px solid #ccc; text-align:center;' title='How long ago the device last sent any activity to the hub'>Last Check-in</td>"
            table += "</tr>"

            def sorted = devList.sort { a, b ->
                def srcA = getHubMeshSourceHub(a)
                def srcB = getHubMeshSourceHub(b)
                if (srcA != srcB) return srcA <=> srcB
                def healthPriority = ["Offline": 1, "Poor": 2, "Fair": 3, "Good": 4, "Excellent": 5, "Pending": 6]
                def hA = state.health?.get(a.id) ?: "Pending"
                def hB = state.health?.get(b.id) ?: "Pending"
                return (healthPriority[hA] ?: 6) <=> (healthPriority[hB] ?: 6)
            }

            def rowNum = 0
            sorted.each { device ->
                def data     = state.history?.get(device.id)
                def protocol = getProtocol(device)
                def srcHub   = getHubMeshSourceHub(device)
                def lastSeen = data?.lastSeen ? formatTimeAgo(data.lastSeen) : "Never"
                def rowBg    = (rowNum % 2 == 0) ? "#ffffff" : "#ebebeb"

                def stateInfo    = getCurrentStateDisplay(device)
                def stateDisplay = "—"
                if (stateInfo) stateDisplay = formatStateDisplay(stateInfo)

                def deviceLink = hubIp
                    ? "<a href='http://${hubIp}/device/edit/${device.id}' target='_blank'>${device.displayName}</a>"
                    : device.displayName

                rowNum++
                table += "<tr style='background-color:${rowBg};'>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${deviceLink}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${srcHub}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'><span style='color:${getProtocolColor(protocol)};font-weight:bold;'>${protocol}</span></td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${getHealthDisplay(device)}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc; text-align:center;'>${stateDisplay}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${lastSeen}</td>"
                table += "</tr>"
            }

            table += "</table>"

            if (hubIp) {
                paragraph "<span style='color:#94a3b8;font-size:11px;'>⚠ Device links are accessible on your local network only.</span>"
            }
            paragraph "<div style='overflow-x:auto; -webkit-overflow-scrolling:touch;'>${table}</div>"
        }
    }
}

// ============================================================
// ===================== PROBLEM DEVICES PAGE ================
// ============================================================
def problemDevicesPage() {
    dynamicPage(name: "problemDevicesPage", title: "⚠️ Problem Devices", install: false) {
        section("") {
            def devList = getAllMonitoredDevices().findAll { device ->
                def h = state.health?.get(device.id) ?: "Pending"
                h in ["Offline", "Poor", "Fair"] && !isDeviceSnoozed(device.id as String)
            }

            if (!devList) {
                paragraph "✅ No problem devices found — all monitored devices are healthy."
                return
            }

            devList = devList.sort { a, b ->
                def healthPriority = ["Offline": 1, "Poor": 2, "Fair": 3]
                def hA = state.health?.get(a.id) ?: "Fair"
                def hB = state.health?.get(b.id) ?: "Fair"
                (healthPriority[hA] ?: 4) <=> (healthPriority[hB] ?: 4)
            }

            def hubIp = location?.hub?.localIP ?: ""

            def table = "<table style='width:100%; border-collapse: collapse; border: 1px solid #ccc;'>"
            table += "<tr style='font-weight:bold; background-color:#f0f0f0;'>"
            table += "<td style='padding:4px; border:1px solid #ccc; text-align:center;'>Device</td>"
            table += "<td style='padding:4px; border:1px solid #ccc; text-align:center;'>Protocol</td>"
            table += "<td style='padding:4px; border:1px solid #ccc; text-align:center;'>Health</td>"
            table += "<td style='padding:4px; border:1px solid #ccc; text-align:center;' title='Current reported state of the device'>State</td>"
            table += "<td style='padding:4px; border:1px solid #ccc; text-align:center;' title='How long ago this state last changed. Updates on each scan cycle, not in real time.'>State Changed</td>"
            table += "<td style='padding:4px; border:1px solid #ccc; text-align:center;' title='How long ago the device last sent any activity to the hub'>Last Check-in</td>"
            table += "<td style='padding:4px; border:1px solid #ccc; text-align:center;'>Avg Check-in</td>"
            table += "</tr>"

            def rowNum = 0
            devList.each { device ->
                def data      = state.history?.get(device.id)
                def protocol  = getProtocol(device)
                def lastSeen  = data?.lastSeen ? formatTimeAgo(data.lastSeen) : "Never"
                def avgInt    = data?.userInterval ? formatInterval(data.userInterval) + " (manual)" :
                                data?.avgInterval  ? formatInterval(data.avgInterval) : "Learning..."
                def rowBg     = (rowNum % 2 == 0) ? "#ffffff" : "#ebebeb"

                def stateInfo    = getCurrentStateDisplay(device)
                def stateDisplay = "—"
                if (stateInfo) stateDisplay = formatStateDisplay(stateInfo)

                def tracked        = state.stateHistory?.get(device.id as String)
                def lastChangedMs  = tracked?.lastChanged ? (tracked.lastChanged as Long) : 0
                def lastChangedStr = lastChangedMs ? formatTimeAgo(lastChangedMs) : "—"

                rowNum++
                def deviceLink = hubIp
                    ? "<a href='http://${hubIp}/device/edit/${device.id}' target='_blank'>${device.displayName}</a>"
                    : device.displayName

                table += "<tr style='background-color:${rowBg};'>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${deviceLink}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'><span style='color:${getProtocolColor(protocol)};font-weight:bold;'>${protocol}</span></td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${getHealthDisplay(device)}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc; text-align:center;'>${stateDisplay}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${lastChangedStr}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${lastSeen}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${avgInt}</td>"
                table += "</tr>"
            }

            table += "</table>"
            if (hubIp) {
                paragraph "<span style='color:#94a3b8;font-size:11px;'>⚠ Device links are accessible on your local network only.</span>"
            }
            paragraph "<div style='overflow-x:auto; -webkit-overflow-scrolling:touch;'>${table}</div>"
        }
    }
}

// ============================================================
// ===================== PROTOCOL OVERRIDE PAGE ==============
// ============================================================
def protocolOverridePage() {
    def allDevices = getAllMonitoredDevices()

    def protocolDevList = allDevices
        .findAll { device ->
            def hasOverride = settings["protocolOverride_${device.id}"] &&
                              settings["protocolOverride_${device.id}"] != "Auto-detect"
            def rawProtocol = getRawProtocol(device)
            hasOverride || isUnresolvableProtocol(rawProtocol)
        }
        .sort { a, b -> a.displayName.trim() <=> b.displayName.trim() }

    def stateDevList = allDevices
        .findAll { device ->
            def hasOverride = settings["stateAttrOverride_${device.id}"] &&
                              settings["stateAttrOverride_${device.id}"] != "Auto-detect"
            hasOverride || shouldShowStateOverride(device)
        }
        .sort { a, b -> a.displayName.trim() <=> b.displayName.trim() }

    dynamicPage(name: "protocolOverridePage", title: "🔧 Device Overrides", install: false) {

        section("") {
            paragraph "<div style='background-color:#fdf4ff; border-left:4px solid #a855f7; padding:10px 12px; border-radius:3px;'>" +
                      "<span style='font-size:15px; font-weight:bold; color:#4a1772;'>🔀 Protocol Overrides</span><br>" +
                      "<span style='color:#475569;font-size:12px;'>Some Hub Mesh linked devices and LAN devices cannot be automatically identified. " +
                      "Set the correct protocol manually — override always takes priority. " +
                      "Set back to <b>Auto-detect</b> to restore automatic detection.</span></div>"
        }
        if (!protocolDevList || protocolDevList.size() == 0) {
            section("") {
                paragraph "✅ No Hub Mesh, LAN, Virtual, or Hub Variable devices found — no protocol overrides needed."
            }
        } else {
            section("<b>Unidentified / Overridden Devices (${protocolDevList.size()})</b>") {
                protocolDevList.each { device ->
                    def currentProtocol = getProtocol(device)
                    def currentOverride = settings["protocolOverride_${device.id}"] ?: "Auto-detect"
                    def isOverridden    = currentOverride != "Auto-detect"
                    def statusDisplay   = isOverridden
                        ? "<span style='color:#a855f7; font-weight:bold;'>⚙️ Override Active: <span style='background:${getProtocolColor(currentProtocol)}22;color:${getProtocolColor(currentProtocol)};font-weight:700;font-size:13px;padding:2px 8px;border-radius:8px;'>${currentProtocol}</span></span>"
                        : "<span style='color:#374151;font-size:13px;font-weight:500;'>Auto-detected: <span style='background:${getProtocolColor(currentProtocol)}22;color:${getProtocolColor(currentProtocol)};font-weight:700;font-size:13px;padding:2px 8px;border-radius:8px;'>${currentProtocol}</span></span>"
                    input "protocolOverride_${device.id}", "enum",
                          title: "<b>${device.displayName}</b> — ${statusDisplay}",
                          options: ["Auto-detect", "Zigbee", "Z-Wave", "Matter",
                                    "Hub Mesh (Zigbee)", "Hub Mesh (Z-Wave)", "Hub Mesh (Matter)", "Hub Mesh",
                                    "LAN", "Virtual", "Hub Variable"],
                          defaultValue: currentOverride, required: false, width: 6
                }
            }
        }

        section("") {
            paragraph "<div style='background-color:#fdf4ff; border-left:4px solid #a855f7; padding:10px 12px; border-radius:3px; margin-top:8px;'>" +
                      "<span style='font-size:15px; font-weight:bold; color:#4a1772;'>📌 State Attribute Overrides</span><br>" +
                      "<span style='color:#475569;font-size:12px;'>Pin a specific attribute per device when the app picks the wrong one to display in the Current State column. " +
                      "Devices with multiple meaningful attributes, and known single-attribute types (presence/Life360, locks, water, smoke, contact, motion) are always listed here — including newly paired devices where attributes may not yet be populated.</span></div>"
        }
        if (!stateDevList || stateDevList.size() == 0) {
            section("") {
                paragraph "✅ No devices with overrideable state attributes found."
            }
        } else {
            section("<b>Devices with Overrideable State Attributes (${stateDevList.size()})</b>") {
                stateDevList.each { device ->
                    def currentOverride      = settings["stateAttrOverride_${device.id}"] ?: "Auto-detect"
                    def autoResult           = getCurrentStateDisplay(device)
                    def attrs                = getMeaningfulAttributes(device)
                    def options              = ["Auto-detect"] + attrs
                    def overrideStateResult  = currentOverride != "Auto-detect" ? getOverrideStateDisplay(device, currentOverride) : null
                    def overrideValueDisplay = overrideStateResult ? formatStateDisplay(overrideStateResult) : "<span style='color:#1f2937;font-weight:600;font-size:13px;'>${currentOverride}</span>"
                    def currentDisplay       = currentOverride == "Auto-detect"
                        ? "<span style='color:#374151;font-size:13px;font-weight:500;'>Auto-detected: ${autoResult ? formatStateDisplay(autoResult) : "—"}</span>"
                        : "<span style='color:#a855f7; font-weight:bold;'>⚙️ Override Active: ${overrideValueDisplay}</span>"
                    input "stateAttrOverride_${device.id}", "enum",
                          title: "<b>${device.displayName}</b> — ${currentDisplay}",
                          options: options,
                          defaultValue: currentOverride,
                          required: false, width: 6
                }
            }
        }

        section("") {
            paragraph "Tap <b>Done</b> to save. Changes take effect immediately on the next page load."
        }
    }
}

// ============================================================
// ===================== SNOOZE MANAGE PAGE ==================
// ============================================================
def snoozeManagePage() {
    app.removeSetting("devicesToSnooze")
    app.removeSetting("devicesToUnsnooze")
    app.updateSetting("confirmSnooze",   [value: false, type: "bool"])
    app.updateSetting("confirmUnsnooze", [value: false, type: "bool"])

    def devList     = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }
        .sort { a, b -> a.displayName.trim() <=> b.displayName.trim() }
    def snoozedList = devList.findAll { isDeviceSnoozed(it.id as String) }
    def activeList  = devList.findAll { !isDeviceSnoozed(it.id as String) }

    dynamicPage(name: "snoozeManagePage", title: "😴 Manage Snoozed Devices", install: false) {
        section("<b>Snooze Devices</b>") {
            paragraph "Select devices to snooze for <b>${settings?.snoozeDurationHours ?: 24} hours</b>."
            if (activeList) {
                input "devicesToSnooze", "enum",
                      title: "Select devices to snooze:",
                      options: activeList.collectEntries { [(it.id): "${it.displayName} (${state.health?.get(it.id) ?: 'Pending'})"] }
                                        .sort { a, b -> a.value <=> b.value },
                      multiple: true, required: false
            } else {
                paragraph "All devices are currently snoozed."
            }
        }
        if (activeList) {
            section() {
                input "confirmSnooze", "bool", title: "Confirm — snooze selected devices",
                      defaultValue: false, submitOnChange: true
            }
            if (settings?.confirmSnooze == true) {
                section("<b>Snooze Result</b>") {
                    if (settings?.devicesToSnooze) {
                        def count = 0
                        settings.devicesToSnooze.each { deviceId -> snoozeDevice(deviceId); count++ }
                        app.updateSetting("confirmSnooze", [value: false, type: "bool"])
                        paragraph "✅ Snoozed ${count} device(s) for ${settings?.snoozeDurationHours ?: 24} hours."
                    } else {
                        app.updateSetting("confirmSnooze", [value: false, type: "bool"])
                        paragraph "No devices selected to snooze."
                    }
                }
            }
        }
        section("<b>Currently Snoozed</b>") {
            if (snoozedList) {
                paragraph snoozedList.collect { device ->
                    "😴 ${device.displayName} — ${formatSnoozeRemaining(device.id as String)}"
                }.join("\n")
                input "devicesToUnsnooze", "enum",
                      title: "Select devices to unsnooze early:",
                      options: snoozedList.collectEntries { [(it.id): "${it.displayName} (${formatSnoozeRemaining(it.id as String)})"] }
                                         .sort { a, b -> a.value <=> b.value },
                      multiple: true, required: false
            } else {
                paragraph "No devices are currently snoozed."
            }
        }
        if (snoozedList) {
            section() {
                input "confirmUnsnooze", "bool", title: "Confirm — unsnooze selected devices",
                      defaultValue: false, submitOnChange: true
            }
            if (settings?.confirmUnsnooze == true) {
                section("<b>Unsnooze Result</b>") {
                    if (settings?.devicesToUnsnooze) {
                        def count = 0
                        settings.devicesToUnsnooze.each { deviceId -> unsnoozeDevice(deviceId); count++ }
                        app.updateSetting("confirmUnsnooze", [value: false, type: "bool"])
                        paragraph "✅ Unsnoozed ${count} device(s)."
                    } else {
                        app.updateSetting("confirmUnsnooze", [value: false, type: "bool"])
                        paragraph "No devices selected to unsnooze."
                    }
                }
            }
        }
    }
}

// ============================================================
// ===================== FORCE SCAN PAGE =====================
// ============================================================
def forceScanPage() {
    scanAllDevices()
    dynamicPage(name: "forceScanPage", title: "Force Scan", install: false) {
        section("<b>Scan Complete</b>") {
            def devList         = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }
            def intervalStr     = settings?.scanInterval ?: "3"
            def intervalMinutes = (intervalStr.toFloat() * 60).toInteger()
            def minGate         = Math.min(intervalMinutes * 0.5, 30.0).toInteger()
            paragraph "✅ Scan complete — ${devList.size()} device(s) checked. Check-in history, health scores, and device states have all been updated.<br><br>" +
                      "<b>Note:</b> A new check-in sample is only recorded if at least <b>${minGate} minutes</b> " +
                      "have passed since the last recorded activity."
        }
    }
}

// ============================================================
// ===================== RESET HISTORY PAGE ==================
// ============================================================
def resetHistoryPage() {
    app.removeSetting("resetHistoryDevices")
    app.updateSetting("resetHistoryConfirm", [value: false, type: "bool"])
    def devList = getAllMonitoredDevices()
        .findAll { getProtocol(it) != "Unknown" }
        .sort { a, b -> a.displayName.trim() <=> b.displayName.trim() }
    dynamicPage(name: "resetHistoryPage", title: "Reset Device History", install: false) {
        section("<b>Select Devices to Reset</b>") {
            if (!devList || devList.size() == 0) {
                paragraph "No devices available."
            } else {
                paragraph "Select one or more devices to reset. Their check-in history and learned baseline will be cleared."
                input "resetHistoryDevices", "enum",
                      title: "Select devices to reset",
                      options: devList.collectEntries { [(it.id): "${it.displayName} (${state.health?.get(it.id) ?: 'Pending'})"] }
                                      .sort { a, b -> a.value <=> b.value },
                      multiple: true, required: false
            }
        }
        section("<b>Confirm Reset</b>") {
            input "resetHistoryConfirm", "bool",
                  title: "Confirm — clear history for selected devices",
                  defaultValue: false
        }
        section() {
            href(name: "toResetConfirm", page: "resetHistoryConfirmPage", title: "Submit Reset")
        }
    }
}

def resetHistoryConfirmPage() {
    def devList = getAllMonitoredDevices()
    dynamicPage(name: "resetHistoryConfirmPage", title: "Reset Device History", install: false) {
        section("<b>Result</b>") {
            if (!resetHistoryConfirm) {
                paragraph "Reset cancelled — confirm checkbox was not checked."
            } else if (!resetHistoryDevices) {
                paragraph "No devices selected."
            } else {
                def successCount = 0
                def resetNames   = []
                resetHistoryDevices.each { deviceId ->
                    def device = devList.find { it.id == deviceId }
                    if (device) {
                        def h = state.history ?: [:]
                        h[device.id] = [
                            lastSeen:     now(),
                            samples:      [],
                            avgInterval:  null,
                            userInterval: state.history?.get(device.id)?.userInterval,
                            protocol:     getProtocol(device)
                        ]
                        state.history = h

                        def health = state.health ?: [:]
                        health[device.id] = "Pending"
                        state.health = health

                        def sh = state.stateHistory ?: [:]
                        sh.remove(device.id)
                        state.stateHistory = sh

                        resetNames << device.displayName
                        successCount++
                    }
                }
                if (successCount > 0) {
                    paragraph "✅ History reset for ${successCount} device(s): ${resetNames.join(', ')}."
                } else {
                    paragraph "No valid devices found."
                }
            }
        }
    }
}

// ============================================================
// ===================== SEND NOTIFICATION PAGE ==============
// ============================================================
def sendNotificationPage() {
    dynamicPage(name: "sendNotificationPage", title: "Send Notification", install: false) {
        def devList    = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }
        def hasDevices = devList.size() > 0
        def hasTargets = (settings?.notifyDevices?.size() ?: 0) > 0 ||
                         (settings?.pushoverDevices?.size() ?: 0) > 0 ||
                         (settings?.enablePush == true)
        def notifyOn   = settings?.enablePush != false

        if (!hasDevices) {
            section("<b>Cannot Send</b>") { paragraph "⚠️ No monitored devices are selected." }; return
        }
        if (!notifyOn) {
            section("<b>Cannot Send</b>") { paragraph "⚠️ Notifications are turned off." }; return
        }
        if (!hasTargets) {
            section("<b>Cannot Send</b>") { paragraph "⚠️ No notification devices configured." }; return
        }
        section("<b>Confirm</b>") {
            paragraph "This will send a device health summary notification now."
            input "sendNowConfirm", "bool", title: "✅ Confirm — send the notification",
                  defaultValue: false, submitOnChange: true
        }
        if (settings?.sendNowConfirm) {
            section("<b>Result</b>") {
                scheduledSummary()
                app.updateSetting("sendNowConfirm", [value: false, type: "bool"])
                def sentTo = []
                if (settings?.notifyDevices)   sentTo.addAll(settings.notifyDevices.collect { it.displayName })
                if (settings?.pushoverDevices) sentTo.addAll(settings.pushoverDevices.collect { "${it.displayName} (Pushover)" })
                paragraph sentTo ? "✅ Notification sent to:\n" + sentTo.collect { "• ${it}" }.join("\n")
                                 : "✅ Notification sent via hub push."
            }
        }
    }
}

// ============================================================
// ===================== SCHEDULED SUMMARY ===================
// ============================================================
def scheduledSummary() {
    if (!isModeOK()) return
    def devList = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }
    if (!devList) return

    def usePushover = (settings?.enablePushover == true && settings?.pushoverPrefix?.trim())
    def prefix      = ""
    def postfix     = ""
    if (usePushover) {
        def tags = settings.pushoverPrefix.trim()
        def priorityMatch = tags =~ /^(\[[EHLNS]\])(.*)/
        if (priorityMatch) { prefix = priorityMatch[0][1]; postfix = priorityMatch[0][2].trim() }
        else { postfix = tags }
    }

    def body = "${prefix}📡 Device Health Summary\n"

    def sections = [
        "Offline":   [emoji: "💀", enabled: settings?.notifyOffline   != false, list: []],
        "Poor":      [emoji: "🔴", enabled: settings?.notifyPoor      != false, list: []],
        "Fair":      [emoji: "🟠", enabled: settings?.notifyFair      != false, list: []],
        "Good":      [emoji: "🟢", enabled: settings?.notifyGood      ?: false, list: []],
        "Excellent": [emoji: "🟢", enabled: settings?.notifyExcellent ?: false, list: []],
    ]

    devList.each { device ->
        if (!isDeviceSnoozed(device.id as String)) {
            def h = state.health?.get(device.id) ?: "Pending"
            if (sections.containsKey(h)) {
                def stateInfo = getCurrentStateDisplay(device)
                def stateStr  = stateInfo ? " [${stateInfo.label}]" : ""
                def lastStr   = state.history?.get(device.id)?.lastSeen
                    ? ", last seen ${formatTimeAgo(state.history[device.id].lastSeen)}"
                    : ""
                sections[h].list << "${device.displayName.trim()}${stateStr}${lastStr}"
            }
        }
    }

    if (settings?.suppressEmptyReport) {
        def hasContent = sections.any { h, data -> data.enabled && data.list }
        if (!hasContent) return
    }

    sections.each { health, data ->
        if (data.enabled) {
            body += "\n${data.emoji} ${health}:\n"
            if (data.list) {
                data.list.each { name -> body += "• ${name}\n" }
            } else {
                body += "None\n"
            }
        }
    }

    def pushoverBody = body
    def plainBody    = body
    if (postfix) pushoverBody += "${postfix}\n"

    if (settings?.enablePush)      sendPush(pushoverBody)
    if (settings?.pushoverDevices) settings.pushoverDevices.each { it.deviceNotification(pushoverBody) }
    if (settings?.notifyDevices)   notifyDevices.each { it.deviceNotification(plainBody) }
}

// ============================================================
// ===================== INFO PAGE ===========================
// ============================================================
def infoPage(Map params = [:]) {
    dynamicPage(name: "infoPage", title: "App Guide & Reference", install: false) {

        section("<b>🔑 Health Ratings</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "<div style='overflow-x:auto;'><table style='width:100%; border-collapse: collapse;'>" +
                      "<tr style='font-weight:bold;'><td style='padding:4px 8px;'>Health</td><td style='padding:4px 8px;'>Meaning</td></tr>" +
                      "<tr><td style='padding:4px 8px;'>⏳ Pending (n/3 samples)</td><td style='padding:4px 8px;'>Learning — sample count shown inline until 3 are collected</td></tr>" +
                      "<tr><td style='padding:4px 8px;'>🟢 Excellent</td><td style='padding:4px 8px;'>Checking in within 1.2× of baseline</td></tr>" +
                      "<tr><td style='padding:4px 8px;'>🟢 Good</td><td style='padding:4px 8px;'>Checking in within 2× of baseline</td></tr>" +
                      "<tr><td style='padding:4px 8px;'>🟠 Fair</td><td style='padding:4px 8px;'>Checking in within 3× of baseline</td></tr>" +
                      "<tr><td style='padding:4px 8px;'>🔴 Poor</td><td style='padding:4px 8px;'>Checking in beyond 3× of baseline</td></tr>" +
                      "<tr><td style='padding:4px 8px;'>💀 Offline</td><td style='padding:4px 8px;'>No activity for ${settings?.offlineThresholdHours ?: 72}h (hard threshold)</td></tr>" +
                      "<tr><td style='padding:4px 8px;'>😴 Snoozed</td><td style='padding:4px 8px;'>Excluded from notifications for a set duration</td></tr>" +
                      "<tr><td style='padding:4px 8px;'>ℹ️ Low Activity Device</td><td style='padding:4px 8px;'>Monitored 7+ days with fewer than 3 samples — normal for infrequently used devices. Only shown on Fair, Poor, and Offline ratings where it provides context.</td></tr>" +
                      "</table></div></div>"
        }

        section("<b>⏳ How Baselines Are Learned</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "The app learns each device's normal check-in pattern automatically — no configuration needed.<br><br>" +
                      "<b>Sample collection:</b> Each time a device checks in, the elapsed time since its last check-in is recorded as a sample. " +
                      "Samples are smoothed using an exponential average to prevent a single unusual gap from skewing the baseline.<br><br>" +
                      "<b>Pending state:</b> A device shows ⏳ Pending until 3 samples have been collected. The sample count is shown inline — e.g. ⏳ Pending (1/3 samples). " +
                      "No health rating is assigned during this period to avoid false alerts from sparse early data.<br><br>" +
                      "<b>Avg Check-in column:</b> Shows the learned average check-in interval. Displays <b>Learning...</b> until 3 samples exist. " +
                      "Once learned, this is the baseline all health ratios are calculated against.<br><br>" +
                      "<b>Minimum gate:</b> To avoid recording noise, a sample is only counted if at least half the scan interval has passed since the last recorded activity (capped at 30 minutes). " +
                      "Running Force Scan frequently will not inflate the sample count — the gate prevents that.<br><br>" +
                      "<b>Sample window:</b> Up to 20 samples are kept per device. Older samples are discarded as new ones arrive, keeping the baseline current.</div>"
        }

        section("<b>📡 Protocol Detection &amp; Overrides</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "The app detects each device's protocol automatically using Hubitat's internal controller type, data values, and driver name.<br><br>" +
                      "<b>Auto-detected protocols:</b> Zigbee, Z-Wave, Matter, Hub Mesh (Zigbee / Z-Wave / Matter), LAN, Virtual, and Hub Variable.<br><br>" +
                      "<b>Why a device might show as LAN or Hub Mesh:</b> Hub Mesh linked devices and some LAN integrations do not expose enough data for the app to determine the underlying protocol. " +
                      "These land on Hub Mesh or LAN rather than Zigbee / Z-Wave.<br><br>" +
                      "<b>Unknown devices:</b> Devices that cannot be identified at all are skipped entirely and not monitored. The Unknown count is shown on the main page.<br><br>" +
                      "<b>Protocol Overrides:</b> Found under <b>🔧 Protocol &amp; State Overrides</b> on the main page. " +
                      "Only devices that are unresolvable (Hub Mesh, LAN, Virtual, Hub Variable) or already overridden are listed — cleanly detected Zigbee and Z-Wave devices do not appear here. " +
                      "Set the correct protocol manually and the override takes effect immediately. " +
                      "Set back to <b>Auto-detect</b> at any time to restore automatic detection. " +
                      "An <b>(override)</b> label appears next to the protocol in the Activity Summary so you can see at a glance which devices have been manually set.</div>"
        }

        section("<b>📌 State Attribute Overrides</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "The <b>Current State</b> column in the Activity Summary shows each device's most meaningful state — ON/OFF, Open/Closed, Active/Inactive, Locked/Unlocked, and so on.<br><br>" +
                      "<b>How auto-detection works:</b> The app checks the device's driver name and available attributes in priority order. " +
                      "Contact sensors show contact state, motion sensors show motion, locks show lock state, and so on. " +
                      "For devices without a recognized primary state, LAN-type attributes like status, connectionStatus, and operatingState are tried as a fallback.<br><br>" +
                      "<b>When auto-detection picks the wrong attribute:</b> Some devices report multiple meaningful attributes — for example a multi-sensor with both motion and contact. " +
                      "If the wrong one is showing, use <b>State Attribute Overrides</b> under <b>🔧 Protocol &amp; State Overrides</b> to pin the correct attribute. " +
                      "Devices with multiple meaningful attributes, and known single-attribute types (presence/Life360, locks, water, smoke, contact, motion) are always listed here — " +
                      "including newly paired devices where attributes may not yet be populated.<br><br>" +
                      "<b>Resetting:</b> Set back to <b>Auto-detect</b> at any time to let the app choose again automatically.</div>"
        }

        section("<b>🔄 Verification (Ping / Refresh)</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "When a device's health drops to Poor or Offline, the app attempts to verify whether it is truly unreachable before leaving it flagged.<br><br>" +
                      "<b>How verification works:</b> On the scan where a device first enters Poor or Offline, the app tries to send a <b>refresh()</b> or <b>ping()</b> command to the device. " +
                      "If the device responds, its last activity timestamp updates and health will recover on the next scan. " +
                      "The verification status is shown inline in the Health column — e.g. <i>🔄 Verifying... (refresh sent)</i>.<br><br>" +
                      "<b>Supported device types:</b> Any device with the Refresh or Ping capability. Refresh is tried first; Ping is used if Refresh is not available.<br><br>" +
                      "<b>Hue devices — important:</b> Hue bulbs and fixtures cannot be pinged or refreshed directly — they communicate exclusively through the Hue Bridge. " +
                      "For verification to work on any Hue device, <b>you must add your Hue Bridge to the monitored devices list.</b><br><br>" +
                      "When a Hue device goes Poor or Offline, the app calls <code>refresh()</code> on the Bridge, which polls all connected Hue devices at once — so one Bridge refresh covers every Hue bulb and fixture simultaneously.<br><br>" +
                      "<b>CoCoHue users:</b> Add the <b>CoCoHue Bridge</b> device to your monitored devices list, not individual bulbs or fixtures.<br><br>" +
                      "<b>If the Bridge is not added:</b> Hue devices will show <i>⚠ Cannot verify — add Hue Bridge to monitored devices</i> and verification will be skipped until the Bridge is included.<br><br>" +
                      "<b>Virtual and Hub Variable devices</b> cannot be verified and will show <i>⚠ Cannot verify — virtual device</i>.<br><br>" +
                      "<b>Devices without Refresh or Ping</b> will show <i>⚠ Cannot verify — device does not support ping or refresh</i>. " +
                      "This is normal for many Zigbee and Z-Wave sensors — it does not indicate a problem with the device or the app.</div>"
        }

        section("<b>🔗 Hub Mesh Source Hub Detection</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "The Hub Mesh Overview page attempts to group devices by their source hub. Detection tries three methods in order:<br><br>" +
                      "1. <b>hubName data value</b> — some Hubitat versions populate this on linked devices<br>" +
                      "2. <b>Device Network ID prefix</b> — some integrations encode the hub name in the DNI<br>" +
                      "3. <b>Fallback: \"Remote Hub\"</b> — used if neither method returns a value<br><br>" +
                      "Source hub detection is currently unsupported on C-8 Pro firmware v2.5.0.126–128 — all Hub Mesh devices show as \"Remote Hub\". " +
                      "This does not affect health monitoring in any way. " +
                      "Hub grouping will improve automatically if Hubitat exposes hub name data in a future firmware release.</div>"
        }

        section("<b>💡 Tips for Best Results</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "• The Activity Summary reflects the state at the last scan — device states do not update live in the browser. Use <b>Force Scan</b> to refresh immediately.<br>" +
                      "• The State Changed column updates on each scan cycle, not in real time — a state change that happens between scans will be captured on the next scan.<br>" +
                      "• Hub Mesh Overview is most useful when you have 10+ Hub Mesh devices — for smaller setups the Activity Summary is sufficient.<br>" +
                      "• Notifications include [state] and last-seen time for devices where a state is detectable — if no state is available the device name and last-seen time are shown without brackets.<br>" +
                      "• <b>ℹ️ Low Activity Device</b> on a Fair, Poor, or Offline rating means the device checks in infrequently by nature — not that something is broken. Common on lights, switches, and garage doors.<br>" +
                      "• If a device is stuck at Pending after several days, check that it is actively reporting to the hub — a device that never checks in will never accumulate samples.<br>" +
                      "• <b>Hue users:</b> Add your Hue Bridge (or CoCoHue Bridge) to the monitored devices list. Without it, the app cannot verify Hue bulbs and fixtures that go Poor or Offline.</div>"
        }
    }
}
