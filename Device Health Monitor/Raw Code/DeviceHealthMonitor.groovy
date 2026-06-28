/**
 * Device Health Monitor
 * Version: 1.5.10
 *
 * Author: jdthomas24
 */

definition(
    name: "Device Health Monitor",
    namespace: "jdthomas24",
    author: "jdthomas24",
    description: "Monitor device check-in health across Zigbee, Z-Wave, Matter, Hub Mesh, LAN, Virtual and Hub Variable — learns each device's normal pattern and alerts you when something goes quiet. Includes OAuth web portal, SPA dashboard, batch scanning, location grouping, and richer notifications.",
    category: "Convenience",
    importUrl: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/refs/heads/main/Device%20Health%20Monitor/Raw%20Code/DeviceHealthMonitor.groovy",
    iconUrl: "",
    iconX2Url: "",
    version: "1.5.10",
    doNotFocus: true,
    oauth: true
)

// ============================================================
// ===================== OAUTH MAPPINGS ======================
// ============================================================
mappings {
    path("/dashboard") { action: [GET: "serveDashboardPage"]  }
    path("/data")      { action: [GET: "serveDataEndpoint"]   }
    path("/refresh")   { action: [GET: "forceRefreshEndpoint"] }
    path("/updateDevice") { action: [GET: "updateDeviceEndpoint"] }
}

// ============================================================
// ===================== PREFERENCES =========================
// ============================================================
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
    page(name: "locationAssignPage")
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
    runIn(1800, disableDebugLogging)
    }


def appButtonHandler(btn) {
    if (btn == "btnRunDeepScan") {
        runDeepVerificationScan()
    }
}

def initialize() {
    if (debugEnabled()) log.debug "Device Health Monitor initializing"
    if (state.history       == null) state.history       = [:]
    if (state.health        == null) state.health        = [:]
    if (state.snoozed       == null) state.snoozed       = [:]
    if (state.verifying     == null) state.verifying     = [:]
    if (state.stateHistory  == null) state.stateHistory  = [:]
    // Always reset scan state on initialize — if the hub rebooted mid-scan,
    // isScanning and scanStartTime persist in state and would trigger the
    // stuck-scan watchdog on every subsequent scan until manually cleared.
    state.isScanning    = false
    state.scanStartTime = null
    state.scanQueue     = []
    state.tempResults   = []
    if (state.deviceCapabilities  == null) state.deviceCapabilities  = [:]
    if (state.deepScanResult      == null) state.deepScanResult      = [:]
    if (state.dropHistory         == null) state.dropHistory         = [:]
    if (state.fairHold            == null) state.fairHold            = [:]

    if (!state.capabilitiesResetDone) {
        state.deviceCapabilities  = [:]
        state.capabilitiesResetDone = true
        if (debugEnabled()) log.debug "Device Health Monitor: reset deviceCapabilities — will rebuild on next scan"
    }

    // v1.5.10: One-time migration — collapse unbounded BigDecimal precision that had
    // accumulated in state.history samples/avgInterval over weeks of scans. Groovy's
    // BigDecimal math doesn't auto-round, and each smoothed sample is computed from
    // the PREVIOUS smoothed sample recursively, so decimal scale compounded every
    // single scan with nothing ever resetting it — some samples had grown to 40+
    // digits after a few weeks. Left unchecked this bloated state.history to several
    // KB per device (671KB total observed across 142 devices, 94% of it in history
    // alone), which risked stalling the scan pipeline before it could even start.
    // Existing samples are rounded to 2 decimal places in place here; new samples
    // are rounded at the point of computation going forward (see processScanChunk)
    // so this can't recur.
    if (!state.samplesRoundedDone) {
        def roundedAny = false
        state.history?.each { id, data ->
            if (data?.samples) {
                data.samples = data.samples.collect { (it as BigDecimal).setScale(2, BigDecimal.ROUND_HALF_UP) }
                roundedAny = true
            }
            if (data?.avgInterval != null) {
                data.avgInterval = (data.avgInterval as BigDecimal).setScale(2, BigDecimal.ROUND_HALF_UP)
            }
        }
        state.samplesRoundedDone = true
        if (roundedAny) log.info "Device Health Monitor: one-time cleanup — rounded bloated sample precision in state.history"
    }
    if (state.deviceLocations == null) state.deviceLocations = [:]

    getAllMonitoredDevices()?.each { device ->
        def existing = settings["loc_${device.id}"]
        if (existing && !state.deviceLocations?.containsKey(device.id as String)) {
            if (!state.deviceLocations) state.deviceLocations = [:]
            state.deviceLocations[device.id as String] = existing
        }
    }
    state.deviceLocations = state.deviceLocations

    if (!state.accessToken) {
        try {
            createAccessToken()
        } catch (e) {
            log.error "Device Health Monitor: OAuth is not enabled. Please enable OAuth in the App Code screen."
        }
    }

    scheduleScanInterval()
    scheduleReportFrequency()
    scheduleDeepVerificationScan()
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

def findKonnectedPanel(device) {
    def monitoredPanels = getAllMonitoredDevices().findAll { d ->
        (d.typeName ?: "").toLowerCase().contains("konnected alarm panel")
    }
    if (!monitoredPanels) return null

    try {
        def parentId = device.parentDeviceId
        if (parentId) {
            def panel = monitoredPanels.find { d -> d.id == parentId }
            if (panel) return panel
        }
    } catch (e) {}

    def dni = (device.deviceNetworkId ?: "")
    if (dni.contains("-")) {
        def parts    = dni.split("-")
        def parentId = parts[0]
        def suffix   = parts.size() > 1 ? parts[1] : ""
        if (suffix.isNumber() && new BigDecimal(suffix).toLong() > 1000) {
            def panel = monitoredPanels.find { d -> d.id == parentId }
            if (panel) return panel
        }
    }

    return null
}

def isKonnectedDevice(device) {
    return findKonnectedPanel(device) != null
}

def isModeOK() {
    if (!settings?.enableModeRestriction) return true
    if (!settings?.restrictedModes) return true
    return settings.restrictedModes.contains(location.mode)
}

// ============================================================
// ===================== DEVICE CATEGORY HELPERS =============
// ============================================================
def setDeviceLocation(String deviceId, String loc) {
    if (!state.deviceLocations) state.deviceLocations = [:]
    if (loc == null || loc == "") {
        state.deviceLocations.remove(deviceId)
        app.removeSetting("loc_${deviceId}")
    } else {
        state.deviceLocations[deviceId] = loc
        app.updateSetting("loc_${deviceId}", [type: "string", value: loc])
    }
    state.deviceLocations = state.deviceLocations
}

def getDeviceLocation(deviceId) {
    return state.deviceLocations?.get(deviceId as String) ?: settings["loc_${deviceId}"] ?: ""
}

def getDeviceDescription(device) {
    return settings["desc_${device.id}"] ?: ""
}

// ============================================================
// ===================== PING STATUS HELPER ==================
// ============================================================
def markChildrenPingAttempted(String parentId) {
    def allDevs = getAllMonitoredDevices()
    def capMap  = state.deviceCapabilities ?: [:]
    allDevs.each { device ->
        def dni = (device.deviceNetworkId ?: "")
        def isChild = false
        try { if (device.parentDeviceId == parentId) isChild = true } catch (e) {}
        if (!isChild && dni.contains("-")) {
            def prefix = dni.split("-")[0]
            if (prefix == parentId) isChild = true
        }
        if (isChild) {
            def capKey  = device.id as String
            def capData = capMap[capKey] ?: [:]
            if (capData.pingWorks != true) {
                capData.pingAttempted    = true
                capData.lastPingAttempt  = now()
                capMap[capKey]           = capData
            }
        }
    }
    state.deviceCapabilities = capMap
    if (debugEnabled()) log.debug "DHM: marked children of ${parentId} as pingAttempted"
}

def getPingStatus(deviceId) {
    def capMap = state.deviceCapabilities ?: [:]
    def cap    = capMap[deviceId as String]
    if (!cap) return "unknown"
    if (cap.pingWorks == true)  return "verified"
    if (cap.pingWorks == false) return "unverifiable"
    if (cap.declared  == true)  return "declared"
    return "unknown"
}

def getPingStatusDisplay(deviceId) {
    def cap = state.deviceCapabilities?.get(deviceId as String) ?: [:]
    switch (getPingStatus(deviceId)) {
        case "verified":
            // v1.5.9: distinguish provisional ("weak") verification — a refresh/ping
            // that merely didn't throw — from "confirmed" (a real lastSeen advance,
            // state event, or Hue/Konnected bridge round-trip).
            return cap.pingTrustSource == "weak"
                ? "<span style='color:#0ea5e9;font-size:10px;font-weight:bold;'>🔄 Verified (auto)</span>"
                : "<span style='color:#22c55e;font-size:10px;font-weight:bold;'>✅ Verified</span>"
        case "unverifiable": return "<span style='color:#94a3b8;font-size:10px;'>⚠ Cannot verify</span>"
        case "declared":     return "<span style='color:#f97316;font-size:10px;'>🔄 Verifiable</span>"
        default:             return ""
    }
}

// v1.5.8: A device at Fair health whose reachability is actually confirmed
// (pingWorks == true) is displayed as "Quiet" rather than "Fair" — idle, not
// a problem. This helper identifies that case so Active Issues can exclude
// it: a confirmed-reachable device isn't an issue just because it's quiet.
def isQuietVerified(deviceId) {
    def h = state.health?.get(deviceId) ?: "Pending"
    if (h != "Fair") return false
    def cap = state.deviceCapabilities?.get(deviceId as String) ?: [:]
    return cap.pingWorks == true
}

// v1.5.9: A successful generic refresh()/ping() does NOT prove a Zigbee/Z-Wave
// device actually received the command — Hubitat hands the command to the
// mesh and returns success regardless of delivery, unlike a Hue Bridge or
// Konnected Panel poll, which is a real network round-trip. So a successful
// generic refresh/ping is only "weak" trust: good enough to avoid nagging
// about an idle-but-probably-fine device, but not good enough to trust
// forever. Weak trust is capped at 2x the Offline Threshold — long enough to
// ride out normal quiet periods, short enough to guarantee the device can
// never be silently masked forever. See updateHealth() for where this is
// enforced and where it's cleared by genuine confirmation.
def getWeakTrustCeilingMs() {
    return ((settings?.offlineThresholdHours ?: 168) * 3600000L) * 2
}

// ============================================================
// ===================== REPEAT DROPS / EXTENDED STATE =======
// ============================================================
def isRepeatDrops(deviceId) {
    def drops = state.dropHistory?.get(deviceId as String) ?: []
    drops = drops.findAll { now() - it < 86400000 }
    return drops.size() >= 3
}

def getExtendedStateTag(device) {
    if (device.hasAttribute("motion") && device.currentValue("motion") == "active") {
        try {
            def stateDate = device.currentState("motion")?.date
            if (stateDate) {
                def hoursActive = (now() - stateDate.time) / 3600000
                if (hoursActive >= 2) {
                    return " <span style='color:#f97316;font-size:10px;'>⏰ Active ${hoursActive.toInteger()}h</span>"
                }
            }
        } catch (e) {}
    }
    if (device.hasAttribute("contact") && device.currentValue("contact") == "open") {
        try {
            def stateDate = device.currentState("contact")?.date
            if (stateDate) {
                def hoursOpen = (now() - stateDate.time) / 3600000
                if (hoursOpen >= 24) {
                    return " <span style='color:#f97316;font-size:10px;'>⏰ Open ${hoursOpen.toInteger()}h</span>"
                }
            }
        } catch (e) {}
    }
    return ""
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
// ===================== STATE-CHANGE VERIFICATION ===========
// ============================================================
def getStateVerified(deviceId) {
    try {
        def tracked = state.stateHistory?.get(deviceId as String)
        if (!tracked?.lastChanged) return false
        def data = state.history?.get(deviceId as String)
        if (!data?.lastSeen) return false
        def stateChangedAfterLastSeen = (tracked.lastChanged as Long) > (data.lastSeen as Long)
        def thresholdMs = ((settings?.offlineThresholdHours ?: 168) * 60 * 60 * 1000 * 1.0).toLong()  // v1.5.3: extended to full window
        def stateChangeIsRecent = (now() - (tracked.lastChanged as Long)) < thresholdMs
        return stateChangedAfterLastSeen && stateChangeIsRecent
    } catch (e) {
        if (debugEnabled()) log.debug "getStateVerified error for device ${deviceId}: ${e.message}"
        return false
    }
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
            return [label: val.capitalize(), color: color, isAlert: isAlert, type: attr]
        }

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

        def currentLabel = app.label ?: "Device Health Monitor"
        section("<b>App Display Name</b> — <span style='color:blue;'>${currentLabel}</span>", hideable: true, hidden: true) {
            paragraph "Enter a name to rename this app in your Hubitat app list."
            input "customAppName", "text", title: "Custom App Name", required: false
        }

        def portalEnabled    = state.accessToken != null
        def portalStatus     = portalEnabled ? "<span style='color:blue; font-weight:bold;'>Enabled</span>" : "<span style='color:red; font-weight:bold;'>Not Enabled</span>"
        def portalSectionTitle = "<b>Device Health Portal</b> — ${portalStatus}"

        section(portalSectionTitle, hideable: true, hidden: portalEnabled) {
            if (portalEnabled) {
                def cloudUrl = getFullApiServerUrl()
                def localUrl = getFullLocalApiServerUrl()
                paragraph "<div style='padding:10px; background-color:#d1ecf1; border:1px solid #bee5eb; color:#0c5460; border-radius:4px;'>" +
                          "<b>Cloud URL (use anywhere):</b><br>" +
                          "<a href='${cloudUrl}/dashboard?access_token=${state.accessToken}' target='_blank' style='color:#0c5460; word-wrap:break-word;'>${cloudUrl}/dashboard?access_token=${state.accessToken}</a><br><br>" +
                          "<b>Local URL (use at home):</b><br>" +
                          "<a href='${localUrl}/dashboard?access_token=${state.accessToken}' target='_blank' style='color:#0c5460; word-wrap:break-word;'>${localUrl}/dashboard?access_token=${state.accessToken}</a>" +
                          "</div>"
            } else {
                def hubIp = location?.hub?.localIP ?: ""
                paragraph "<div style='padding:10px; background-color:#f8d7da; border:1px solid #f5c6cb; color:#721c24; border-radius:4px;'>" +
                          "<b>OAuth is not yet enabled.</b> To activate the web portal:<br><br>" +
                          "1. Go to <b>Apps Code</b> in the Hubitat menu" + (hubIp ? " — <a href='http://${hubIp}/app/list' target='_blank' style='color:#721c24;'>tap here to open Apps Code</a>" : "") + "<br>" +
                          "2. Find <b>Device Health Monitor</b> in the list and open it<br>" +
                          "3. Click <b>OAuth</b> in the top-right of the code editor<br>" +
                          "4. Click <b>Enable OAuth in App</b> → <b>Update</b><br>" +
                          "5. Return here and tap <b>Done</b> to save — the portal URLs will appear above." +
                          "</div>"
            }
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

        def scanIntervalLabel  = ["0.5": "Every 30 min", "1": "Hourly", "3": "Every 3 h", "6": "Every 6 h"]
        def currentScan        = scanIntervalLabel[settings?.scanInterval ?: "3"] ?: "Every 3 h"
        def currentThreshold   = settings?.offlineThresholdHours ?: 168
        def snoozeOn           = snoozeEnabled()
        def currentSnooze      = settings?.snoozeDurationHours ?: 24
        def modeOn             = settings?.enableModeRestriction == true
        def modeLabel          = modeOn ? (settings?.restrictedModes ? settings.restrictedModes.join(", ") : "none set") : "off"
        def snoozedDeviceCount = state.snoozed?.count { id, until -> until >= now() } ?: 0
        def scanningLabel      = state.isScanning ? " | <span style='color:#1a73e8;'>🔄 Scanning...</span>" : ""
        def snoozeLabel = !snoozeOn ? "<span style='color:red;'>off</span>" :
                          snoozedDeviceCount > 0 ? "<span style='color:orange;'>${snoozedDeviceCount} snoozed</span>" :
                          "<span style='color:blue;'>${currentSnooze}h</span>"
        def monitoringTitle = "<b>Monitoring Settings</b> — " +
            "Scan: <span style='color:blue;'>${currentScan}</span> | " +
            "Offline after: <span style='color:blue;'>${currentThreshold}h</span> | " +
            "Snooze: ${snoozeLabel} | " +
            "Mode: <span style='color:${modeOn ? "blue" : "red"};'>${modeOn ? modeLabel : "off"}</span>${scanningLabel}"

        section(monitoringTitle, hideable: true, hidden: true) {
            paragraph "<hr style='background-color:#eee; height:1px; border:0; margin:4px 0 10px 0;'/>"
            paragraph "<b>Scan Interval</b> — how often device activity is checked and health ratings are updated."
            input "scanInterval", "enum",
                  title: "Scan Frequency:",
                  options: ["0.5": "Every 30 Minutes", "1": "Hourly", "3": "Every 3 Hours", "6": "Every 6 Hours"],
                  defaultValue: "3", submitOnChange: true

            paragraph "<hr style='background-color:#eee; height:1px; border:0; margin:10px 0;'/>"
            paragraph "<b>Offline after inactivity (hours)</b> — devices with no activity beyond this threshold are marked Offline. Also controls how long a verified ping is trusted before re-verification is required."
            input "offlineThresholdHours", "number", title: "Offline after inactivity (hours):",
                  defaultValue: 168, required: true, submitOnChange: true

            paragraph "<hr style='background-color:#eee; height:1px; border:0; margin:10px 0;'/>"
            paragraph "<b>Snooze</b> — enable or disable snooze globally."
            input "enableSnooze", "bool", title: "Enable snooze", defaultValue: false, submitOnChange: true
            if (snoozeEnabled()) {
                input "snoozeDurationHours", "number", title: "Snooze duration (hours):",
                      defaultValue: 24, required: true, submitOnChange: true
            }

            def deepResult    = state.deepScanResult
            def deepResultStr = deepResult ? new Date(deepResult.ranAt).format("MM/dd/yy h:mm a", location.timeZone) +
                " — ${deepResult.verified} verified, ${deepResult.unverifiable} unverifiable, ${deepResult.declared} still declared" : "Never run"
            def deepEnabled   = settings?.enableDeepScan == true
            def deepTitle     = "<b>Deep Verification Scan</b> — <span style='color:${deepEnabled ? "blue" : "#94a3b8"};'>${deepEnabled ? "Scheduled" : "Off"}</span>"

            paragraph "<hr style='background-color:#eee; height:1px; border:0; margin:10px 0;'/>"
            paragraph deepTitle
            paragraph "<b>Last run:</b> ${deepResultStr}"
            input "enableDeepScan", "bool", title: "Schedule — runs once then auto-disables:", defaultValue: false, submitOnChange: true
            if (deepEnabled) {
                input "deepScanTime", "time", title: "Run at:", required: true
            }
            input "btnRunDeepScan", "button", title: "▶ Run Now"

            paragraph "<hr style='background-color:#eee; height:1px; border:0; margin:10px 0;'/>"
            paragraph "<b>Mode Restriction</b> — optionally restrict notifications to specific hub modes."
            input "enableModeRestriction", "bool", title: "Enable mode restriction for notifications",
                  defaultValue: false, submitOnChange: true
            if (settings?.enableModeRestriction) {
                input "restrictedModes", "mode",
                      title: "Only send notifications when hub is in one of these modes:",
                      multiple: true, required: false
            }
        }

        def notifOn           = settings?.enablePush != false
        def notifSectionTitle = "<b>Notifications</b> — <span style='color:${notifOn ? "blue" : "red"};'>${notifOn ? "ON" : "OFF"}</span>"
        section(notifSectionTitle, hideable: true, hidden: true) {
            input "enablePush", "bool", title: "Enable notifications", defaultValue: false
            input "reportFrequency", "enum",
                  title: "Notification Frequency:",
                  options: ["daily": "Daily", "every2": "Every 2 Days", "every3": "Every 3 Days", "weekly": "Weekly"],
                  defaultValue: "daily"
            input "summaryTime", "time", title: "Notification Time:", required: false
            input "notifyDevices", "capability.notification",
                  title: "Notification devices", multiple: true, required: false
            input "enablePushover", "bool", title: "⚙️ Enable Pushover Markup", defaultValue: false
            input "pushoverDevices", "capability.notification",
                  title: "Pushover notification devices", multiple: true, required: false
            input "pushoverPrefix", "text",
                  title: "Pushover tags",
                  description: "e.g. [H][TITLE=Device Health Report][HTML][SELFDESTRUCT=43200]",
                  required: false

            paragraph "<b>Report Sections:</b>"
            input "notifyOffline",       "bool", title: "💀 Include Offline devices",              defaultValue: true
            input "notifyPoor",          "bool", title: "🔴 Include Poor health devices",           defaultValue: true
            input "notifyFair",          "bool", title: "🟠 Include Fair health devices",           defaultValue: true
            input "notifyGood",          "bool", title: "🟢 Include Good health devices",           defaultValue: false
            input "notifyExcellent",     "bool", title: "🟢 Include Excellent health devices",      defaultValue: false
            input "suppressEmptyReport", "bool", title: "🔕 Don't send notification if nothing to report", defaultValue: false

            paragraph "<b>Send notification now:</b>"
            href(name: "toSendNotification", page: "sendNotificationPage", title: "📤 Send Notification Now")
        }

        section("<b>Reports:</b>") {
            href(name: "toActivitySummary", page: "activitySummaryPage",
                 title: "<b>Device Activity Summary</b>",
                 description: "All devices, health status, current state")
            href(name: "toProblemDevices", page: "problemDevicesPage",
                 title: "<b>⚠️ Problem Devices & Verification</b>",
                 description: "Active issues, unverifiable devices, and verification status")
            if (getAllMonitoredDevices().any { getProtocol(it).startsWith("Hub Mesh") }) {
                href(name: "toHubMeshSummary", page: "hubMeshSummaryPage",
                     title: "<b>🔗 Hub Mesh Overview</b>",
                     description: "Health summary grouped by source hub")
            }
            href(name: "toLocationAssign", page: "locationAssignPage",
                 title: "<b>🏷️ Location Assignment</b>",
                 description: "Assign rooms and descriptions to devices — used in portal")

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
                 description: "Health scoring, state tracking, portal setup, and troubleshooting explained")
            paragraph rawHtml: true, """
<div style='padding:4px 0;'>
  <a href='https://community.hubitat.com/t/release-device-health-monitor/163229' target='_blank'
     style='display:block; background:#f8f8f8; border:1px solid #ddd; border-radius:6px; padding:10px 14px; text-decoration:none; color:#333; margin-bottom:6px;'>
    <span style='font-size:14px;'>💬 <b>Hubitat Community Thread</b></span><br>
    <span style='font-size:12px; color:#888;'>Questions, feedback, and release notes</span>
  </a>
  <a href='https://paypal.me/jdthomas24?locale.x=en_US&country.x=US' target='_blank'
     style='display:block; background:#f8f8f8; border:1px solid #ddd; border-radius:6px; padding:10px 14px; text-decoration:none; color:#333;'>
    <span style='font-size:14px;'>☕ <b>Buy Me a Coffee</b></span><br>
    <span style='font-size:12px; color:#888;'>Enjoying the app? Any amount is appreciated — thank you!</span>
  </a>
</div>
"""
        }

        section("<b>Diagnostics</b>") {
            input "debugMode", "bool",
                  title: "Debug Logging (auto-disables after 30 min)",
                  defaultValue: false, submitOnChange: true
            paragraph "<span style='color:#94a3b8; font-size:11px;'>Device Health Monitor v1.5.10</span>"
        }
    }
}

