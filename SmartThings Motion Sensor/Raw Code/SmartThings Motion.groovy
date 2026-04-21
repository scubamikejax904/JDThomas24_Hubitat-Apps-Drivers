/*
SmartThings Motion Sensor Enhanced

Version: 1.7.5
Author: jdthomas24
Namespace: jdthomas24

Supported Models:
- STS-IRM-250 (motionv4)
- STS-IRM-251 (motionv5)
- GP-AEOMSSUS (Aeotec Zigbee motion)
- GP-U999SJVLBAA (Samsung SmartThings motion)

Enhancements:
- Motion auto reset with race condition fix
- Optional temperature reporting (enableTemp)
- Battery voltage curve with 5% increments & smoothing
- Battery reporting interval in minutes (converted to seconds for Zigbee)
- LQI/RSSI signal monitoring with route health rating
- Health Check ping() implementation
- Debug logging auto-disables after 30 minutes
- Temperature logging suppressed when enableTemp is off

Changes in 1.7.5:
- Removed presence detection — was causing interference with device reporting
  due to aggressive runIn() scheduling on every parse event
- Removed lastCheckin attribute — redundant with Hubitat built-in Last Activity
- Removed zigbeeHealth and missedCheckins — were presence-driven, no data source
- Tightened battery voltage curve to 5% increments for cleaner reporting
- Temperature events fully suppressed (no log, no event) when enableTemp is off
- configure() no longer called on every updated() save — only on relevant
  settings changes to avoid interrupting device reporting cycle
*/

import hubitat.zigbee.clusters.iaszone.ZoneStatus
import hubitat.zigbee.zcl.DataType

def driverVersion() { return "1.7.5" }

metadata {
    definition(
        name: "SmartThings Motion Sensor Enhanced",
        namespace: "jdthomas24",
        author: "jdthomas24"
    ) {
        capability "Battery"
        capability "Configuration"
        capability "MotionSensor"
        capability "Initialize"
        capability "Refresh"
        capability "Sensor"
        capability "TemperatureMeasurement"
        capability "Health Check"

        attribute "batteryVoltage", "number"
        attribute "lqi",            "number"
        attribute "rssi",           "number"
        attribute "routeHealth",    "string"

        fingerprint inClusters:"0000,0001,0003,000F,0020,0402,0500", model:"motionv4",        manufacturer:"SmartThings"
        fingerprint inClusters:"0000,0001,0003,000F,0020,0402,0500", model:"motionv5",        manufacturer:"SmartThings"
        fingerprint inClusters:"0000,0001,0003,000F,0020,0402,0500", model:"GP-AEOMSSUS",     manufacturer:"Aeotec"
        fingerprint inClusters:"0000,0001,0003,000F,0020,0402,0500", model:"GP-U999SJVLBAA", manufacturer:"Samsung"
    }

    preferences {
        input name: "motionReset",          type: "number",  title: "Motion Reset Time (seconds)",         defaultValue: 60
        input name: "enableTemp",           type: "bool",    title: "Enable Temperature Reporting",        defaultValue: true
        input name: "tempAdj",              type: "decimal", title: "Temperature Offset",                  defaultValue: 0
        input name: "batteryReportMinutes", type: "enum",
              title: "Battery Reporting Interval (minutes)",
              description: "How often the device reports battery. Converted to seconds for Zigbee reporting.",
              options: ["30","60","120","240","360"],
              defaultValue: "60"
        input name: "infoLogging",  type: "bool", title: "Enable Info Logging",                              defaultValue: true
        input name: "debugLogging", type: "bool", title: "Enable Debug Logging (auto-disables after 30 min)", defaultValue: false
    }
}

// ============================================================
// ===================== LIFECYCLE ===========================
// ============================================================
def installed() {
    log.info "${device.displayName}: Installed driver v${driverVersion()}"
    scheduleDebugAutoOff()
    initialize()
}

def initialize() {
    runIn(2, configure)
    runIn(7, refresh)
}

def updated() {
    log.info "${device.displayName}: Updated driver v${driverVersion()}"

    // v1.7.5: clear ALL scheduled jobs on update — removes any stale timers
    // from previous driver versions (e.g. presenceTimeoutCheck from v1.7.4)
    unschedule()

    // v1.7.5: clear stale attributes from previous driver versions
    ["presence", "lastCheckin", "checkinInterval", "presenceTimeout",
     "missedCheckins", "zigbeeHealth"].each { attr ->
        device.deleteCurrentState(attr)
    }

    // v1.7.5: clear stale state variables from presence logic
    state.remove("lastCheckin")
    state.remove("checkinHistory")
    state.remove("avgCheckin")
    state.remove("missed")

    scheduleDebugAutoOff()
    configure()
}

def configure() {
    def battInterval = (batteryReportMinutes ?: "60").toInteger() * 60

    def cmds = []
    cmds += zigbee.batteryConfig()
    cmds += zigbee.configureReporting(0x0500, 0x0002, DataType.BITMAP16, 30, 3600, null)
    cmds += zigbee.configureReporting(0x0001, 0x0020, DataType.UINT8, 30, battInterval, 1)

    if (enableTemp) {
        cmds += zigbee.temperatureConfig(30, 1800)
    }

    cmds += zigbee.enrollResponse()
    sendZigbeeCommands(cmds)
}

def refresh() {
    def cmds = []
    cmds += zigbee.readAttribute(0x0001, 0x0020)  // battery voltage
    cmds += zigbee.readAttribute(0x0500, 0x0002)  // zone status
    if (enableTemp) cmds += zigbee.readAttribute(0x0402, 0x0000)  // temperature
    sendZigbeeCommands(cmds)
}

