/**
 * Leviton LDATA / LWHEM Smart Panel - Child Driver
 *
 * Ported from: https://github.com/rwoldberg/ldata-ha
 * Original author: rwoldberg (Home Assistant integration)
 * Hubitat port: Community port
 *
 * This child driver is automatically created by the LevitonLDATA_Parent driver.
 * It represents either a Smart Circuit Breaker or a CT clamp sensor.
 *
 * Breaker child devices provide:
 *   - Switch capability (on/off = reset/trip) when allowBreakerControl is enabled on parent
 *   - Power monitoring (watts)
 *   - Current monitoring (amps)
 *   - Voltage monitoring (volts)
 *   - Frequency monitoring (Hz)
 *   - Energy counters (kWh, if supported by panel firmware)
 *   - Breaker state (ManualON, ManualOFF, Tripped, etc.)
 *   - Ampere rating, position, leg, poles
 *
 * CT clamp child devices provide:
 *   - Power monitoring (watts) — Grid, Solar, Generator, etc.
 *   - Current monitoring (amps)
 *   - Energy counters (kWh)
 *
 * Do NOT create this driver manually — it is managed by the parent.
 *
 * Changelog:
 *   - 1.2.0: Only send an event when the value actually changed from the
 *     device's current attribute value. A single WS panel snapshot (e.g.
 *     right after a reconnect) can carry every breaker/CT at once; resending
 *     all ~15 attributes per breaker unconditionally on every update was
 *     generating thousands of sendEvent() calls in one parse() pass, which is
 *     what tripped Hubitat's "excessive hub load" protection and caused that
 *     update to be dropped entirely. Most fields (model, position, leg,
 *     poles, ampRating, IDs) essentially never change after the first set, so
 *     this should cut event volume dramatically with no change in behavior.
 */