// ============================================================
// ===================== LOCATION ASSIGNMENT PAGE ============
// ============================================================
def getRoomOptions() {
    def locs = []
    (1..30).each { i ->
        def v = settings["loc${i}"] ?: ""
        def t = v.trim()
        if (t != "") locs << t
    }
    return locs.sort()
}

def locationAssignPage() {
    def roomOptions = getRoomOptions()
    def devList = getAllMonitoredDevices()
        .findAll { getProtocol(it) != "Unknown" }
        .sort { a, b -> a.displayName.trim() <=> b.displayName.trim() }

    dynamicPage(name: "locationAssignPage", title: "Location Assignment", install: false) {

        def hasLocs     = getRoomOptions().size() > 0
        def locCount    = getRoomOptions().size()
        def locTitle    = hasLocs
            ? "<b>Locations</b> — <span style='color:blue;'>${locCount} defined</span>"
            : "<b>Locations</b>"

        section(locTitle, hideable: true, hidden: hasLocs) {
            paragraph "<i>Enter your room and area names below. Leave unused boxes blank.</i>"
            input "btnSaveLocations", "button", title: "💾 Save Locations"
            (1..10).each { i ->
                def col1 = i
                def col2 = i + 10
                def col3 = i + 20
                input "loc${col1}", "text",
                      title: (settings["loc${col1}"] ?: "") != "" ? "<b>✅ Location ${col1}</b>" : "<b>Location ${col1}</b>",
                      defaultValue: settings["loc${col1}"] ?: "",
                      required: false, width: 4
                input "loc${col2}", "text",
                      title: (settings["loc${col2}"] ?: "") != "" ? "<b>✅ Location ${col2}</b>" : "<b>Location ${col2}</b>",
                      defaultValue: settings["loc${col2}"] ?: "",
                      required: false, width: 4
                input "loc${col3}", "text",
                      title: (settings["loc${col3}"] ?: "") != "" ? "<b>✅ Location ${col3}</b>" : "<b>Location ${col3}</b>",
                      defaultValue: settings["loc${col3}"] ?: "",
                      required: false, width: 4
                paragraph "<hr style='background-color:#ddd; height:1px; border:0; margin:0;'/>"
            }
        }

        if (!devList || devList.size() == 0) {
            section("") { paragraph "No monitored devices found. Select devices on the main page first." }
            return
        }

        if (roomOptions.size() > 0) {
            section("<b>Assign Devices to a Room</b>") {
                paragraph "<i>Select a room — devices already assigned to it will be pre-checked. Check or uncheck devices, then confirm to save.</i>"
                def devOptions = devList.collectEntries { [(it.id): it.displayName] }.sort { a, b -> a.value <=> b.value }

                input "bulkLoc", "enum",
                      title: "Room:",
                      options: roomOptions,
                      required: false,
                      submitOnChange: true

                if (settings?.bulkLoc) {
                    def selectedRoom = settings.bulkLoc
                    def currentlyInRoom = devList
                        .findAll { getDeviceLocation(it.id) == selectedRoom }
                        .collect { it.id as String }

                    if (state.lastBulkLoc != selectedRoom) {
                        state.lastBulkLoc = selectedRoom
                        if (currentlyInRoom) {
                            app.updateSetting("bulkDevs", [type: "enum", value: currentlyInRoom])
                        } else {
                            app.removeSetting("bulkDevs")
                        }
                    }

                    def count = currentlyInRoom.size()
                    paragraph "<span style='color:#94a3b8;font-size:12px;'>${count} device(s) currently assigned to <b>${selectedRoom}</b></span>"

                    input "bulkDevs", "enum",
                          title: "Devices in ${selectedRoom}:",
                          options: devOptions,
                          multiple: true,
                          required: false

                    input "bulkApplyConfirm", "bool",
                          title: "Confirm — save device assignments for ${selectedRoom}",
                          defaultValue: false,
                          submitOnChange: true

                    if (settings?.bulkApplyConfirm == true) {
                        def newDevIds  = settings.bulkDevs instanceof List ? settings.bulkDevs :
                                         settings.bulkDevs ? [settings.bulkDevs] : []
                        def addedCount   = 0
                        def removedCount = 0

                        newDevIds.each { dId ->
                            if (getDeviceLocation(dId) != selectedRoom) {
                                setDeviceLocation(dId, selectedRoom)
                                addedCount++
                            }
                        }

                        currentlyInRoom.each { dId ->
                            if (!newDevIds.contains(dId)) {
                                setDeviceLocation(dId, "")
                                removedCount++
                            }
                        }

                        state.lastBulkLoc = null
                        app.updateSetting("bulkApplyConfirm", [value: false, type: "bool"])

                        def msg = "✅ ${selectedRoom} updated"
                        if (addedCount   > 0) msg += " — ${addedCount} added"
                        if (removedCount > 0) msg += " — ${removedCount} removed"
                        paragraph msg
                    }
                }
            }

            def unassigned = devList.findAll { !getDeviceLocation(it.id) }
            def assigned   = devList.findAll { getDeviceLocation(it.id) }

            section("<b>Device Summary</b>") {
                paragraph "Assigned: <b><span style='color:blue;'>${assigned.size()}</span></b> &nbsp;|&nbsp; Unassigned: <b><span style='color:${unassigned.size() > 0 ? 'red' : 'blue'};'>${unassigned.size()}</span></b> &nbsp;|&nbsp; Total: <b>${devList.size()}</b>"
            }

            section("<b>Individual Devices</b>", hideable: true, hidden: true) {
                paragraph "<i>For faster assignment use the web portal — tap any device card to set its location.</i>"
                devList.each { device ->
                    def currentLoc  = getDeviceLocation(device.id)
                    def currentDesc = settings["desc_${device.id}"] ?: ""
                    def h           = state.health?.get(device.id) ?: "Pending"
                    def protocol    = getProtocol(device)
                    def tag         = currentLoc ? "<span style='color:blue;font-size:11px;'>🏷️ ${currentLoc}</span>" : "<span style='color:#94a3b8;font-size:11px;'>unassigned</span>"
                    paragraph "<b>${device.displayName}</b> ${tag} <span style='color:#94a3b8;font-size:11px;'>${h} · ${protocol}</span>"
                    input "loc_${device.id}", "enum",
                          title: "Location:",
                          options: roomOptions,
                          defaultValue: currentLoc,
                          required: false,
                          width: 6
                    input "desc_${device.id}", "text",
                          title: "Description:",
                          defaultValue: currentDesc,
                          required: false,
                          width: 6
                    paragraph "<hr style='background-color:#eee; height:1px; border:0; margin:4px 0;'/>"
                }
            }
        } else {
            section("") {
                paragraph "<i>Enter your locations above and tap Done — dropdowns and the portal will populate automatically.</i>"
            }
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

def scheduleDeepVerificationScan() {
    unschedule("runDeepVerificationScan")
    if (settings?.enableDeepScan && settings?.deepScanTime) {
        schedule(settings.deepScanTime, runDeepVerificationScan)
        if (debugEnabled()) log.debug "Deep verification scan scheduled for ${settings.deepScanTime}"
    }
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
// ===================== DEEP VERIFICATION SCAN ==============
// ============================================================
def runDeepVerificationScan() {
    def devList = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }
    if (!devList) return

    def targets = devList.findAll { getPingStatus(it.id) in ["declared", "unknown"] }

    log.info "Device Health Monitor: deep verification scan starting — ${targets.size()} device(s) to verify"
    if (targets.size() == 0) {
        log.info "Device Health Monitor: deep verification scan — nothing to verify (all devices already Verified or Unverifiable)"
        return
    }

    def totalDevices = targets.size()
    def batchSize    = totalDevices > 200 ? 25 : 40
    def groups       = targets.collate(batchSize)
    def totalGroups  = groups.size()
    log.info "Device Health Monitor: deep scan — ${totalGroups} batch(es) of ${batchSize}"

    state.deepScanQueue   = groups.collect { group -> group.collect { it.id } }
    state.deepScanTotal   = totalGroups
    state.deepScanCurrent = 0

    processDeepScanGroup()
    def actualDelay = ((totalGroups - 1) * 2) + 10
    runIn(actualDelay, "finalizeDeepScan", [overwrite: false])
}

def processDeepScanGroup(data = null) {
    def queue   = state.deepScanQueue ?: []
    if (queue.isEmpty()) return

    def deviceIds   = queue.remove(0)
    state.deepScanQueue   = queue
    state.deepScanCurrent = (state.deepScanCurrent ?: 0) + 1
    def groupNum    = state.deepScanCurrent
    def totalGroups = state.deepScanTotal ?: 0
    def allDevs     = getAllMonitoredDevices()
    log.info "Device Health Monitor: deep scan batch ${groupNum}/${totalGroups} — pinging ${deviceIds.size()} device(s)"

    if (!queue.isEmpty()) {
        runIn(2, "processDeepScanGroup", [overwrite: false])
    }

    deviceIds.each { devId ->
        def device = allDevs.find { it.id == devId }
        if (!device) return

        def capMapD  = state.deviceCapabilities ?: [:]
        def capKeyD  = devId as String
        def capDataD = capMapD[capKeyD] ?: [:]
        def protocol  = getProtocol(device)
        def isVirtual = protocol in ["Virtual", "Hub Variable"]

        if (isVirtual) {
            capDataD.pingWorks  = false
            capDataD.pingFailed = (capDataD.pingFailed ?: 0) + 1
        } else if (isHueDevice(device)) {
            def bridge = findHueBridge()
            if (bridge) {
                try { bridge.refresh(); capDataD.pingAttempted = true; capDataD.lastPingAttempt = now() }
                catch (e) { capDataD.pingWorks = false; capDataD.pingFailed = (capDataD.pingFailed ?: 0) + 1 }
            } else {
                capDataD.pingWorks = false
            }
        } else {
            def hasRefresh       = false
            def hasPing          = false
            def hasCustomRefresh = false
            try { hasRefresh       = device.hasCapability("Refresh") } catch (e) {}
            try { hasPing          = device.hasCapability("Ping")    } catch (e) {}
            try { hasCustomRefresh = device.hasCommand("forceRefresh") || device.hasCommand("refresh") } catch (e) {}

            if (hasRefresh) {
                try { device.refresh(); capDataD.pingAttempted = true; capDataD.lastPingAttempt = now() }
                catch (e) { capDataD.pingWorks = false; capDataD.pingFailed = (capDataD.pingFailed ?: 0) + 1 }
            } else if (hasPing) {
                try { device.ping(); capDataD.pingAttempted = true; capDataD.lastPingAttempt = now() }
                catch (e) { capDataD.pingWorks = false; capDataD.pingFailed = (capDataD.pingFailed ?: 0) + 1 }
            } else if (hasCustomRefresh) {
                try {
                    if (device.hasCommand("forceRefresh")) device.forceRefresh()
                    else device.refresh()
                    capDataD.pingAttempted = true; capDataD.lastPingAttempt = now()
                } catch (e) { capDataD.pingWorks = false; capDataD.pingFailed = (capDataD.pingFailed ?: 0) + 1 }
            } else {
                capDataD.pingWorks  = false
                capDataD.pingFailed = (capDataD.pingFailed ?: 0) + 1
            }
        }
        capMapD[capKeyD]         = capDataD
        state.deviceCapabilities = capMapD
    }
}

def finalizeDeepScan() {
    def devList = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }

    def verified     = devList.count { getPingStatus(it.id) == "verified"     }
    def unverifiable = devList.count { getPingStatus(it.id) == "unverifiable" }
    def declared     = devList.count { getPingStatus(it.id) == "declared"     }

    state.deepScanResult = [
        ranAt:        now(),
        verified:     verified,
        unverifiable: unverifiable,
        declared:     declared
    ]

    app.updateSetting("enableDeepScan", [value: false, type: "bool"])
    unschedule("runDeepVerificationScan")

    log.info "Device Health Monitor: deep verification scan complete — ${verified} verified, ${unverifiable} unverifiable, ${declared} still declared"

    runIn(30, "scanAllDevices", [overwrite: true])
    log.info "Device Health Monitor: running scan to check for ping responses — waiting 30s for device responses"
}

// ============================================================
// ===================== SCAN — BATCHED ======================
// ============================================================
def scanAllDevices() {
    def devList = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }
    if (!devList) return

    def nowMs = new Date().time
    if (state.isScanning && state.scanStartTime && (nowMs - state.scanStartTime > 120000)) {
        log.warn "Device Health Monitor: previous scan appears stuck — resetting."
        state.isScanning  = false
        state.scanQueue   = []
        state.tempResults = []
    }

    if (state.isScanning) {
        if (debugEnabled()) log.debug "Scan already in progress — skipping duplicate request."
        return
    }

    log.info "Device Health Monitor: scan started — ${devList.size()} device(s) queued"
    state.isScanning    = true
    state.scanStartTime = nowMs
    state.tempResults   = []
    state.scanQueue     = devList.collect { it.id }

    purgeOrphanedState(devList)
    runIn(1, "processScanChunk")
}

def purgeOrphanedState(devList) {
    def activeIds = devList.collect { it.id as String } as Set

    ["history", "health", "verifying", "stateHistory", "fairHold"].each { stateKey ->
        def map = state[stateKey]
        if (map instanceof Map) {
            def stale = map.keySet().findAll { !(it in activeIds) }
            if (stale) {
                stale.each { map.remove(it) }
                state[stateKey] = map
                if (debugEnabled()) log.debug "Purged ${stale.size()} orphaned ${stateKey} entr${stale.size() == 1 ? 'y' : 'ies'}"
            }
        }
    }

    if (state.snoozed instanceof Map) {
        def snoozedCopy  = state.snoozed
        def staleSnoozed = snoozedCopy.keySet().findAll { !(it in activeIds) }
        if (staleSnoozed) {
            staleSnoozed.each { snoozedCopy.remove(it) }
            state.snoozed = snoozedCopy
        }
    }
}

def processScanChunk() {
    if (!state.isScanning) return

    def queue = state.scanQueue ?: []
    if (queue.size() == 0) {
        finalizeScan()
        return
    }

    def totalDevices = getAllMonitoredDevices().size()
    def chunkSize    = totalDevices > 200 ? 25 : 40
    def chunk        = queue.take(chunkSize)
    def remaining    = queue.drop(chunkSize)
    state.scanQueue  = remaining
    def batchNum     = Math.ceil(totalDevices / chunkSize).toInteger() - Math.ceil(remaining.size() / chunkSize).toInteger()
    log.info "Device Health Monitor: scanning batch ${batchNum} — ${chunk.size()} devices (${remaining.size()} remaining)"

    def allDevs         = getAllMonitoredDevices()
    def intervalStr     = settings?.scanInterval ?: "3"
    def intervalMinutes = (intervalStr.toFloat() * 60).toInteger()
    def minGate         = Math.min(intervalMinutes * 0.5, 30.0)
    def nowMs           = new Date().time

    chunk.each { devId ->
        def device = allDevs.find { it.id == devId }
        if (!device) return

        try {
            def id       = device.id
            def data     = state.history[id]
            def protocol = getProtocol(device)
            def filtered = usesFilteredSampling(protocol)

            def lastActivity = device.getLastActivity()
            def lastSeen     = (lastActivity ? safeTime(lastActivity) : null) ?: now()
            // v1.5.6: Also consider lastKnownStateDate from previous scans
            def capMapPre  = state.deviceCapabilities ?: [:]
            def prevKnown  = capMapPre[id as String]?.lastKnownStateDate as Long ?: 0
            if (prevKnown > lastSeen) lastSeen = prevKnown

            try {
                def stateDate = device.currentStates?.collect { safeTime(it.date) }?.findAll { it }?.max()
                if (stateDate && stateDate > lastSeen) lastSeen = stateDate
                // v1.5.6: Store lastKnownStateDate separately so verified refresh responses
                // advance lastSeen even when getLastActivity() does not update (common in Z-Wave).
                def capMapLS  = state.deviceCapabilities ?: [:]
                def capKeyLS  = id as String
                def capDataLS = capMapLS[capKeyLS] ?: [:]
                def prevStateDate = capDataLS.lastKnownStateDate as Long ?: 0
                if (stateDate && stateDate > prevStateDate) {
                    capDataLS.lastKnownStateDate = stateDate
                    capMapLS[capKeyLS] = capDataLS
                    state.deviceCapabilities = capMapLS
                    // If this is newer than what getLastActivity returned, use it as lastSeen
                    if (stateDate > lastSeen) lastSeen = stateDate
                }
            } catch (e) {
                if (debugEnabled()) log.debug "currentStates date check error for ${device.displayName}: ${e.message}"
            }

            def capMap  = state.deviceCapabilities ?: [:]
            def capKey  = id as String
            def capData = capMap[capKey] ?: [:]

            if (isHueDevice(device)) {
                if (debugEnabled()) log.debug "DHM capability scan: ${device.displayName} detected as Hue — marking declared"
                capData.declared        = true
                capData.declaredRefresh = true
                if (capData.pingWorks == false) { capData.pingWorks = null; capData.pingFailed = 0 }
            } else if (isKonnectedDevice(device)) {
                if (debugEnabled()) log.debug "DHM capability scan: ${device.displayName} detected as Konnected child (DNI: ${device.deviceNetworkId}) — marking declared"
                capData.declared        = true
                capData.declaredRefresh = true
                if (capData.pingWorks == false) { capData.pingWorks = null; capData.pingFailed = 0 }
            } else {
                def declaredRefresh  = false
                def declaredPing     = false
                def hasCustomRefresh = false
                def capCheckOk       = false
                try { declaredRefresh  = device.hasCapability("Refresh");    capCheckOk = true } catch (e) {}
                try { declaredPing     = device.hasCapability("Ping");       capCheckOk = true } catch (e) {}
                try { hasCustomRefresh = device.hasCommand("forceRefresh") ||
                                         device.hasCommand("refresh")       ; capCheckOk = true } catch (e) {}
                capData.declaredRefresh  = declaredRefresh || hasCustomRefresh
                capData.declaredPing     = declaredPing
                capData.declared         = declaredRefresh || declaredPing || hasCustomRefresh
                capData.customRefreshCmd = hasCustomRefresh && !declaredRefresh ? "forceRefresh" : null
                if (capCheckOk && !capData.declared && capData.pingWorks == null) {
                    capData.pingWorks  = false
                    capData.pingFailed = 0
                }
            }
            if (!capData.containsKey("pingFailed")) capData.pingFailed = 0
            capMap[capKey] = capData
            state.deviceCapabilities = capMap

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
                    def capMapRec  = state.deviceCapabilities ?: [:]
                    def capKeyRec  = id as String
                    def capDataRec = capMapRec[capKeyRec] ?: [:]
                    if (capDataRec.pingAttempted == true) {
                        capDataRec.pingWorks     = true
                        capDataRec.pingFailed    = 0
                        capDataRec.pingAttempted = false
                        // v1.5.9: a genuine lastSeen advance is real confirmation —
                        // upgrade trust source and clear any weak-trust ceiling/cooldown.
                        capDataRec.pingTrustSource        = "confirmed"
                        capDataRec.weakTrustFirstGranted   = null
                        capDataRec.weakTrustCooldownUntil  = null
                        capMapRec[capKeyRec]     = capDataRec
                        state.deviceCapabilities = capMapRec
                        if (debugEnabled()) log.debug "${device.displayName}: ping confirmed working — device responded after verification attempt"
                    }
                    def elapsed = ((lastSeen - prevLastSeen) / (1000 * 60)).toBigDecimal().setScale(2, BigDecimal.ROUND_HALF_UP)
                    data.lastSeen = lastSeen
                    if (elapsed >= minGate) {
                        def recordSample = true
                        if (filtered) {
                            recordSample = elapsed <= (intervalMinutes * 1.5)
                        }
                        if (recordSample) {
                            // v1.5.10: Round both elapsed (above) and smoothed (below) to 2
                            // decimal places at the point of computation. Without this,
                            // Groovy's BigDecimal division/multiplication preserves full
                            // precision and compounds it every scan, since smoothed is
                            // computed recursively from the previous smoothed value — see
                            // the migration note in initialize() for what this fixed.
                            def alpha      = 0.15
                            def prevSmooth = (data.samples && data.samples.size() > 0) ? data.samples[-1] : elapsed
                            def smoothed   = (alpha * elapsed + (1 - alpha) * prevSmooth).setScale(2, BigDecimal.ROUND_HALF_UP)
                            data.samples << smoothed
                            if (data.samples.size() > 20) data.samples.remove(0)
                            if (data.samples.size() >= 3) {
                                data.avgInterval = (data.samples.sum() / data.samples.size()).toBigDecimal().setScale(2, BigDecimal.ROUND_HALF_UP)
                            }
                        }
                    }
                }
                data.protocol     = protocol
                state.history[id] = data
                updateHealth(device)
            }
            updateStateTracking(device)

        } catch (e) {
            log.warn "Scan failed for ${device.displayName}: ${e.message}"
        }
    }

    if (state.scanQueue.size() > 0) {
        runIn(2, "processScanChunk")
    } else {
        runIn(1, "finalizeScan")
    }
}