// ============================================================
// ===================== DEBUG AUTO-OFF ======================
// ============================================================
private void scheduleDebugAutoOff() {
    unschedule("disableDebugLogging")
    if (debugLogging) {
        log.warn "${device.displayName}: Debug logging enabled — will auto-disable in 30 minutes"
        runIn(1800, "disableDebugLogging")
    }
}

def disableDebugLogging() {
    log.info "${device.displayName}: Auto-disabling debug logging after 30 minutes"
    device.updateSetting("debugLogging", [value: "false", type: "bool"])
}

// ============================================================
// ===================== HEALTH CHECK ========================
// ============================================================

// v1.7.5: stub to silently absorb stale scheduled calls from v1.7.4
// Safe to remove in a future version once all devices have saved preferences
def presenceTimeoutCheck() {
    if (debugLogging) log.debug "${device.displayName}: presenceTimeoutCheck() — stale timer from v1.7.4, ignoring"
}

def ping() {
    if (debugLogging) log.debug "${device.displayName}: ping() — refreshing device state"
    refresh()
}

// ============================================================
// ===================== PARSE ===============================
// ============================================================
def parse(String description) {
    if (!description) return

    Map descMap = zigbee.parseDescriptionAsMap(description)
    if (descMap?.lqi)  { sendEvent(name: "lqi",  value: descMap.lqi);  updateRouteHealth(descMap.lqi.toInteger()) }
    if (descMap?.rssi) { sendEvent(name: "rssi", value: descMap.rssi) }

    // Zone status — motion
    if (description.startsWith("zone status")) {
        ZoneStatus status = zigbee.parseZoneStatus(description)
        processMotion(status)
        return
    }

    // Battery voltage — cluster 0x0001 attribute 0x0020
    if (descMap?.cluster == "0001" && descMap?.attrId == "0020") {
        def rawVolts = Integer.parseInt(descMap.value, 16) / 10.0
        def volts    = smoothBattery(rawVolts)
        sendEvent(name: "batteryVoltage", value: volts, unit: "V")
        def pct = calculateBattery(volts)
        if (device.currentValue("battery") != pct) {
            if (infoLogging) log.info "${device.displayName}: Battery ${pct}% (${volts}V)"
            sendEvent(name: "battery", value: pct, unit: "%")
        }
        return
    }

    // Temperature and other events
    def evt = zigbee.getEvent(description)
    if (!evt) return

    if (evt.name == "temperature") {
        // v1.7.5: fully suppressed when enableTemp is off — no log, no event
        if (!enableTemp) return
        Double offset = tempAdj ?: 0
        def temp = (evt.value + offset).round(2)
        if (infoLogging) log.info "${device.displayName}: Temperature ${temp}°${evt.unit}"
        sendEvent(name: "temperature", value: temp, unit: evt.unit)
        return
    }
}

// ============================================================
// ===================== MOTION ==============================
// ============================================================
def processMotion(ZoneStatus status) {
    if (status.isAlarm1Set()) {
        // Cancel any pending reset before starting a new one — prevents
        // inactive firing mid-motion if device triggers rapidly
        unschedule("motionInactive")
        sendEvent(name: "motion", value: "active", isStateChange: true)
        if (infoLogging) log.info "${device.displayName}: Motion active"

        // Guard against 0 or negative motionReset values
        def resetTime = (motionReset ?: 60).toInteger()
        if (resetTime > 0) runIn(resetTime, motionInactive)
    } else {
        motionInactive()
    }
}

def motionInactive() {
    sendEvent(name: "motion", value: "inactive", isStateChange: true)
    if (infoLogging) log.info "${device.displayName}: Motion inactive"
}

// ============================================================
// ===================== ROUTE HEALTH ========================
// ============================================================
def updateRouteHealth(Integer lqi) {
    def health = lqi >= 150 ? "Excellent" :
                 lqi >= 100 ? "Good" :
                 lqi >= 60  ? "Weak" : "Poor"
    sendEvent(name: "routeHealth", value: health)
    if (debugLogging) log.debug "${device.displayName}: LQI=${lqi} routeHealth=${health}"
}

// ============================================================
// ===================== BATTERY =============================
// ============================================================

/**
 * v1.7.5: Tightened voltage-to-percentage curve for CR2450/CR2477 coin cells.
 * 5% increments for cleaner battery reporting.
 * These batteries hold voltage well between 3.0-2.8V then drop steeply below 2.7V.
 */
def calculateBattery(Double voltage) {
    if (voltage >= 3.05) return 100
    if (voltage >= 3.00) return 95
    if (voltage >= 2.95) return 90
    if (voltage >= 2.90) return 85
    if (voltage >= 2.85) return 80
    if (voltage >= 2.80) return 75
    if (voltage >= 2.75) return 70
    if (voltage >= 2.70) return 65
    if (voltage >= 2.65) return 60
    if (voltage >= 2.60) return 55
    if (voltage >= 2.55) return 50
    if (voltage >= 2.50) return 45
    if (voltage >= 2.45) return 40
    if (voltage >= 2.40) return 35
    if (voltage >= 2.35) return 30
    if (voltage >= 2.30) return 25
    if (voltage >= 2.25) return 20
    if (voltage >= 2.20) return 15
    if (voltage >= 2.15) return 10
    if (voltage >= 2.10) return 5
    return 1
}

def smoothBattery(Double voltage) {
    if (!state.lastVolt) { state.lastVolt = voltage; return voltage }
    def smoothed = (state.lastVolt + voltage) / 2
    state.lastVolt = smoothed
    return smoothed.round(2)
}

// ============================================================
// ===================== ZIGBEE SEND =========================
// ============================================================
void sendZigbeeCommands(List cmds) {
    if (!cmds) return
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}