metadata {
    definition(
        name       : "Leviton Smart Load Center Child",
        namespace  : "jdthomas24",
        author     : "Community Port from rwoldberg/ldata-ha",
        description: "Child device for Leviton Smart Panel breaker or CT sensor",
        version    : "1.2.0"
    ) {
        // Core capabilities
        capability "Switch"           // on/off = breaker reset/trip
        capability "PowerMeter"       // power in watts
        capability "CurrentMeter"     // current in amps
        capability "VoltageMeasurement" // voltage
        capability "EnergyMeter"      // energy in kWh (maps to energyConsumption)
        capability "Sensor"
        capability "Actuator"

        // Device type (breaker or ct)
        attribute "deviceType",        "string"  // "breaker" or "ct"

        // Breaker-specific attributes
        attribute "breakerState",      "string"  // ManualON, ManualOFF, Tripped, etc.
        attribute "remoteState",       "string"  // RemoteON, RemoteOFF
        attribute "canRemoteOn",       "string"  // true/false — 2nd gen breakers only
        attribute "ampRating",         "number"  // rated amperage
        attribute "position",          "number"  // slot position in panel (1-66)
        attribute "leg",               "number"  // 1 or 2 (which panel leg)
        attribute "poles",             "number"  // 1 or 2 pole breaker
        attribute "breakerModel",      "string"  // hardware model string
        attribute "frequency",         "number"  // line frequency (Hz)
        attribute "energyConsumption", "number"  // cumulative energy (kWh)
        attribute "energyImport",      "number"  // import energy (kWh, solar breakers)
        attribute "breakerId",         "string"  // internal Leviton breaker ID

        // CT-specific attributes
        attribute "channel",           "string"  // CT channel (1, 2, 3...)
        attribute "ctId",              "string"  // internal Leviton CT ID

        // Switch commands (only functional when allowBreakerControl=true on parent)
        command "on",  [[name:"Reset/close breaker (requires allowBreakerControl on parent)"]]
        command "off", [[name:"Trip/open breaker (requires allowBreakerControl on parent)"]]
    }

    preferences {
        input name: "debugLogging",
              type: "bool",
              title: "Enable Debug Logging",
              defaultValue: false
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  LIFECYCLE
// ─────────────────────────────────────────────────────────────────────────────

def installed() {
    log.info "[LDATA-Child] ${device.displayName} installed"
    sendEvent(name: "switch",  value: "off")
    sendEvent(name: "power",   value: 0)
    sendEvent(name: "current", value: 0)
    sendEvent(name: "voltage", value: 0)
    sendEvent(name: "energy",  value: 0)
}

def updated() {
    logDebug "[LDATA-Child] ${device.displayName} updated"
}

// ─────────────────────────────────────────────────────────────────────────────
//  SWITCH COMMANDS  (delegate to parent)
// ─────────────────────────────────────────────────────────────────────────────

def on() {
    def breakerId = device.currentValue("breakerId")
    if (!breakerId) {
        log.warn "[LDATA-Child] ${device.displayName}: no breakerId — is this a CT device?"
        return
    }
    logDebug "[LDATA-Child] ${device.displayName}: on() → parent.breakerOn(${breakerId})"
    def parent = getParent()
    if (parent) {
        parent.breakerOn(breakerId)
    } else {
        log.error "[LDATA-Child] ${device.displayName}: no parent device found"
    }
}

def off() {
    def breakerId = device.currentValue("breakerId")
    if (!breakerId) {
        log.warn "[LDATA-Child] ${device.displayName}: no breakerId — is this a CT device?"
        return
    }
    logDebug "[LDATA-Child] ${device.displayName}: off() → parent.breakerOff(${breakerId})"
    def parent = getParent()
    if (parent) {
        parent.breakerOff(breakerId)
    } else {
        log.error "[LDATA-Child] ${device.displayName}: no parent device found"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  DATA UPDATES  (called by parent via child.parse())
// ─────────────────────────────────────────────────────────────────────────────

def parse(List<Map> events) {
    events.each { evt ->
        try {
            def name  = evt.name
            def value = evt.value
            def v     = null   // reusable scratch var for rounded numeric values

            switch (name) {
                // ── Core power attributes ──────────────────────────────────
                case "power":
                    v = roundSafe(value, 1)
                    if (changed("power", v)) sendEvent(name: "power", value: v, unit: "W")
                    break

                case "current":
                    v = roundSafe(value, 3)
                    if (changed("amperage", v)) sendEvent(name: "amperage", value: v, unit: "A")
                    break

                case "voltage":
                    v = roundSafe(value, 1)
                    if (changed("voltage", v)) sendEvent(name: "voltage", value: v, unit: "V")
                    break

                case "frequency":
                    v = roundSafe(value, 2)
                    if (changed("frequency", v)) sendEvent(name: "frequency", value: v, unit: "Hz")
                    break

                // ── Energy ────────────────────────────────────────────────
                case "energyConsumption":
                    v = roundSafe(value, 3)
                    // Map to both the standard capability attribute and our custom one
                    if (changed("energy", v))            sendEvent(name: "energy",            value: v, unit: "kWh")
                    if (changed("energyConsumption", v)) sendEvent(name: "energyConsumption", value: v, unit: "kWh")
                    break

                case "energyImport":
                    v = roundSafe(value, 3)
                    if (changed("energyImport", v)) sendEvent(name: "energyImport", value: v, unit: "kWh")
                    break

                // ── Switch / state ────────────────────────────────────────
                case "switch":
                    if (changed("switch", value)) sendEvent(name: "switch", value: value)
                    break

                case "breakerState":
                    if (changed("breakerState", value)) sendEvent(name: "breakerState", value: value)
                    break


                case "remoteState":
                    if (changed("remoteState", value)) sendEvent(name: "remoteState", value: value)
                    break

                // ── Metadata (non-polling attributes) ─────────────────────
                case "ampRating":
                    if (changed("ampRating", value)) sendEvent(name: "ampRating", value: value, unit: "A")
                    break

                case "position":
                    if (changed("position", value)) sendEvent(name: "position", value: value)
                    break

                case "leg":
                    if (changed("leg", value)) sendEvent(name: "leg", value: value)
                    break

                case "poles":
                    if (changed("poles", value)) sendEvent(name: "poles", value: value)
                    break

                case "canRemoteOn":
                    v = value?.toString()
                    if (changed("canRemoteOn", v)) sendEvent(name: "canRemoteOn", value: v)
                    break

                case "breakerModel":
                    if (changed("breakerModel", value)) sendEvent(name: "breakerModel", value: value)
                    break

                case "deviceType":
                    if (changed("deviceType", value)) sendEvent(name: "deviceType", value: value)
                    break

                case "breakerId":
                    if (changed("breakerId", value)) sendEvent(name: "breakerId", value: value)
                    break

                case "channel":
                    if (changed("channel", value)) sendEvent(name: "channel", value: value)
                    break

                case "ctId":
                    if (changed("ctId", value)) sendEvent(name: "ctId", value: value)
                    break

                default:
                    logDebug "[LDATA-Child] Unhandled event: ${name}=${value}"
            }
        } catch (Exception e) {
            log.warn "[LDATA-Child] ${device.displayName} parse error for ${evt}: ${e.message}"
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  UTILITY
// ─────────────────────────────────────────────────────────────────────────────

// v1.2.0: Skip sending an event if the new value matches what's already
// stored — string comparison avoids Float/BigDecimal/String type mismatches
// between what currentValue() returns and what we're about to send.
private boolean changed(String attr, def newVal) {
    def cur = device.currentValue(attr)
    return cur?.toString() != newVal?.toString()
}

private def roundSafe(def value, int decimals) {
    try {
        if (value == null) return 0
        return value.toFloat().round(decimals)
    } catch (ignored) { return 0 }
}

private void logDebug(String msg) {
    if (settings.debugLogging) log.debug msg
}