def finalizeScan() {
    if (!state.isScanning) return
    state.isScanning    = false
    state.scanStartTime = null
    state.tempResults   = []
    state.scanQueue     = []
    log.info "Device Health Monitor: scan complete — all devices processed"
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

    def offlineThreshold     = ((settings?.offlineThresholdHours ?: 168) * 60).toDouble()
    def minutesSinceLastSeen = (now() - (data.lastSeen ?: now())) / (1000 * 60)

    if (minutesSinceLastSeen >= offlineThreshold) {
        state.health[id] = "Offline"
    } else {
        // v1.5.3: Protocol-aware minimum baseline floor
        // Prevents burst-usage devices (Apple TV, media players, LAN devices) from
        // learning an unrealistically short baseline during active periods and then
        // falsely scoring Poor when they go quiet. Zigbee/Z-Wave floors stay tight
        // so real mesh failures are still caught quickly.
        def protocol    = getProtocol(device)
        def minBaseline = 30.0  // default — Zigbee / Z-Wave (30 min)
        switch (protocol) {
            case "LAN":
            case "Hub Mesh":
            case "Hub Mesh (Zigbee)":
            case "Hub Mesh (Z-Wave)":
            case "Hub Mesh (Matter)":
                minBaseline = 480.0    // 8 hours — LAN/media devices
                break
            case "Matter":
                minBaseline = 120.0    // 2 hours
                break
            case "Virtual":
            case "Hub Variable":
                minBaseline = 1440.0   // 24 hours
                break
        }
        def baseline = Math.max(
            (data.userInterval ?: data.avgInterval ?: 60).toDouble(),
            minBaseline
        )
        // v1.5.3: Loosened thresholds — give burst-use devices (locks, lights,
        // switches, media devices, door sensors) real breathing room before
        // notifications fire. Devices need to go truly quiet before reaching Poor.
        def ratio = minutesSinceLastSeen / baseline
        if      (ratio <= 1.5) state.health[id] = "Excellent"
        else if (ratio <= 3.0) state.health[id] = "Good"
        else if (ratio <= 6.0) state.health[id] = "Fair"
        else                   state.health[id] = "Poor"
    }

    def currentHealth = state.health[id]

    // v1.5.3: Pingable hold-at-Fair gate
    // When a device would enter Poor for the first time and supports refresh/ping,
    // hold it at Fair for ONE scan cycle while a verification ping is sent.
    // A fairHold flag is stored so the gate only fires once per drop event —
    // on the next scan the device is allowed through to Poor if still quiet.
    // If it responds before then → recovers on its own, never reaches Poor.
    if (currentHealth == "Poor") {
        def prevH = state.prevHealth?.get(id as String)
        if (prevH != "Poor" && prevH != "Offline") {
            def capChk     = state.deviceCapabilities?.get(id as String) ?: [:]
            def isPingable = capChk.pingWorks == true ||
                             capChk.declared  == true ||
                             isHueDevice(device) ||
                             isKonnectedDevice(device)
            // Only hold if we haven't already held this drop cycle
            def fairHolds  = state.fairHold ?: [:]
            def alreadyHeld = fairHolds[id as String] == true
            if (isPingable && !alreadyHeld) {
                state.health[id] = "Fair"
                currentHealth    = "Fair"
                if (!state.fairHold) state.fairHold = [:]
                state.fairHold[id as String] = true
                state.fairHold = state.fairHold
                if (debugEnabled()) log.debug "${device.displayName}: first Poor entry — holding at Fair for one scan pending verification ping"
            } else if (alreadyHeld) {
                // Hold already used — clear it and let Poor through
                def fh = state.fairHold ?: [:]
                fh.remove(id as String)
                state.fairHold = fh
                if (debugEnabled()) log.debug "${device.displayName}: fairHold expired — promoting to Poor"
            }
        } else {
            // Device was already Poor/Offline — clear any stale fairHold
            def fh = state.fairHold ?: [:]
            if (fh.containsKey(id as String)) { fh.remove(id as String); state.fairHold = fh }
        }
    } else if (currentHealth in ["Good", "Excellent", "Pending"]) {
        // Device recovered — clear fairHold so next drop gets a fresh hold cycle
        def fh = state.fairHold ?: [:]
        if (fh.containsKey(id as String)) { fh.remove(id as String); state.fairHold = fh }
    }

    // v1.5.5: Verified devices cannot be Poor or Offline
    // v1.5.7: Verification trust expires after offlineThresholdHours.
    // A stale pingWorks=true (older than the offline threshold) is cleared so
    // battery-dead or truly offline devices are not permanently masked behind
    // "Quiet verified reachable". The fairHold gate then gets a fresh cycle to
    // attempt re-verification before the device escalates to Poor or Offline.
    // v1.5.9: "confirmed" trust (a real lastSeen advance, a real state event,
    // or a Hue/Konnected bridge round-trip) behaves exactly as before. "weak"
    // trust (a generic refresh()/ping() that merely didn't throw) gets the
    // same one-cycle-at-a-time renewal below, but ALSO has its own ceiling —
    // if it's been running purely on weak trust for getWeakTrustCeilingMs()
    // (default 2x Offline Threshold) with zero genuine confirmation, trust is
    // force-cleared and a cooldown is set so the device is guaranteed to show
    // a real Poor/Offline for a full Offline Threshold window before weak
    // trust can be granted again. This is what stops a never-confirmed device
    // from being masked as "Quiet" forever.
    if (currentHealth in ["Poor", "Offline"]) {
        def capChk = state.deviceCapabilities?.get(id as String) ?: [:]
        if (capChk.pingWorks == true) {
            def isWeak        = capChk.pingTrustSource == "weak"
            def weakCeilingMs = getWeakTrustCeilingMs()
            def weakExceeded  = isWeak && capChk.weakTrustFirstGranted &&
                                 (now() - (capChk.weakTrustFirstGranted as Long)) >= weakCeilingMs

            def pingAge      = now() - (capChk.lastPingAttempt as Long ?: 0)
            def maxPingAgeMs = ((settings?.offlineThresholdHours ?: 168) * 3600000L)

            if (weakExceeded) {
                def capMap  = state.deviceCapabilities ?: [:]
                def capData = capMap[id as String] ?: [:]
                capData.pingWorks              = null
                capData.pingFailed             = 0
                capData.pingTrustSource        = null
                capData.weakTrustFirstGranted  = null
                // Guarantee at least one full Offline Threshold window of real
                // visibility before weak trust can mask this device again.
                capData.weakTrustCooldownUntil = now() + maxPingAgeMs
                capMap[id as String] = capData
                state.deviceCapabilities = capMap
                if (debugEnabled()) log.debug "${device.displayName}: weak trust ceiling reached (${(weakCeilingMs/3600000).round(0)}h) with no genuine confirmation — forcing real ${currentHealth} for at least ${(maxPingAgeMs/3600000).round(0)}h"
                // currentHealth stays Poor/Offline this cycle — fairHold was already evaluated above
            } else if (pingAge < maxPingAgeMs) {
                // Trust still valid — cap at Fair, display as Quiet
                state.health[id] = "Fair"
                currentHealth    = "Fair"
                if (debugEnabled()) log.debug "${device.displayName}: capped at Fair — verified reachable (ping age ${(pingAge/3600000).round(1)}h, trust=${capChk.pingTrustSource ?: 'confirmed'})"
            } else {
                // Trust expired — null out pingWorks so fairHold gets a fresh attempt.
                // weakTrustFirstGranted is deliberately preserved here (not cleared) so
                // the cumulative weak-trust clock keeps running across repeated
                // re-verification cycles instead of resetting every time.
                def capMap  = state.deviceCapabilities ?: [:]
                def capData = capMap[id as String] ?: [:]
                capData.pingWorks  = null
                capData.pingFailed = 0
                capMap[id as String] = capData
                state.deviceCapabilities = capMap
                if (debugEnabled()) log.debug "${device.displayName}: pingWorks trust expired (${(pingAge/3600000).round(1)}h old) — clearing for fresh verification"
                // currentHealth stays Poor/Offline — fairHold was already evaluated above
            }
        }
    }

    def prevHealth = state.prevHealth?.get(id as String)
    if (currentHealth in ["Poor", "Offline"] && !(prevHealth in ["Poor", "Offline"])) {
        def dropMap  = state.dropHistory ?: [:]
        def drops    = dropMap[id as String] ?: []
        drops << now()
        drops = drops.findAll { now() - it < 86400000 }
        dropMap[id as String] = drops
        state.dropHistory = dropMap
    }

    // v1.5.2: Auto-reset verification status on health recovery
    // When a device recovers from Poor/Offline back to Good/Excellent,
    // clear pingWorks so it gets a fresh verification attempt next time it drops.
    // This prevents devices from being permanently stuck as Unverifiable after
    // a single bad attempt — seasonal/sporadic devices benefit most from this.
    // v1.5.9: a real recovery to Good/Excellent only happens via a genuine
    // lastSeen advance, so it's also genuine confirmation — always clear any
    // weak-trust ceiling/cooldown tracking too (previously this only wrote
    // capMapR back when pingWorks was false, silently leaving stale weak-trust
    // timestamps behind on the unconditional path).
    if (currentHealth in ["Good", "Excellent"] && prevHealth in ["Poor", "Offline"]) {
        def capMapR  = state.deviceCapabilities ?: [:]
        def capKeyR  = id as String
        def capDataR = capMapR[capKeyR] ?: [:]
        if (capDataR.pingWorks == false) {
            capDataR.pingWorks  = null
            capDataR.pingFailed = 0
            if (debugEnabled()) log.debug "${device.displayName}: health recovered to ${currentHealth} — verification status reset for fresh re-evaluation"
        }
        capDataR.pingTrustSource        = "confirmed"
        capDataR.weakTrustFirstGranted  = null
        capDataR.weakTrustCooldownUntil = null
        capMapR[capKeyR]    = capDataR
        state.deviceCapabilities = capMapR
    }

    if (!state.prevHealth) state.prevHealth = [:]
    def prevMap = state.prevHealth
    prevMap[id as String] = currentHealth
    state.prevHealth = prevMap

    if (!(currentHealth in ["Poor", "Offline"])) {
        state.verifying?.remove(id)
        return
    }

    if (state.verifying == null) state.verifying = [:]

    // v1.5.8: Removed the every-other-scan verification throttle that used to
    // live here. It returned early on alternating scans without re-attempting
    // verification, which caused the "sometimes shows Verifying, sometimes
    // doesn't" flicker and meant a result could take 2-3 Force Scan taps to
    // show up. Verification now runs every scan a device is Poor/Offline —
    // for Hue/Konnected this is one lightweight Bridge/Panel call per scan;
    // for direct refresh/ping it's a modest increase in command frequency to
    // devices that are already flagged as a problem.

    if (getStateVerified(id as String)) {
        state.verifying[id] = "state_verified"
        log.info "Device Health Monitor: ${currentHealth} — ${device.displayName} self-verified via state change event (no ping needed)"
        // v1.5.9: a real state-change event is genuine confirmation too —
        // clear any weak-trust ceiling/cooldown tracking.
        def capMapSV  = state.deviceCapabilities ?: [:]
        def capDataSV = capMapSV[id as String] ?: [:]
        if (capDataSV.pingTrustSource == "weak" || capDataSV.weakTrustFirstGranted || capDataSV.weakTrustCooldownUntil) {
            capDataSV.pingTrustSource        = "confirmed"
            capDataSV.weakTrustFirstGranted  = null
            capDataSV.weakTrustCooldownUntil = null
            capMapSV[id as String] = capDataSV
            state.deviceCapabilities = capMapSV
        }
        if (data?.samples?.size() > 0) {
            data.samples.remove(data.samples.size() - 1)
            if (data.samples.size() >= 3) {
                data.avgInterval = (data.samples.sum() / data.samples.size()).toBigDecimal().setScale(2, BigDecimal.ROUND_HALF_UP)
            }
            state.history[id] = data
        }
        return
    }

    def protocol     = getProtocol(device)
    def isVirtual    = protocol in ["Virtual", "Hub Variable"]
    def hasRefresh   = false
    def hasPing      = false
    def verifyMethod = ""

    if (isVirtual) {
        verifyMethod = "virtual"
    } else if (isHueDevice(device)) {
        def bridge = findHueBridge()
        if (bridge) {
            try {
                bridge.refresh()
                verifyMethod = "hue_bridge"
                markChildrenPingAttempted(bridge.id as String)
            }
            catch (e) { verifyMethod = "hue_bridge_failed" }
        } else {
            verifyMethod = "hue_no_bridge"
        }
    } else if (isKonnectedDevice(device)) {
        def panel = findKonnectedPanel(device)
        if (panel) {
            try {
                panel.refresh()
                verifyMethod = "konnected_panel"
                markChildrenPingAttempted(panel.id as String)
            }
            catch (e) { verifyMethod = "konnected_panel_failed" }
        } else {
            verifyMethod = "konnected_no_panel"
        }
    } else {
        try { hasRefresh = device.hasCapability("Refresh") } catch (e) { }
        try { hasPing    = device.hasCapability("Ping")    } catch (e) { }
        def hasCustomRefresh = false
        try { hasCustomRefresh = device.hasCommand("forceRefresh") || device.hasCommand("refresh") } catch (e) {}
        if (hasRefresh) {
            try { device.refresh(); verifyMethod = "refresh" }
            catch (e) { verifyMethod = "failed" }
        } else if (hasPing) {
            try { device.ping(); verifyMethod = "ping" }
            catch (e) { verifyMethod = "failed" }
        } else if (hasCustomRefresh) {
            try {
                if (device.hasCommand("forceRefresh")) { device.forceRefresh(); verifyMethod = "refresh" }
                else { device.refresh(); verifyMethod = "refresh" }
            } catch (e) { verifyMethod = "failed" }
        } else {
            verifyMethod = "none"
        }
    }
    state.verifying[id] = verifyMethod
    if (verifyMethod in ["refresh", "ping"]) {
        log.info "Device Health Monitor: ${currentHealth} — sent ${verifyMethod} to ${device.displayName}"
    } else if (verifyMethod in ["none", "virtual", "hue_no_bridge", "failed"]) {
        if (debugEnabled()) log.debug "Device Health Monitor: ${currentHealth} — cannot verify ${device.displayName} (${verifyMethod})"
    }

    def capMapH  = state.deviceCapabilities ?: [:]
    def capKeyH  = id as String
    def capDataH = capMapH[capKeyH] ?: [:]
    if (verifyMethod in ["hue_bridge", "konnected_panel"]) {
        // v1.5.8: Bridge/Panel proxy verification confirms immediately.
        // bridge.refresh() / panel.refresh() returns this device's full current
        // state regardless of whether the value changed — but Hubitat's
        // CoCoHue/Konnected drivers only emit a fresh event when the value
        // *does* change. A bulb/sensor that's been idle for weeks would
        // otherwise never advance lastSeen and stay stuck at Offline/Verifiable
        // forever, even though the Bridge/Panel call is succeeding every time.
        // A clean call is itself sufficient proof of reachability for this device.
        capDataH.lastPingAttempt    = now()
        capDataH.pingAttempted      = false
        capDataH.pingWorks          = true
        capDataH.pingFailed         = 0
        // v1.5.9: a Bridge/Panel refresh is a real network round-trip — mark
        // as "confirmed" trust, which has no weak-trust ceiling.
        capDataH.pingTrustSource        = "confirmed"
        capDataH.weakTrustFirstGranted  = null
        capDataH.weakTrustCooldownUntil = null
        // Apply the Quiet cap immediately within this same scan instead of
        // waiting for the next one to notice pingWorks=true — this is what
        // lets a single Force Scan fully clear it instead of needing 2-3 taps.
        state.health[id] = "Fair"
        if (debugEnabled()) log.debug "${device.displayName}: ${verifyMethod} succeeded — confirmed reachable immediately (Bridge/Panel proxy verification)"
    } else if (verifyMethod in ["refresh", "ping"]) {
        capDataH.lastPingAttempt = now()
        capDataH.pingAttempted   = true
        // v1.5.9: A generic refresh()/ping() that didn't throw is only "weak"
        // proof of reachability — Hubitat hands the command to the mesh and
        // returns success regardless of whether the device actually received
        // it, unlike a Hue Bridge/Konnected Panel round-trip above. Grant the
        // same immediate Quiet treatment, but tag it weak and start (or
        // continue) the weak-trust clock, UNLESS this device is in a
        // post-ceiling cooldown — in which case skip the grant entirely so
        // the real Poor/Offline stays visible for the guaranteed window.
        def inCooldown = capDataH.weakTrustCooldownUntil &&
                         now() < (capDataH.weakTrustCooldownUntil as Long)
        if (!inCooldown) {
            if (capDataH.pingTrustSource != "weak" || !capDataH.weakTrustFirstGranted) {
                capDataH.weakTrustFirstGranted = now()
            }
            capDataH.pingWorks       = true
            capDataH.pingTrustSource = "weak"
            capDataH.pingFailed      = 0
            state.health[id] = "Fair"
            if (debugEnabled()) log.debug "${device.displayName}: ${verifyMethod} succeeded — granting provisional Quiet status pending genuine confirmation"
        } else if (debugEnabled()) {
            log.debug "${device.displayName}: ${verifyMethod} succeeded but in post-ceiling cooldown — holding at real ${currentHealth} for visibility"
        }
    } else if (verifyMethod in ["none", "virtual", "hue_no_bridge", "hue_bridge_failed",
                                 "konnected_no_panel", "konnected_panel_failed", "failed"]) {
        capDataH.pingWorks       = false
        capDataH.pingFailed      = (capDataH.pingFailed ?: 0) + 1
        capDataH.pingTrustSource = null
    }
    capMapH[capKeyH]         = capDataH
    state.deviceCapabilities = capMapH

    if (currentHealth == "Offline" &&
        isLowActivity(id as String) &&
        verifyMethod in ["none", "virtual", "hue_no_bridge", "hue_bridge_failed", "failed"]) {
        state.health[id] = "Poor"
        if (debugEnabled()) log.debug "${device.displayName}: Low activity + unverifiable — capped at Poor instead of Offline"
    }
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

        def lowActivity  = isLowActivity(device.id as String)
        def repeatDrops  = isRepeatDrops(device.id as String)
        def tagSuffix    = ""
        if (repeatDrops)       tagSuffix = " <span style='color:#f97316;font-size:10px;'>🔄 Repeat Drops</span>"
        else if (lowActivity)  tagSuffix = " <span style='color:#94a3b8;font-size:10px;'>ℹ️ Low Activity Device</span>"

        def verifyMethod = state.verifying?.get(device.id)
        if (verifyMethod == null) return "${baseDisplay}${tagSuffix}"
        switch (verifyMethod) {
            case "state_verified":         return "${baseDisplay}${tagSuffix} <span style='color:#22c55e;font-size:11px;'>✅ State verified — device active via event</span>"
            case "refresh":                return "${baseDisplay}${tagSuffix} <span style='color:#1a73e8;font-size:11px;'>🔄 Verifying... (refresh sent)</span>"
            case "ping":                   return "${baseDisplay}${tagSuffix} <span style='color:#1a73e8;font-size:11px;'>🔄 Verifying... (ping sent)</span>"
            case "hue_bridge":             return "${baseDisplay}${tagSuffix} <span style='color:#1a73e8;font-size:11px;'>🔄 Verifying... (Hue Bridge refresh sent)</span>"
            case "hue_no_bridge":          return "${baseDisplay}${tagSuffix} <span style='color:#94a3b8;font-size:11px;'>⚠ Cannot verify — add Hue Bridge to monitored devices</span>"
            case "hue_bridge_failed":      return "${baseDisplay}${tagSuffix} <span style='color:#94a3b8;font-size:11px;'>⚠ Hue Bridge refresh failed</span>"
            case "konnected_panel":        return "${baseDisplay}${tagSuffix} <span style='color:#1a73e8;font-size:11px;'>🔄 Verifying... (Konnected Panel refresh sent)</span>"
            case "konnected_no_panel":     return "${baseDisplay}${tagSuffix} <span style='color:#94a3b8;font-size:11px;'>⚠ Cannot verify — add Konnected Alarm Panel to monitored devices</span>"
            case "konnected_panel_failed": return "${baseDisplay}${tagSuffix} <span style='color:#94a3b8;font-size:11px;'>⚠ Konnected Panel refresh failed</span>"
            case "virtual":                return "${baseDisplay}${tagSuffix} <span style='color:#94a3b8;font-size:11px;'>⚠ Cannot verify — virtual device</span>"
            case "none":                   return "${baseDisplay}${tagSuffix} <span style='color:#94a3b8;font-size:11px;'>⚠ Cannot verify — device does not support ping or refresh</span>"
            case "failed":                 return "${baseDisplay}${tagSuffix} <span style='color:#94a3b8;font-size:11px;'>⚠ Verification attempted but command failed</span>"
            default:                       return "${baseDisplay}${tagSuffix}"
        }
    }
    switch (h) {
        case "Excellent":
        case "Good":
        case "Fair":
            def lowActivity = isLowActivity(device.id as String)
            def extStateTag = getExtendedStateTag(device)
            def lowSuffix   = (lowActivity && !extStateTag) ? " <span style='color:#94a3b8;font-size:10px;'>ℹ️ Low Activity Device</span>" : ""
            def healthEmoji = h == "Fair" ? "🟠" : "🟢"
            // v1.5.6: Verified + Fair devices display as "Quiet" — reachable but idle
            // v1.5.9: distinguish "weak" (provisional, refresh/ping didn't throw) from
            // "confirmed" (real lastSeen advance, state event, or Hue/Konnected bridge)
            def capChk      = state.deviceCapabilities?.get(device.id as String) ?: [:]
            def isVerified  = capChk.pingWorks == true
            def isWeakTrust = capChk.pingTrustSource == "weak"
            def displayLabel = (h == "Fair" && isVerified) ? "Quiet" : h
            def quietSuffix  = (h == "Fair" && isVerified)
                ? (isWeakTrust
                    ? " <span style='color:#94a3b8;font-size:10px;'>responded to refresh — unconfirmed</span>"
                    : " <span style='color:#94a3b8;font-size:10px;'>verified reachable</span>")
                : ""
            return "${healthEmoji} ${displayLabel}${extStateTag}${lowSuffix}${quietSuffix}"
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
def safeTime(ts) {
    if (ts == null) return null
    if (ts instanceof Number) return ts.toLong()
    try {
        def t = ts?.time
        if (t instanceof Number) return t.toLong()
        def s = t?.toString() ?: ts?.toString()
        if (!s) return null
        // Handle scientific notation (e.g. "72E3" from some device firmware values)
        if (s.isNumber()) return new BigDecimal(s).toLong()
        return null
    } catch (e) {
        return null
    }
}

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
        case "#c62828": return "<span style='background:#fee2e2; color:#b91c1c; padding:3px 10px; border-radius:10px; font-weight:700; font-size:13px; display:inline-block;'>${label}</span>"
        case "#e65100": return "<span style='background:#fff3e0; color:#c2410c; padding:3px 10px; border-radius:10px; font-weight:700; font-size:13px; display:inline-block;'>${label}</span>"
        case "#1565c0": return "<span style='background:#dbeafe; color:#1d4ed8; padding:3px 10px; border-radius:10px; font-weight:700; font-size:13px; display:inline-block;'>${label}</span>"
        case "#8b5cf6": return "<span style='background:#f3e8ff; color:#7c3aed; padding:3px 10px; border-radius:10px; font-weight:700; font-size:13px; display:inline-block;'>${label}</span>"
        case "#16a34a": return "<span style='background:#dcfce7; color:#15803d; padding:3px 10px; border-radius:10px; font-weight:700; font-size:13px; display:inline-block;'>${label}</span>"
        default:        return "<span style='color:#4b5563;font-weight:600;font-size:13px;'>${label}</span>"
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
// ===================== OAUTH PORTAL HELPERS ================
// ============================================================
def getPortalRedirectHtml(delayMs, msgText) {
    return "<!DOCTYPE html><html><head><meta charset='UTF-8'>" +
           "<script>setTimeout(function(){window.location.href='dashboard?access_token=${state.accessToken}';},${delayMs});</script>" +
           "</head><body style='background:#0d0d0d;color:#fff;text-align:center;padding-top:100px;font-family:sans-serif;'>" +
           "<h3>🔄 Refreshing...</h3><p style='color:#666;'>${msgText}</p></body></html>"
}

def forceRefreshEndpoint() {
    try {
        runIn(1, "scanAllDevices", [overwrite: true])
        return render(contentType: "text/html", data: getPortalRedirectHtml(3000, "Running health scan..."), status: 200)
    } catch (e) {
        log.error "Device Health Monitor portal refresh error: ${e}"
        return render(contentType: "text/html", data: "Error: ${e.message}", status: 500)
    }
}

def updateDeviceEndpoint() {
    try {
        def dId = params?.deviceId
        if (dId) {
            if (params.loc != null) {
                setDeviceLocation(dId, params.loc)
            }
            if (params.desc != null) {
                if (params.desc == "") app.removeSetting("desc_${dId}")
                else app.updateSetting("desc_${dId}", [type: "text", value: params.desc])
            }
            if (debugEnabled()) log.debug "Portal updated device ${dId}: loc=${params.loc} desc=${params.desc}"
        }
        return render(contentType: "application/json", data: '{"success":true}', status: 200)
    } catch (e) {
        log.error "Device Health Monitor updateDevice error: ${e}"
        return render(contentType: "application/json", data: '{"error":"'+e+'"}', status: 500)
    }
}

// ============================================================
// ===================== PORTAL ENDPOINT: DATA ===============
// ============================================================
def serveDataEndpoint() {
    try {
        def devList = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }
        def roomOptions  = getRoomOptions()

        def estate = devList.collect { device ->
            def data         = state.history?.get(device.id)
            def h            = state.health?.get(device.id) ?: "Pending"
            def protocol     = getProtocol(device)
            def snoozed      = isDeviceSnoozed(device.id as String)
            def lastSeenMs   = data?.lastSeen ? (data.lastSeen as Long) : 0
            def lastSeenStr  = lastSeenMs ? formatTimeAgo(lastSeenMs) : "Never"
            def avgIntStr    = data?.userInterval ? formatInterval(data.userInterval) + " (manual)" :
                               data?.avgInterval  ? formatInterval(data.avgInterval) : "Learning..."
            def stateInfo    = getCurrentStateDisplay(device)
            def stateLabel   = stateInfo?.label ?: "—"
            def stateColor   = stateInfo?.color ?: "#c0c4cc"
            def isAlert      = stateInfo?.isAlert ?: false
            def tracked      = state.stateHistory?.get(device.id as String)
            def lastChanged  = tracked?.lastChanged ? formatTimeAgo(tracked.lastChanged as Long) : "—"
            def loc          = getDeviceLocation(device.id)
            def desc         = settings["desc_${device.id}"] ?: ""
            def verifyMethod = state.verifying?.get(device.id)
            def hasOverride  = settings["protocolOverride_${device.id}"] &&
                               settings["protocolOverride_${device.id}"] != "Auto-detect"
            def trustSource  = state.deviceCapabilities?.get(device.id as String)?.pingTrustSource ?: ""

            [
                id:              device.id,
                name:            device.displayName,
                health:          h,
                protocol:        protocol,
                protocolColor:   getProtocolColor(protocol),
                hasOverride:     hasOverride,
                snoozed:         snoozed,
                snoozeRemaining: snoozed ? formatSnoozeRemaining(device.id as String) : "",
                lastSeen:        lastSeenStr,
                lastSeenMs:      lastSeenMs,
                avgInterval:     avgIntStr,
                stateLabel:      stateLabel,
                stateColor:      stateColor,
                stateAlert:      isAlert,
                lastChanged:     lastChanged,
                location:        loc,
                description:     desc,
                pingStatus:      getPingStatus(device.id),
                pingTrustSource: trustSource,
                repeatDrops:     isRepeatDrops(device.id as String),
                extStateTag:     getExtendedStateTag(device),
                verifyMethod:    verifyMethod ?: "",
                lowActivity:     isLowActivity(device.id as String)
            ]
        }

        def healthOrder = ["Offline": 1, "Poor": 2, "Fair": 3, "Good": 4, "Excellent": 5, "Pending": 6]
        estate = estate.sort { a, b ->
            def pA = healthOrder[a.health] ?: 6
            def pB = healthOrder[b.health] ?: 6
            if (pA != pB) return pA <=> pB
            return a.name <=> b.name
        }

        def payload = [
            token:      state.accessToken,
            lastScan:   new Date().format("MM/dd/yyyy h:mm a", location.timeZone),
            locations:  roomOptions,
            isScanning: state.isScanning ?: false,
            estate:     estate
        ]

        return render(contentType: "application/json", data: groovy.json.JsonOutput.toJson(payload), status: 200)

    } catch (e) {
        log.error "Device Health Monitor data endpoint error: ${e}"
        return render(contentType: "application/json", data: '{"error":"Data unavailable"}', status: 500)
    }
}

// ============================================================
// ===================== PORTAL ENDPOINT: DASHBOARD (SPA) ====
// ============================================================
def serveDashboardPage() {
    try {
        def css = """
body{font-family:-apple-system,BlinkMacSystemFont,sans-serif;padding:20px;background:#0d0d0d;color:#e0e0e0;margin:0}
.container{max-width:860px;margin:0 auto;background:#151515;padding:25px;border-radius:12px;box-sizing:border-box}
.loader{border:4px solid #333;border-top:4px solid #3b82f6;border-radius:50%;width:40px;height:40px;animation:spin 1s linear infinite;margin:30px auto}
@keyframes spin{0%{transform:rotate(0deg)}100%{transform:rotate(360deg)}}
h2{text-align:center;color:#fff;margin:0 0 4px 0}
.subtitle{text-align:center;font-size:12px;color:#666;margin-bottom:20px}
.summary-box{display:flex;flex-wrap:wrap;gap:10px;margin-bottom:20px}
.summary-card{flex:1;min-width:90px;box-sizing:border-box;background:#1e1e1e;padding:12px;border-radius:8px;text-align:center;border-bottom:3px solid #333}
.summary-card b{display:block;font-size:22px;color:#fff;margin-bottom:4px}
.summary-card span{font-size:11px;color:#aaa;text-transform:uppercase}
.top-bar{display:flex;gap:10px;margin-bottom:20px;flex-wrap:wrap}
.btn{flex:1;background:#1f618d;color:#fff;border:none;padding:13px 16px;border-radius:8px;text-align:center;text-decoration:none;font-weight:600;cursor:pointer;font-size:13px;display:block}
.btn:hover{background:#1a5276}
details{margin-bottom:12px}
summary{padding:10px 14px;background:#1c1c1c;border-radius:6px;border-left:4px solid #3b82f6;cursor:pointer;color:#fff;font-weight:bold;font-size:15px;list-style:none}
summary:hover{background:#252525}
.cat-count{float:right;font-size:12px;color:#888;margin-top:2px}
.dev-card{background:#222;padding:12px 14px;border-radius:8px;margin-bottom:10px;border-left:4px solid #333}
.health-Offline{border-left-color:#991b1b;background:linear-gradient(90deg,rgba(153,27,27,.12) 0%,#222 30%)}
.health-Poor{border-left-color:#ef4444;background:linear-gradient(90deg,rgba(239,68,68,.1) 0%,#222 30%)}
.health-Fair{border-left-color:#f97316;background:linear-gradient(90deg,rgba(249,115,22,.1) 0%,#222 30%)}
.health-Good{border-left-color:#22c55e}
.health-Excellent{border-left-color:#22c55e}
.health-Pending{border-left-color:#94a3b8}
.health-Snoozed{border-left-color:#8b5cf6;opacity:0.7}
.row{display:flex;align-items:flex-start;gap:10px}
.dev-name{font-size:14px;font-weight:bold;color:#fff}
.dev-meta{font-size:11px;color:#888;margin-top:3px}
.dev-state{display:inline-block;padding:2px 8px;border-radius:8px;font-size:11px;font-weight:700;margin-top:4px}
.proto-tag{display:inline-block;font-size:10px;font-weight:700;padding:1px 6px;border-radius:4px;margin-left:6px;vertical-align:middle}
.modal-overlay{display:none;position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,.75);z-index:1000;align-items:center;justify-content:center;padding:20px;box-sizing:border-box}
.modal{background:#1a1a1a;border:1px solid #333;border-radius:12px;padding:22px;width:100%;max-width:500px;max-height:90vh;overflow-y:auto;position:relative}
.modal-title{font-size:17px;font-weight:bold;color:#fff;margin-bottom:15px;border-bottom:1px solid #333;padding-bottom:10px}
.modal-row{display:flex;justify-content:space-between;padding:8px 0;border-bottom:1px solid #1e1e1e;font-size:13px}
.modal-row span:first-child{color:#888}
.modal-row span:last-child{color:#fff;text-align:right;max-width:60%}
.modal-input{width:100%;padding:7px;border-radius:4px;border:1px solid #444;background:#222;color:#fff;font-size:13px;box-sizing:border-box;margin-top:3px}
.modal-input:focus{outline:none;border-color:#3b82f6}
.modal-btns{display:flex;gap:10px;margin-top:15px}
.modal-save{flex:1;padding:11px;background:#22c55e;color:#fff;border:none;border-radius:6px;font-weight:bold;cursor:pointer}
.modal-save:hover{background:#16a34a}
.modal-cancel{flex:1;padding:11px;background:#2c3e50;color:#fff;border:none;border-radius:6px;font-weight:bold;cursor:pointer}
.modal-cancel:hover{background:#34495e}
.scanning-badge{background:#1f618d;color:#fff;font-size:11px;padding:3px 8px;border-radius:10px;margin-left:8px}
select.top-select{background:#2c3e50;color:#fff;border:none;border-radius:8px;padding:0 12px;font-size:13px;font-weight:600;cursor:pointer;height:44px}
@media(max-width:600px){body{padding:10px}.container{padding:14px}.row{flex-wrap:wrap}.summary-card{min-width:40%}}
"""

        def js = """
const ACCESS_TOKEN = '${state.accessToken}';
let db = null;
let groupMode = 'protocol';

function load() {
    document.getElementById('app').innerHTML = "<div class='loader'></div><p style='text-align:center;color:#666;margin-top:10px;'>Loading estate data...</p>";
    fetch('data?access_token=' + ACCESS_TOKEN)
        .then(r => r.json())
        .then(data => { db = data; render(); })
        .catch(err => {
            document.getElementById('app').innerHTML = "<p style='color:#ef4444;text-align:center;padding:40px;'>Connection error — hub may be busy.<br><small>" + err + "</small></p>";
        });
}

function silentRefresh() {
    if (document.getElementById('editModal').style.display !== 'flex') {
        fetch('data?access_token=' + ACCESS_TOKEN)
            .then(r => r.json())
            .then(data => { db = data; render(); });
    }
}

function healthIcon(h) {
    return {Offline:'💀',Poor:'🔴',Fair:'🟠',Good:'🟢',Excellent:'🟢',Pending:'⏳'}[h] || '⏳';
}

function healthLabel(dev) {
    let h = dev.health;
    let icon = healthIcon(h);
    if (dev.snoozed) return "😴 Snoozed (" + dev.snoozeRemaining + ")";
    if (h === 'Pending') return "⏳ Pending";

    let suffix = '';
    if (dev.repeatDrops) suffix = ' <span style="color:#f97316;font-size:10px;">🔄 Repeat Drops</span>';
    else if (dev.lowActivity && (h === 'Fair' || h === 'Poor' || h === 'Offline')) suffix = ' <span style="color:#94a3b8;font-size:10px;">ℹ️ Low Activity</span>';

    if (h === 'Poor' || h === 'Offline') {
        let vm = dev.verifyMethod;
        let vSuffix = '';
        if (vm === 'state_verified')               vSuffix = ' <span style="color:#22c55e;font-size:10px;">✅ State verified</span>';
        else if (vm === 'refresh' || vm === 'ping') vSuffix = ' <span style="color:#1a73e8;font-size:10px;">🔄 Verifying...</span>';
        else if (vm === 'none' || vm === 'virtual') vSuffix = ' <span style="color:#94a3b8;font-size:10px;">⚠ Cannot verify</span>';
        else if (vm === 'hue_bridge')              vSuffix = ' <span style="color:#1a73e8;font-size:10px;">🔄 Hue Bridge refresh sent</span>';
        else if (vm === 'hue_no_bridge')           vSuffix = ' <span style="color:#94a3b8;font-size:10px;">⚠ Add Hue Bridge</span>';
        else if (vm === 'konnected_panel')         vSuffix = ' <span style="color:#1a73e8;font-size:10px;">🔄 Konnected Panel refresh sent</span>';
        else if (vm === 'konnected_no_panel')      vSuffix = ' <span style="color:#94a3b8;font-size:10px;">⚠ Add Konnected Panel</span>';
        else if (vm === 'konnected_panel_failed')  vSuffix = ' <span style="color:#94a3b8;font-size:10px;">⚠ Konnected Panel refresh failed</span>';
        return icon + ' ' + h + suffix + vSuffix;
    }

    // v1.5.9: Fair + verified devices show as "Quiet" here too, matching the
    // Hubitat app page — previously the portal only used this distinction for
    // summary counts/filtering (isQuiet), never in the label text itself.
    if (h === 'Fair' && dev.pingStatus === 'verified') {
        let quietSuffix = dev.pingTrustSource === 'weak'
            ? ' <span style="color:#94a3b8;font-size:10px;">responded to refresh — unconfirmed</span>'
            : ' <span style="color:#94a3b8;font-size:10px;">verified reachable</span>';
        return icon + ' Quiet' + suffix + quietSuffix;
    }
    return icon + ' ' + h + suffix;
}

function stateTag(dev) {
    if (dev.stateLabel === '—') return '';
    let bg = dev.stateColor === '#c62828' ? '#fee2e2;color:#b91c1c' :
             dev.stateColor === '#e65100' ? '#fff3e0;color:#c2410c' :
             dev.stateColor === '#1565c0' ? '#dbeafe;color:#1d4ed8' :
             dev.stateColor === '#8b5cf6' ? '#f3e8ff;color:#7c3aed' :
             dev.stateColor === '#16a34a' ? '#dcfce7;color:#15803d' : 'transparent;color:#4b5563';
    return "<span class='dev-state' style='background:#" + bg + ";'>" + dev.stateLabel + "</span>";
}

function protoTag(dev) {
    return "<span class='proto-tag' style='background:" + dev.protocolColor + "22;color:" + dev.protocolColor + ";'>" +
           dev.protocol + (dev.hasOverride ? ' <span style=\\"color:#94a3b8\\">(override)</span>' : '') + "</span>";
}

function card(dev) {
    let locDesc = [];
    if (dev.location) locDesc.push('🏷️ ' + dev.location);
    let pingTag = dev.pingStatus === 'verified' ? (dev.pingTrustSource === 'weak'
                    ? "<span style='color:#0ea5e9;font-size:10px;'>🔄 Verified (auto)</span>"
                    : "<span style='color:#22c55e;font-size:10px;'>✅ Verified</span>") :
                  dev.pingStatus === 'unverifiable' ? "<span style='color:#94a3b8;font-size:10px;'>⚠ Cannot verify</span>" :
                  dev.pingStatus === 'declared' ? "<span style='color:#f97316;font-size:10px;'>🔄 Verifiable</span>" : '';
    if (dev.description) locDesc.push('📝 ' + dev.description);
    let locHtml = locDesc.length ? "<div style='font-size:11px;color:#888;margin-top:2px;'>" + locDesc.join(' &nbsp;|&nbsp; ') + "</div>" : '';

    return "<div class='dev-card health-" + (dev.snoozed ? 'Snoozed' : dev.health) + "' onclick='openEdit(this)' style='cursor:pointer;' " +
           "data-id='" + dev.id + "' data-name='" + dev.name.replace(/'/g, "&#39;") + "' " +
           "data-loc='" + (dev.location || '').replace(/'/g, "&#39;") + "' " +
           "data-desc='" + (dev.description || '').replace(/'/g, "&#39;") + "' " +
           "data-health='" + dev.health + "' data-protocol='" + dev.protocol + "' " +
           "data-lastseen='" + dev.lastSeen + "' data-avginterval='" + dev.avgInterval + "'>" +
           "<div class='row'><div style='flex:1;min-width:0;'>" +
           "<div class='dev-name'>" + dev.name + protoTag(dev) + "</div>" +
           locHtml +
           "<div class='dev-health'>" + healthLabel(dev) + "</div>" +
           stateTag(dev) +
           (pingTag ? "<div style='margin-top:3px;'>" + pingTag + "</div>" : "") +
           "</div><div style='text-align:right;flex-shrink:0;font-size:11px;color:#666;min-width:80px;'>" +
           "<div>Last: " + dev.lastSeen + "</div>" +
           "<div>Avg: " + dev.avgInterval + "</div>" +
           (dev.lastChanged !== '—' ? "<div>Changed: " + dev.lastChanged + "</div>" : '') +
           "</div></div></div>";
}

function render() {
    if (!db) return;
    let estate   = db.estate || [];
    let isQuiet  = d => d.health === 'Fair' && d.pingStatus === 'verified';
    let offline  = estate.filter(d => d.health === 'Offline' && !d.snoozed).length;
    let poor     = estate.filter(d => d.health === 'Poor'    && !d.snoozed).length;
    let fair     = estate.filter(d => d.health === 'Fair'    && !d.snoozed && !isQuiet(d)).length;
    let healthy  = estate.filter(d => (['Good','Excellent'].includes(d.health) || isQuiet(d)) && !d.snoozed).length;
    let total    = estate.length;
    let scanning = db.isScanning ? "<span class='scanning-badge'>🔄 Scanning</span>" : "";

    let html = "<h2>📡 Device Health</h2>";
    html += "<p class='subtitle'>Last scan: " + db.lastScan + scanning + "</p>";

    html += "<div class='summary-box'>";
    html += "<div class='summary-card' style='border-bottom-color:#991b1b;'><b>" + offline + "</b><span>Offline</span></div>";
    html += "<div class='summary-card' style='border-bottom-color:#ef4444;'><b>" + poor + "</b><span>Poor</span></div>";
    html += "<div class='summary-card' style='border-bottom-color:#f97316;'><b>" + fair + "</b><span>Fair</span></div>";
    html += "<div class='summary-card' style='border-bottom-color:#22c55e;'><b>" + healthy + "</b><span>Healthy</span></div>";
    html += "<div class='summary-card' style='border-bottom-color:#3b82f6;'><b>" + total + "</b><span>Total</span></div>";
    html += "</div>";

    html += "<div class='top-bar'>";
    html += "<a href='refresh?access_token=" + ACCESS_TOKEN + "' class='btn'>🔄 Force Scan</a>";
    html += "<select class='top-select' onchange='changeGroup(this.value)'>";
    html += "<option value='protocol' " + (groupMode==='protocol'?'selected':'') + ">📡 By Protocol</option>";
    html += "<option value='health'   " + (groupMode==='health'  ?'selected':'') + ">❤️ By Health</option>";
    html += "<option value='location' " + (groupMode==='location'?'selected':'') + ">🏷️ By Location</option>";
    html += "</select>";
    html += "</div>";

    let issues = estate.filter(d => ['Offline','Poor','Fair'].includes(d.health) && !isQuiet(d) && !d.snoozed);
    if (issues.length) {
        html += "<details open><summary style='border-left-color:#ef4444;'>⚠️ Active Issues <span class='cat-count'>" + issues.length + " devices</span></summary><div style='padding-top:10px;'>";
        issues.forEach(d => html += card(d));
        html += "</div></details>";
    }

    let snoozed = estate.filter(d => d.snoozed);
    if (snoozed.length) {
        html += "<details><summary style='border-left-color:#8b5cf6;'>😴 Snoozed <span class='cat-count'>" + snoozed.length + "</span></summary><div style='padding-top:10px;'>";
        snoozed.forEach(d => html += card(d));
        html += "</div></details>";
    }

    let healthy_devs = estate.filter(d => (!['Offline','Poor','Fair'].includes(d.health) || isQuiet(d)) && !d.snoozed);
    let groups = {};
    healthy_devs.forEach(d => {
        let keys = [];
        if (groupMode === 'protocol')        keys = [d.protocol];
        else if (groupMode === 'health')     keys = [d.health];
        else if (groupMode === 'location')   keys = [d.location || 'Unassigned'];
        keys.forEach(k => {
            if (!groups[k]) groups[k] = [];
            groups[k].push(d);
        });
    });
    Object.keys(groups).sort().forEach(gName => {
        html += "<details><summary style='border-left-color:#3b82f6;'>" + gName + " <span class='cat-count'>" + groups[gName].length + " devices</span></summary><div style='padding-top:10px;'>";
        groups[gName].forEach(d => html += card(d));
        html += "</div></details>";
    });

    document.getElementById('app').innerHTML = html;
}

function changeGroup(mode) { groupMode = mode; render(); }

function openEdit(card) {
    document.getElementById('editDeviceId').value = card.getAttribute('data-id');
    document.getElementById('editDeviceName').innerText = card.getAttribute('data-name');
    document.getElementById('modalHealth').innerText    = card.getAttribute('data-health') || '—';
    document.getElementById('modalProtocol').innerText  = card.getAttribute('data-protocol') || '—';
    document.getElementById('modalLastSeen').innerText  = card.getAttribute('data-lastseen') || '—';
    document.getElementById('modalAvgInt').innerText    = card.getAttribute('data-avginterval') || '—';

    let locSel = document.getElementById('editLoc');
    locSel.innerHTML = '<option value="">— Unassigned —</option>';
    (db.locations || []).forEach(l => {
        let opt = new Option(l, l);
        if (l === card.getAttribute('data-loc')) opt.selected = true;
        locSel.add(opt);
    });
    let current = card.getAttribute('data-loc');
    if (current && !Array.from(locSel.options).some(o => o.value === current)) {
        let newOpt = new Option(current, current, false, true);
        locSel.add(newOpt);
        locSel.value = current;
    }

    document.getElementById('editDesc').value = card.getAttribute('data-desc') || '';
    document.getElementById('editModal').style.display = 'flex';
}

function saveEdit() {
    let btn = document.querySelector('.modal-save');
    btn.innerText = '⏳ Saving...'; btn.disabled = true;
    let dId  = document.getElementById('editDeviceId').value;
    let loc  = document.getElementById('editLoc').value;
    let desc = document.getElementById('editDesc').value;
    fetch('updateDevice?deviceId=' + dId + '&loc=' + encodeURIComponent(loc) + '&desc=' + encodeURIComponent(desc) + '&access_token=' + ACCESS_TOKEN)
        .then(r => r.json())
        .then(() => {
            btn.innerText = '💾 Save'; btn.disabled = false;
            let dev = db.estate.find(d => d.id == dId);
            if (dev) { dev.location = loc; dev.description = desc; }
            closeEdit(); render();
        })
        .catch(() => { btn.innerText = '💾 Save'; btn.disabled = false; alert('Save failed.'); });
}

function closeEdit() { document.getElementById('editModal').style.display = 'none'; }
document.addEventListener('keydown', e => { if (e.key === 'Escape') closeEdit(); });
document.addEventListener('DOMContentLoaded', load);
setInterval(silentRefresh, 60000);
"""

        def html = "<!DOCTYPE html><html><head>" +
                   "<meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                   "<title>Device Health Portal</title>" +
                   "<style>${css}</style></head><body>" +
                   "<div class='container'><div id='app'></div></div>" +
                   "<div id='editModal' class='modal-overlay' onclick='closeEdit()'>" +
                   "<div class='modal' onclick='event.stopPropagation()'>" +
                   "<div class='modal-title'>📡 <span id='editDeviceName'></span></div>" +
                   "<input type='hidden' id='editDeviceId'>" +
                   "<div style='background:#1a1a1a;border-radius:6px;padding:10px;margin-bottom:14px;font-size:12px;'>" +
                   "<div class='modal-row' style='border-bottom:1px solid #222;'><span>Health</span><span id='modalHealth'>—</span></div>" +
                   "<div class='modal-row' style='border-bottom:1px solid #222;'><span>Protocol</span><span id='modalProtocol'>—</span></div>" +
                   "<div class='modal-row' style='border-bottom:1px solid #222;'><span>Last Check-in</span><span id='modalLastSeen'>—</span></div>" +
                   "<div class='modal-row'><span>Avg Check-in</span><span id='modalAvgInt'>—</span></div>" +
                   "</div>" +
                   "<div class='modal-row'><span>Location</span><span style='width:60%;'><select id='editLoc' class='modal-input'></select></span></div>" +
                   "<div class='modal-row'><span>Description</span><span style='width:60%;'><input type='text' id='editDesc' class='modal-input' placeholder='Optional notes...'></span></div>" +
                   "<div class='modal-btns'><button class='modal-save' onclick='saveEdit()'>💾 Save</button><button class='modal-cancel' onclick='closeEdit()'>Cancel</button></div>" +
                   "</div></div>" +
                   "<script>${js}</script></body></html>"

        return render(contentType: "text/html", data: html, status: 200)

    } catch (Exception e) {
        log.error "Device Health Monitor portal error: ${e}"
        return render(contentType: "text/html", data: "<h3 style='color:white;font-family:sans-serif;'>Portal Error</h3><p style='color:#ccc;'>${e}</p>", status: 500)
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
            if (state.isScanning) {
                paragraph "<div style='background-color:#dbeafe; border-left:3px solid #1d4ed8; padding:6px 10px; border-radius:0; font-size:12px; color:#1d4ed8;'>🔄 Scan in progress — data updates automatically as each batch completes.</div>"
            } else {
                paragraph "<div style='background-color:#e8f0fe; border-left:3px solid #1565c0; padding:6px 10px; border-radius:0; font-size:12px; color:#1565c0;'>🔄 Data reflects the last completed scan. Tap <b>Force Scan</b> above to refresh.</div>"
            }

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
            table += "<th style='padding:4px; border:1px solid #ccc;'>Device</th>"
            table += "<th style='padding:4px; border:1px solid #ccc;'>Protocol</th>"
            table += "<th style='padding:4px; border:1px solid #ccc;'>Health</th>"
            table += "<th style='padding:4px; border:1px solid #ccc;'>State</th>"
            table += "<th style='padding:4px; border:1px solid #ccc;'>State Changed</th>"
            table += "<th style='padding:4px; border:1px solid #ccc;'>Last Check-in</th>"
            table += "<th style='padding:4px; border:1px solid #ccc;'>Avg Check-in</th>"
            table += "<th style='padding:4px; border:1px solid #ccc;'>Verification</th>"
            table += "<th style='padding:4px; border:1px solid #ccc;'>Location</th>"
            table += "</tr></thead><tbody>"

            def rowNum = 0
            devList.each { device ->
                def data        = state.history?.get(device.id)
                def protocol    = getProtocol(device)
                def snoozed     = isDeviceSnoozed(device.id as String)
                def hasOverride = settings["protocolOverride_${device.id}"] && settings["protocolOverride_${device.id}"] != "Auto-detect"
                def rowBg       = snoozed ? "#f8f8f8" : (rowNum % 2 == 0) ? "#ffffff" : "#ebebeb"
                def protocolDisplay = hasOverride ? "${protocol} <span style='color:#94a3b8;font-size:10px;'>(override)</span>" : protocol

                def lastSeenMs  = data?.lastSeen ? (data.lastSeen as Long) : 0
                def lastSeenStr = lastSeenMs ? formatTimeAgo(lastSeenMs) : "Never"
                def avgRawMin   = data?.userInterval ? (data.userInterval as Long) : data?.avgInterval ? (data.avgInterval as Long) : 999999
                def avgIntStr   = data?.userInterval ? formatInterval(data.userInterval) + " (manual)" :
                                  data?.avgInterval  ? formatInterval(data.avgInterval) : "Learning..."

                def h            = state.health?.get(device.id) ?: "Pending"
                def healthOrder  = snoozed ? 99 : (h == "Offline" ? 1 : h == "Poor" ? 2 : h == "Fair" ? 3 : h == "Good" ? 4 : h == "Excellent" ? 5 : 6)
                def stateInfo    = getCurrentStateDisplay(device)
                def stateDisplay = stateInfo ? formatStateDisplay(stateInfo) : "—"
                def stateOrderVal = stateInfo ? stateInfo.label.toLowerCase() : "zzz"
                def tracked       = state.stateHistory?.get(device.id as String)
                def lastChangedMs = tracked?.lastChanged ? (tracked.lastChanged as Long) : 0
                def lastChangedStr = lastChangedMs ? formatTimeAgo(lastChangedMs) : "—"
                def loc = getDeviceLocation(device.id) ?: "—"

                rowNum++
                def deviceLink = hubIp ? "<a href='http://${hubIp}/device/edit/${device.id}' target='_blank'>${device.displayName}</a>" : device.displayName

                table += "<tr style='background-color:${rowBg};${snoozed ? "opacity:0.6;" : ""}'>"
                table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${device.displayName.toLowerCase().trim()}'>${deviceLink}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'><span style='color:${getProtocolColor(protocol)};font-weight:bold;'>${protocolDisplay}</span></td>"
                table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${healthOrder}'>${getHealthDisplay(device)}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc; text-align:center;' data-order='${stateOrderVal}'>${stateDisplay}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${-lastChangedMs}'>${lastChangedStr}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${-lastSeenMs}'>${lastSeenStr}</td>"
                def pingDisplay = getPingStatusDisplay(device.id)
                table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${avgRawMin}'>${avgIntStr}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc; text-align:center;'>${pingDisplay}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${loc}</td>"
                table += "</tr>"
            }
            table += "</tbody></table>"

            paragraph rawHtml: true, """
${hubIp ? "<div style='background-color:#fff8e1; border-left:3px solid #e65100; padding:6px 10px; font-size:12px; color:#e65100; margin-bottom:6px;'>⚠ Device links are accessible on your local network only.</div>" : ""}
<div style='overflow-x:auto; -webkit-overflow-scrolling:touch;'>${table}</div>
<script>
\$(document).ready(function() {
    \$('#activityTable').DataTable({
        paging: false, info: false, searching: true,
        order: [[2, 'asc']],
        columnDefs: [{ type: 'num', targets: [2, 4, 5, 6] }, { type: 'string', targets: [3] }]
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
            if (!devList) { paragraph "No Hub Mesh devices found in your monitored device list."; return }

            def groups = buildHubMeshSummary()
            def hubIp  = location?.hub?.localIP ?: ""

            paragraph rawHtml: true, "<div style='background-color:#f8f0ff; border-left:3px solid #8b5cf6; padding:6px 10px; border-radius:0; font-size:12px; color:#6d28d9; margin-bottom:8px;'>ℹ️ Source hub detection is not supported on current Hubitat firmware. All Hub Mesh devices show as \"Remote Hub\" — this does not affect health monitoring.</div>"

            def bannerHtml = ""
            groups.each { srcHub, counts ->
                def worstColor = counts.offline > 0 ? "#991b1b" : counts.poor > 0 ? "#c62828" : counts.fair > 0 ? "#ea580c" : "#16a34a"
                def worstLabel = counts.offline > 0 ? "💀 Offline devices present" : counts.poor > 0 ? "🔴 Poor devices present" : counts.fair > 0 ? "🟠 Fair devices present" : "🟢 All healthy"
                bannerHtml += "<div style='background:#f0f0f0; border-left:4px solid ${worstColor}; padding:8px 10px; margin-bottom:8px; border-radius:3px;'>"
                bannerHtml += "<b>${srcHub}</b> &nbsp;·&nbsp; ${counts.total} device(s) &nbsp;·&nbsp; <span style='color:${worstColor};'>${worstLabel}</span><br><small>"
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
            table += "<td style='padding:4px; border:1px solid #ccc;'>Device</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Source Hub</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Protocol</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Health</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>State</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Last Check-in</td>"
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
                def data         = state.history?.get(device.id)
                def protocol     = getProtocol(device)
                def srcHub       = getHubMeshSourceHub(device)
                def lastSeen     = data?.lastSeen ? formatTimeAgo(data.lastSeen) : "Never"
                def rowBg        = (rowNum % 2 == 0) ? "#ffffff" : "#ebebeb"
                def stateInfo    = getCurrentStateDisplay(device)
                def stateDisplay = stateInfo ? formatStateDisplay(stateInfo) : "—"
                def deviceLink   = hubIp ? "<a href='http://${hubIp}/device/edit/${device.id}' target='_blank'>${device.displayName}</a>" : device.displayName
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
            if (hubIp) paragraph "<span style='color:#94a3b8;font-size:11px;'>⚠ Device links are accessible on your local network only.</span>"
            paragraph "<div style='overflow-x:auto; -webkit-overflow-scrolling:touch;'>${table}</div>"
        }
    }
}

// ============================================================
// ===================== PROBLEM DEVICES PAGE ================
// ============================================================
def problemDevicesPage() {
    dynamicPage(name: "problemDevicesPage", title: "Problem Devices & Verification", install: false) {

        def allDevs  = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }
        def hubIp    = location?.hub?.localIP ?: ""

        section("") {
            href(name: "toForceScanFromProblems", page: "forceScanPage", title: "🔄 Force Scan Now")
        }

        def problems = allDevs.findAll { device ->
            def h = state.health?.get(device.id) ?: "Pending"
            h in ["Offline", "Poor", "Fair"] &&
                !isQuietVerified(device.id as String) &&
                !isDeviceSnoozed(device.id as String)
        }.sort { a, b ->
            def pri = ["Offline": 1, "Poor": 2, "Fair": 3]
            def pA  = pri[state.health?.get(a.id)] ?: 4
            def pB  = pri[state.health?.get(b.id)] ?: 4
            if (pA != pB) return pA <=> pB
            return a.displayName <=> b.displayName
        }

        def unverifiable = allDevs.findAll { 
            getPingStatus(it.id) == "unverifiable" && 
            !(state.health?.get(it.id) in ["Excellent", "Good", "Pending"])
        }
        .sort { a, b -> a.displayName <=> b.displayName }

        def verified     = allDevs.findAll { getPingStatus(it.id) == "verified"  }.size()
        def declared     = allDevs.findAll { getPingStatus(it.id) == "declared"  }.size()
        def unverCount   = unverifiable.size()
        def unknownCount = allDevs.findAll { getPingStatus(it.id) == "unknown"   }.size()

        section("") {
            def offCount  = problems.count { state.health?.get(it.id) == "Offline" }
            def poorCount = problems.count { state.health?.get(it.id) == "Poor"    }
            def fairCount = problems.count { state.health?.get(it.id) == "Fair"    }

            paragraph "Active Issues: " +
                "<b><span style='color:#991b1b;'>💀 ${offCount} Offline</span></b> &nbsp;|&nbsp; " +
                "<b><span style='color:#ef4444;'>🔴 ${poorCount} Poor</span></b> &nbsp;|&nbsp; " +
                "<b><span style='color:#f97316;'>🟠 ${fairCount} Fair</span></b>" +
                "<br>Verification: " +
                "<b><span style='color:#22c55e;'>✅ ${verified} Verified</span></b> &nbsp;|&nbsp; " +
                "<b><span style='color:#f97316;'>🔄 ${declared} Declared</span></b> &nbsp;|&nbsp; " +
                "<b><span style='color:#94a3b8;'>⚠ ${unverCount} Unverifiable</span></b>"
        }

        section("<b>Active Issues</b>") {
            if (!problems) {
                paragraph "✅ No problem devices — all monitored devices are healthy."
            } else {
                def table = "<table style='width:100%; border-collapse:collapse; border:1px solid #ccc;'>"
                table += "<tr style='font-weight:bold; background-color:#f0f0f0;'>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>Device</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>Health</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>State</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>Last Check-in</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>Verification</td>"
                table += "</tr>"

                def rowNum = 0
                problems.each { device ->
                    def data         = state.history?.get(device.id)
                    def lastSeen     = data?.lastSeen ? formatTimeAgo(data.lastSeen) : "Never"
                    def stateInfo    = getCurrentStateDisplay(device)
                    def stateDisplay = stateInfo ? formatStateDisplay(stateInfo) : "—"
                    def pingDisp     = getPingStatusDisplay(device.id)
                    def loc          = getDeviceLocation(device.id)
                    def locTag       = loc ? " <span style='color:#94a3b8;font-size:10px;'>🏷️ ${loc}</span>" : ""
                    def rowBg        = (rowNum % 2 == 0) ? "#ffffff" : "#ebebeb"
                    def deviceLink   = hubIp ? "<a href='http://${hubIp}/device/edit/${device.id}' target='_blank'>${device.displayName}</a>" : device.displayName
                    rowNum++
                    table += "<tr style='background-color:${rowBg};'>"
                    table += "<td style='padding:4px; border:1px solid #ccc;'>${deviceLink}${locTag}</td>"
                    table += "<td style='padding:4px; border:1px solid #ccc;'>${getHealthDisplay(device)}</td>"
                    table += "<td style='padding:4px; border:1px solid #ccc; text-align:center;'>${stateDisplay}</td>"
                    table += "<td style='padding:4px; border:1px solid #ccc;'>${lastSeen}</td>"
                    table += "<td style='padding:4px; border:1px solid #ccc;'>${pingDisp}</td>"
                    table += "</tr>"
                }
                table += "</table>"
                if (hubIp) paragraph "<span style='color:#94a3b8;font-size:11px;'>⚠ Device links are accessible on your local network only.</span>"
                paragraph "<div style='overflow-x:auto; -webkit-overflow-scrolling:touch;'>${table}</div>"
            }
        }

        section("<b>Unverifiable Devices</b> — <span style='color:${unverCount > 0 ? "#94a3b8" : "#22c55e"};'>${unverCount} device(s)</span>", hideable: true, hidden: unverCount == 0) {
            if (unverCount == 0) {
                paragraph "✅ All devices support ping or refresh verification."
            } else {
                paragraph "<div style='background-color:#f8f8f8; border-left:3px solid #94a3b8; padding:6px 10px; font-size:12px; color:#4b5563; margin-bottom:8px;'>" +
                          "These devices cannot be pinged or refreshed. If they go Offline the app cannot confirm whether they are truly unreachable. " +
                          "Verification status resets automatically when a device recovers to Good or Excellent — so devices that were previously unverifiable will get a fresh attempt next time they drop.</div>"

                def table = "<table style='width:100%; border-collapse:collapse; border:1px solid #ccc;'>"
                table += "<tr style='font-weight:bold; background-color:#f0f0f0;'>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>Device</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>Protocol</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>Health</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>Failed Attempts</td>"
                table += "</tr>"

                def rowNum = 0
                unverifiable.each { device ->
                    def h        = state.health?.get(device.id) ?: "Pending"
                    def protocol = getProtocol(device)
                    def cap      = state.deviceCapabilities?.get(device.id as String) ?: [:]
                    def attempts = cap.pingFailed ?: 0
                    def loc      = getDeviceLocation(device.id)
                    def locTag   = loc ? " <span style='color:#94a3b8;font-size:10px;'>🏷️ ${loc}</span>" : ""
                    def rowBg    = (rowNum % 2 == 0) ? "#ffffff" : "#ebebeb"
                    def deviceLink = hubIp ? "<a href='http://${hubIp}/device/edit/${device.id}' target='_blank'>${device.displayName}</a>" : device.displayName
                    rowNum++
                    table += "<tr style='background-color:${rowBg};'>"
                    table += "<td style='padding:4px; border:1px solid #ccc;'>${deviceLink}${locTag}</td>"
                    table += "<td style='padding:4px; border:1px solid #ccc;'><span style='color:${getProtocolColor(protocol)};font-weight:bold;'>${protocol}</span></td>"
                    table += "<td style='padding:4px; border:1px solid #ccc;'>${getHealthDisplay(device)}</td>"
                    table += "<td style='padding:4px; border:1px solid #ccc; color:#94a3b8;'>${attempts}</td>"
                    table += "</tr>"
                }
                table += "</table>"
                if (hubIp) paragraph "<span style='color:#94a3b8;font-size:11px;'>⚠ Device links are accessible on your local network only.</span>"
                paragraph "<div style='overflow-x:auto; -webkit-overflow-scrolling:touch;'>${table}</div>"
            }
        }

        section("<b>Verification Summary</b>", hideable: true, hidden: true) {
            paragraph "<div style='background-color:#f8f8f8; border:1px solid #ddd; border-radius:6px; padding:10px;'>" +
                      "<b>✅ Verified (${verified})</b> — confirmed responds to ping or refresh after going Poor/Offline<br>" +
                      "<b>🔄 Declared (${declared})</b> — capability declared by driver, not yet tested under real conditions<br>" +
                      "<b>⚠ Unverifiable (${unverCount})</b> — no capability or command confirmed non-functional<br>" +
                      "<b>❓ Unknown (${unknownCount})</b> — not yet scanned<br><br>" +
                      "<i style='color:#94a3b8;font-size:11px;'>Verification status resets automatically on health recovery so devices always get a fresh attempt. " +
                      "Run Deep Verification Scan to force re-evaluation of all declared devices.</i></div>"

            if (unknownCount > 0) {
                def unknownDevs = allDevs.findAll { getPingStatus(it.id) == "unknown" }
                                         .sort { a, b -> a.displayName <=> b.displayName }
                def unknownList = unknownDevs.collect { device ->
                    def protocol = getProtocol(device)
                    "<span style='color:${getProtocolColor(protocol)};font-weight:bold;font-size:11px;'>${protocol}</span>&nbsp;${device.displayName}"
                }.join("<br>")
                paragraph "<div style='background-color:#f8f8f8; border:1px solid #ddd; border-radius:6px; padding:10px; margin-top:6px;'>" +
                          "<b style='color:#94a3b8;'>❓ Not yet scanned (${unknownCount}):</b><br>" +
                          "<span style='font-size:12px; color:#4b5563; line-height:1.8;'>${unknownList}</span><br><br>" +
                          "<i style='color:#94a3b8;font-size:11px;'>These will be classified after the next scan. Run Force Scan to update immediately.</i></div>"
            }
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
            def hasOverride = settings["protocolOverride_${device.id}"] && settings["protocolOverride_${device.id}"] != "Auto-detect"
            def rawProtocol = getRawProtocol(device)
            hasOverride || isUnresolvableProtocol(rawProtocol)
        }
        .sort { a, b -> a.displayName.trim() <=> b.displayName.trim() }

    def stateDevList = allDevices
        .findAll { device ->
            def hasOverride = settings["stateAttrOverride_${device.id}"] && settings["stateAttrOverride_${device.id}"] != "Auto-detect"
            hasOverride || shouldShowStateOverride(device)
        }
        .sort { a, b -> a.displayName.trim() <=> b.displayName.trim() }

    dynamicPage(name: "protocolOverridePage", title: "🔧 Device Overrides", install: false) {

        section("") {
            paragraph "<div style='background-color:#fdf4ff; border-left:4px solid #a855f7; padding:10px 12px; border-radius:3px;'>" +
                      "<span style='font-size:15px; font-weight:bold; color:#4a1772;'>🔀 Protocol Overrides</span><br>" +
                      "<span style='color:#475569;font-size:12px;'>Some Hub Mesh linked devices and LAN devices cannot be automatically identified. Set the correct protocol manually. Set back to <b>Auto-detect</b> to restore automatic detection.</span></div>"
        }
        if (!protocolDevList || protocolDevList.size() == 0) {
            section("") { paragraph "✅ No Hub Mesh, LAN, Virtual, or Hub Variable devices found — no protocol overrides needed." }
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
                      "<span style='color:#475569;font-size:12px;'>Pin a specific attribute per device when the app picks the wrong one to display in the Current State column.</span></div>"
        }
        if (!stateDevList || stateDevList.size() == 0) {
            section("") { paragraph "✅ No devices with overrideable state attributes found." }
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
                          options: options, defaultValue: currentOverride, required: false, width: 6
                }
            }
        }
        section("") { paragraph "Tap <b>Done</b> to save. Changes take effect immediately on the next page load." }
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

    def devList     = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }.sort { a, b -> a.displayName.trim() <=> b.displayName.trim() }
    def snoozedList = devList.findAll { isDeviceSnoozed(it.id as String) }
    def activeList  = devList.findAll { !isDeviceSnoozed(it.id as String) }

    dynamicPage(name: "snoozeManagePage", title: "😴 Manage Snoozed Devices", install: false) {
        section("<b>Snooze Devices</b>") {
            paragraph "Select devices to snooze for <b>${settings?.snoozeDurationHours ?: 24} hours</b>."
            if (activeList) {
                input "devicesToSnooze", "enum",
                      title: "Select devices to snooze:",
                      options: activeList.collectEntries { [(it.id): "${it.displayName} (${state.health?.get(it.id) ?: 'Pending'})"] }.sort { a, b -> a.value <=> b.value },
                      multiple: true, required: false
            } else {
                paragraph "All devices are currently snoozed."
            }
        }
        if (activeList) {
            section() {
                input "confirmSnooze", "bool", title: "Confirm — snooze selected devices", defaultValue: false, submitOnChange: true
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
                paragraph snoozedList.collect { device -> "😴 ${device.displayName} — ${formatSnoozeRemaining(device.id as String)}" }.join("\n")
                input "devicesToUnsnooze", "enum",
                      title: "Select devices to unsnooze early:",
                      options: snoozedList.collectEntries { [(it.id): "${it.displayName} (${formatSnoozeRemaining(it.id as String)})"] }.sort { a, b -> a.value <=> b.value },
                      multiple: true, required: false
            } else {
                paragraph "No devices are currently snoozed."
            }
        }
        if (snoozedList) {
            section() {
                input "confirmUnsnooze", "bool", title: "Confirm — unsnooze selected devices", defaultValue: false, submitOnChange: true
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
        section("<b>Scan Started</b>") {
            def devList         = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }
            def totalDevices    = devList.size()
            def chunkSize       = totalDevices > 200 ? 25 : 40
            def intervalStr     = settings?.scanInterval ?: "3"
            def intervalMinutes = (intervalStr.toFloat() * 60).toInteger()
            def minGate         = Math.min(intervalMinutes * 0.5, 30.0).toInteger()
            paragraph "✅ Scan started — ${devList.size()} device(s) processing in the background. " +
                      "Health scores and device states update progressively as batches complete.<br><br>" +
                      "<b>Note:</b> A new check-in sample is only recorded if at least <b>${minGate} minutes</b> have passed since the last recorded activity."
        }
    }
}

// ============================================================
// ===================== RESET HISTORY PAGE ==================
// ============================================================
def resetHistoryPage() {
    app.removeSetting("resetHistoryDevices")
    app.updateSetting("resetHistoryConfirm", [value: false, type: "bool"])
    def devList = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }.sort { a, b -> a.displayName.trim() <=> b.displayName.trim() }
    dynamicPage(name: "resetHistoryPage", title: "Reset Device History", install: false) {
        section("<b>Select Devices to Reset</b>") {
            if (!devList || devList.size() == 0) {
                paragraph "No devices available."
            } else {
                paragraph "Select one or more devices to reset. Their check-in history and learned baseline will be cleared."
                input "resetHistoryDevices", "enum",
                      title: "Select devices to reset",
                      options: devList.collectEntries { [(it.id): "${it.displayName} (${state.health?.get(it.id) ?: 'Pending'})"] }.sort { a, b -> a.value <=> b.value },
                      multiple: true, required: false
            }
        }
        section("<b>Confirm Reset</b>") {
            input "resetHistoryConfirm", "bool", title: "Confirm — clear history for selected devices", defaultValue: false
        }
        section() { href(name: "toResetConfirm", page: "resetHistoryConfirmPage", title: "Submit Reset") }
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

        if (!hasDevices) { section("<b>Cannot Send</b>") { paragraph "⚠️ No monitored devices are selected." }; return }
        if (!notifyOn)   { section("<b>Cannot Send</b>") { paragraph "⚠️ Notifications are turned off." };       return }
        if (!hasTargets) { section("<b>Cannot Send</b>") { paragraph "⚠️ No notification devices configured." }; return }

        section("<b>Confirm</b>") {
            paragraph "This will send a device health summary notification now."
            input "sendNowConfirm", "bool", title: "✅ Confirm — send the notification", defaultValue: false, submitOnChange: true
        }
        if (settings?.sendNowConfirm) {
            section("<b>Result</b>") {
                scheduledSummary()
                app.updateSetting("sendNowConfirm", [value: false, type: "bool"])
                def sentTo = []
                if (settings?.notifyDevices)   sentTo.addAll(settings.notifyDevices.collect { it.displayName })
                if (settings?.pushoverDevices) sentTo.addAll(settings.pushoverDevices.collect { "${it.displayName} (Pushover)" })
                paragraph sentTo ? "✅ Notification sent to:\n" + sentTo.collect { "• ${it}" }.join("\n") : "✅ Notification sent via hub push."
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
        "Excellent": [emoji: "🟢", enabled: settings?.notifyExcellent ?: false, list: []]
    ]

    devList.each { device ->
        if (!isDeviceSnoozed(device.id as String)) {
            def h = state.health?.get(device.id) ?: "Pending"
            if (sections.containsKey(h)) {
                def stateInfo = getCurrentStateDisplay(device)
                def stateStr  = stateInfo ? " [${stateInfo.label}]" : ""
                def lastStr   = state.history?.get(device.id)?.lastSeen
                    ? ", last seen ${formatTimeAgo(state.history[device.id].lastSeen)}" : ""
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
            if (data.list) { data.list.each { name -> body += "• ${name}\n" } }
            else { body += "None\n" }
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

        section("<b>🌐 Web Portal</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "The <b>Device Health Portal</b> is a browser-accessible dashboard available from any device — phone, tablet, or desktop.<br><br>" +
                      "<b>SPA Architecture:</b> The portal shell loads instantly, then fetches device data asynchronously. Even with 200+ devices the portal opens immediately.<br><br>" +
                      "<b>How to enable:</b> Go to Apps Code → Device Health Monitor → OAuth (top right) → Enable → Update. " +
                      "Then open the app and tap Done. Cloud and Local URLs appear at the top of the main page.<br><br>" +
                      "<b>What it shows:</b> All devices with health rating, protocol, current state, last check-in, avg check-in, location, and description. " +
                      "Summary cards show Offline, Poor, Fair, Healthy, and Total counts.<br><br>" +
                      "<b>Group by:</b> Toggle between By Protocol, By Health, and By Location using the dropdown on the portal.<br><br>" +
                      "<b>Edit from portal:</b> Tap any device card to update location and description without opening the Hubitat app.<br><br>" +
                      "<b>Force Scan:</b> The Force Scan button triggers an immediate batch scan from the browser.<br><br>" +
                      "<b>Auto-refresh:</b> The portal silently refreshes every 60 seconds.<br><br>" +
                      "<b>Dashboard tile:</b> Add a Link tile to your Hubitat dashboard and paste in the portal URL.</div>"
        }

        section("<b>Batch Scanning</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "Devices are scanned in batches (40 per chunk, 25 for installs over 200 devices) with a 2-second pause between batches. " +
                      "Health scores update progressively as each batch completes.<br><br>" +
                      "<b>Stuck scan protection:</b> If a scan hasn't completed within 2 minutes it is automatically reset.</div>"
        }

        section("<b>🏷️ Location Assignment</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "Assign rooms or locations to devices from the <b>🏷️ Location Assignment</b> page. " +
                      "Locations are used for the <b>Group by Location</b> view on the portal and appear on each device card.<br><br>" +
                      "Use <b>Bulk Apply</b> to assign the same location to multiple devices at once. " +
                      "Individual assignments can also be set directly from the portal edit modal.</div>"
        }

        section("<b>🔑 Health Ratings</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "<div style='overflow-x:auto;'><table style='width:100%; border-collapse: collapse;'>" +
                      "<tr style='font-weight:bold;'><td style='padding:4px 8px;'>Health</td><td style='padding:4px 8px;'>Meaning</td></tr>" +
                      "<tr><td style='padding:4px 8px;'>⏳ Pending (n/3 samples)</td><td style='padding:4px 8px;'>Learning — sample count shown inline until 3 are collected</td></tr>" +
                      "<tr><td style='padding:4px 8px;'>🟢 Excellent</td><td style='padding:4px 8px;'>Checking in within 1.5× of baseline</td></tr>" +
                      "<tr><td style='padding:4px 8px;'>🟢 Good</td><td style='padding:4px 8px;'>Checking in within 3× of baseline</td></tr>" +
                      "<tr><td style='padding:4px 8px;'>🟠 Fair</td><td style='padding:4px 8px;'>Checking in within 6× of baseline</td></tr>" +
                      "<tr><td style='padding:4px 8px;'>🟠 Quiet</td><td style='padding:4px 8px;'>Fair health but reachability has been confirmed — device is idle, not unreachable. Two flavors: <b>verified reachable</b> (a real check-in, state event, or Hue/Konnected Bridge round-trip — trusted indefinitely, re-checked every Offline Threshold) and <b>responded to refresh — unconfirmed</b> (a plain Zigbee/Z-Wave refresh/ping that didn't throw, which isn't proof the device actually received it — see Verification section below). The unconfirmed kind is capped at 2× the Offline Threshold, after which it's forced to show a real Poor/Offline for a full Offline Threshold window before it can go Quiet again — so a device that never genuinely checks in can't be masked forever.</td></tr>" +
                      "<tr><td style='padding:4px 8px;'>🔴 Poor</td><td style='padding:4px 8px;'>Checking in beyond 6× of baseline</td></tr>" +
                      "<tr><td style='padding:4px 8px;'>💀 Offline</td><td style='padding:4px 8px;'>No activity for configured threshold (default ${settings?.offlineThresholdHours ?: 168}h). Low activity unverifiable devices are capped at Poor.</td></tr>" +
                      "<tr><td style='padding:4px 8px;'>😴 Snoozed</td><td style='padding:4px 8px;'>Excluded from notifications for a set duration</td></tr>" +
                      "<tr><td style='padding:4px 8px;'>ℹ️ Low Activity</td><td style='padding:4px 8px;'>Monitored 7+ days with fewer than 3 samples — infrequently used device</td></tr>" +
                      "</table></div></div>"
        }

        section("<b>⏳ How Baselines Are Learned</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "The app learns each device's normal check-in pattern automatically — no configuration needed.<br><br>" +
                      "<b>Sample collection:</b> Each time a device checks in, the elapsed time since its last check-in is recorded as a smoothed sample.<br><br>" +
                      "<b>Pending state:</b> A device shows ⏳ Pending until 3 samples have been collected.<br><br>" +
                      "<b>Minimum gate:</b> A sample is only counted if at least half the scan interval has passed since the last recorded activity (capped at 30 minutes).<br><br>" +
                      "<b>Sample window:</b> Up to 20 samples are kept per device.</div>"
        }

        section("<b>🔄 Verification (Ping / Refresh / State)</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "When a device enters Poor or Offline the app attempts to confirm it is still reachable before firing a notification.<br><br>" +
                      "<b>Step 1 — State-change check:</b> If the device fired any state change event after its last recorded check-in and within the offline threshold window, it is marked ✅ State verified — no ping needed.<br><br>" +
                      "<b>Step 2 — Refresh / Ping:</b> If no recent state change is found, the app sends refresh() or ping() to the device directly.<br><br>" +
                      "<b>Hold-at-Fair:</b> When a pingable device enters Poor for the first time, it is held at Fair for one scan cycle while the ping is sent. If it responds it recovers on its own without ever reaching Poor. If it doesn't respond it is confirmed Poor on the next scan.<br><br>" +
                      "<b>Quiet (verified reachable):</b> Fair-health devices that have confirmed they respond to ping or refresh are shown as 🟠 Quiet instead of Fair — idle but reachable. Used for Z-Wave and LAN devices whose <code>getLastActivity()</code> does not update reliably after command-triggered state reports.<br><br>" +
                      "<b>Verification trust expiry (v1.5.7):</b> Quiet/verified status expires after your configured Offline Threshold (default 7 days). If a device goes quiet and the last successful ping is older than this window, trust is cleared and the app re-verifies from scratch — preventing dead battery devices from being permanently masked as Quiet.<br><br>" +
                      "<b>Auto-reset on recovery:</b> When a device recovers from Poor or Offline back to Good or Excellent, its verification status is automatically reset so it always gets a fresh attempt next time it drops.<br><br>" +
                      "<b>Hue devices (v1.5.8):</b> Add your Hue Bridge to monitored devices — the app refreshes the Bridge when any Hue device goes Poor or Offline. Because a Bridge poll returns each bulb's full current state regardless of whether it changed, a successful Bridge refresh now confirms the bulb as reachable immediately, instead of waiting for a Hubitat event that may never fire when the value is unchanged.<br><br>" +
                      "<b>Konnected devices (v1.5.8):</b> Add your Konnected Alarm Panel to monitored devices — child sensors are verified by refreshing the panel, with the same immediate confirmation as Hue above.<br><br>" +
                      "<hr style='background-color:#eee; height:1px; border:0; margin:10px 0;'/>" +
                      "<b>Why doesn't a plain Zigbee/Z-Wave refresh() or ping() confirm reachability the way Hue/Konnected does?</b><br>" +
                      "It's a Hubitat platform limitation, not a bug in this app. A Hue Bridge or Konnected Panel refresh is a real network round-trip — the bridge actually talks to the device over the local LAN, and the call genuinely fails (times out / connection error) if the device is truly unreachable. A plain <code>device.refresh()</code> or <code>device.ping()</code> on a Zigbee or Z-Wave device, by contrast, just hands the command to the radio mesh and returns success immediately — Hubitat does not wait for, or expose, any delivery acknowledgment from the device itself. So \"the command didn't throw an error\" is not the same thing as \"the device received it.\" For a device that's gone fully silent (dead battery, fell off the mesh, unplugged), the mesh can sometimes still accept the command without complaint, even though nothing is actually listening on the other end.<br><br>" +
                      "<b>Provisional Quiet status with a ceiling (v1.5.9):</b> Because of the above, a successful plain refresh/ping is treated as <i>provisional</i> (\"weak\") proof of reachability rather than full confirmation. It still gets the same immediate Quiet treatment as Hue/Konnected — so a device that's simply idle isn't falsely flagged — but it's capped at <b>2× your Offline Threshold</b> (default 14 days) of continuous unconfirmed trust. If a device rides on weak trust that whole time without ever once producing a genuine check-in, real state event, or other independent confirmation, trust is force-cleared and the device is guaranteed to show a real Poor/Offline status for at least <b>one full Offline Threshold</b> (default 7 days) before weak trust can be granted again. In the worst case (a device that is genuinely dead and never recovers), this repeats indefinitely — roughly 14 days masked, then 7 days correctly flagged, on and on — so a truly silent device can never be hidden from you forever, even though quiet-but-fine devices still get the breathing room they need day to day. A device that produces even one genuine confirmation at any point immediately upgrades to full (\"confirmed\") trust and the cycle stops.<br><br>" +
                      "<b>Confirmed vs. provisional, at a glance:</b> the app/portal show <code>✅ Verified</code> / \"verified reachable\" for confirmed trust, and <code>🔄 Verified (auto)</code> / \"responded to refresh — unconfirmed\" for provisional weak trust, so you can tell at a glance which devices have actually proven themselves versus which are just coasting on a command that didn't error out.</div>"
        }

        section("<b>💡 Tips for Best Results</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "• Enable OAuth in App Code to unlock the Web Portal<br>" +
                      "• Scheduled devices (lights, irrigation) self-verify via state changes — no configuration needed<br>" +
                      "• Verification status auto-resets on health recovery — no manual intervention needed for seasonal devices<br>" +
                      "• Quiet/verified trust expires after the offline threshold — dead battery devices will escalate correctly once trust expires<br>" +
                      "• Plain Zigbee/Z-Wave refresh/ping devices now also go Quiet immediately when they respond — but that trust is provisional and capped at 2× the Offline Threshold, so a device that's actually gone silent will still surface as a real Poor/Offline periodically rather than being masked forever (see App Guide → Verification)<br>" +
                      "• Low activity devices that cannot be verified will show Poor instead of Offline — this is intentional<br>" +
                      "• Assign locations in the 🏷️ Location Assignment page — enables room grouping in the portal<br>" +
                      "• Add your Hue Bridge or CoCoHue Bridge to monitored devices for Hue verification support — bulbs that sit untouched for weeks will now confirm as Quiet/Verified from the Bridge poll alone<br>" +
                      "• After updating the app, run Force Scan to immediately update all health scores</div>"
        }

        section("<b>📋 Version History</b>", hideable: true, hidden: true) {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "<b>v1.5.10</b> — Bug fix: scan pipeline stalling silently with no error logged, on installs with many devices and weeks of history<br>" +
                      "<span style='color:#475569;font-size:12px;'>Groovy's BigDecimal division and multiplication don't auto-round — and since each check-in sample is smoothed from the previous sample recursively (<code>smoothed = alpha * elapsed + (1-alpha) * prevSmooth</code>), decimal precision compounded every single scan with nothing ever resetting it. After weeks of scans this bloated <code>state.history</code> samples from a few characters each to 40+ digit BigDecimals, ballooning total app state size (671KB observed across 142 devices, 94% of it in <code>history</code> alone) to the point where the scan pipeline could stall before <code>processScanChunk()</code> ever ran — with no error logged, since the failure occurred at the state-persistence layer rather than as a caught exception. Elapsed and smoothed values are now rounded to 2 decimal places at the point of computation, and a one-time migration on update rounds all existing stored samples and <code>avgInterval</code> values back down. No action needed beyond opening the app and tapping Done once after updating — the cleanup runs automatically on initialize, and Force Scan should complete normally afterward.</span><br><br>" +
                      "<b>v1.5.9</b> — Bug fix: quiet-but-fine Z-Wave/Zigbee devices stuck cycling Poor/Offline forever, with no bounded way to confirm a real outage<br>" +
                      "<span style='color:#475569;font-size:12px;'>Generic <code>device.refresh()</code>/<code>device.ping()</code> only proves the mesh accepted the command — Hubitat does not wait for or expose any delivery acknowledgment from the device, unlike a Hue Bridge or Konnected Panel refresh, which is a real network round-trip. Previously this meant a quiet-but-reachable Z-Wave/Zigbee switch (one that rarely changes state, so <code>getLastActivity()</code> never advances after a refresh) had no path to Quiet status at all — it would cycle Poor → Offline forever even though refresh kept succeeding every scan. Generic refresh/ping success now gets the same immediate Fair-cap (\"Quiet\") treatment Hue/Konnected already had, but tagged as <i>provisional (\"weak\")</i> trust rather than full confirmation, since a clean refresh call still isn't proof of actual delivery. Weak trust is capped at 2× your Offline Threshold (default 14 days) of continuous unconfirmed riding; once exceeded, trust is force-cleared and the device is guaranteed to show a real Poor/Offline for at least one full Offline Threshold (default 7 days) before weak trust can be granted again — so a device that never once genuinely checks in can't be hidden from you forever, while devices that are simply idle still get the same breathing room as before. Any genuine confirmation (a real check-in, a real state-change event, or a Hue/Konnected bridge round-trip) immediately upgrades a device to full (\"confirmed\") trust, which has no ceiling — it's already covered by the existing v1.5.7 periodic re-verification. The app page, Activity Summary, Problem Devices page, and web portal all now show a distinct badge/label for provisional (\"🔄 Verified (auto)\" / \"responded to refresh — unconfirmed\") vs. confirmed (\"✅ Verified\" / \"verified reachable\") status, so it's clear at a glance which devices have actually proven themselves. Also fixed: the web portal's per-device label never showed \"Quiet\" for Fair+verified devices — it only used that distinction for the summary counts and Active Issues filtering — so the portal card text now matches the Hubitat app page. Also fixed: the auto-reset-on-recovery block only wrote capability data back when clearing a failed ping status, silently leaving stale weak-trust timestamps in place on every other recovery.</span><br><br>" +
                      "<b>v1.5.8</b> — Bug fix: Hue/Konnected bulbs and sensors that sit unused for weeks stuck at Offline/Verifiable<br>" +
                      "<span style='color:#475569;font-size:12px;'>Hue and Konnected verification works by refreshing the Bridge/Panel, not the device itself. A Bridge poll returns the full current state of every bulb whether or not it changed — but Hubitat's CoCoHue integration only emits a new event when a value actually changes. A bulb that's been off and untouched for weeks therefore never produced a fresh timestamp, so <code>pingWorks</code> never flipped to <code>true</code> even though the Bridge refresh itself was succeeding every time. A successful Bridge/Panel refresh is now treated as immediate proof of reachability — it stamps <code>pingWorks=true</code> directly instead of waiting for an event that may never come. <code>lastSeen</code> and learned baseline intervals are deliberately left untouched so this doesn't get recorded as a real check-in sample. The display now also updates within the same scan rather than the next one, and the old every-other-scan verification throttle was removed — so a single Force Scan fully resolves a Hue/Konnected device to ✅ Verified / 🟠 Quiet instead of needing 2-3 taps. Devices on the plain refresh/ping path still depend on the device itself responding, so those are unaffected by this change. Also fixed: Konnected devices that failed verification (no panel found, or panel refresh failed) weren't being marked Unverifiable — they now are. Active Issues (both the app page and the web portal) no longer lists Quiet/verified devices as problems — a confirmed-reachable idle device isn't an issue, so it's now grouped with healthy devices instead, and the Fair count in the summary no longer includes it.</span><br><br>" +
                      "<b>v1.5.7</b> — Bug fix: Stale ping verification masking dead battery devices<br>" +
                      "<span style='color:#475569;font-size:12px;'>In v1.5.6 the \"Quiet verified reachable\" label was introduced but <code>pingWorks=true</code> had no expiry. A device with a dead battery (e.g. a button or sensor) could be permanently held at Quiet and never escalate to Poor or Offline. Verification trust now expires after your configured Offline Threshold. When trust expires the app clears <code>pingWorks</code> and the fairHold gate gets a fresh re-verification attempt before escalating to Poor then Offline. No action needed — updates automatically on next scan.</span><br><br>" +
                      "<b>v1.5.6</b> — Added \"Quiet verified reachable\" display for Fair+verified devices. Added <code>lastKnownStateDate</code> tracking so verified refresh responses advance <code>lastSeen</code> even when <code>getLastActivity()</code> does not update (common in Z-Wave).<br><br>" +
                      "<b>v1.5.5</b> — Verified devices capped at Fair to prevent false Poor/Offline on Z-Wave devices whose last activity timestamp does not update after command-triggered state reports.<br><br>" +
                      "<b>v1.5.3</b> — Protocol-aware baseline floors (LAN/Hub Mesh 8h, Matter 2h, Virtual 24h). Loosened health ratio thresholds. Pingable hold-at-Fair gate.<br><br>" +
                      "<b>v1.5.2</b> — Verification status auto-resets on health recovery. Hub Mesh overview page added.<br><br>" +
                      "<b>v1.5.1</b> — State-change verification: devices that fire a state event are marked \"State verified\" without needing a ping.<br><br>" +
                      "<b>v1.5.0</b> — Batch scanning, stuck-scan watchdog, deep verification scan, location assignment, SPA web portal with OAuth." +
                      "</div>"
        }
    }
}
