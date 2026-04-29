/**
 *  Moonraker / Klipper 3D Printer Driver for Hubitat
 *
 *  Author:  jdthomas24
 *  Version: 1.0.45
 *  Date:    2026-04-29
 *
 *  Copyright 2026
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Supports any Moonraker/Klipper installation:
 *
 *    SONICPAD (multiple printers, one device):
 *      Create one Hubitat device per printer, all pointing to the same IP but different ports:
 *        Printer 1 -> IP: 192.168.1.x  Port: 7125
 *        Printer 2 -> IP: 192.168.1.x  Port: 7127
 *        Printer 3 -> IP: 192.168.1.x  Port: 7128
 *
 *    STANDARD INSTALL (Raspberry Pi, BTT Pi, CB1, etc.):
 *      One Hubitat device per printer, each with its own IP:
 *        Printer 1 -> IP: 192.168.1.10  Port: 7125
 *        Printer 2 -> IP: 192.168.1.11  Port: 7125
 *        Printer 3 -> IP: 192.168.1.12  Port: 7125
 *
 *    AUTHENTICATION:
 *      Most local installs require no API key.
 *      If your Moonraker install requires one, enter it in the API Key preference.
 *      Find your API key in moonraker.conf or via http://<ip>:<port>/access/api_key
 *
 *  Thanks to:
 *    NonaSuomy - Moonraker-Home-Assistant
 *    marcolivierarsenault - moonraker-home-assistant
 *    Arksine - Moonraker
 */
public static String version() { return "1.0.45" }

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

metadata {
    definition(name: "Moonraker Klipper Printer Driver", namespace: "Jdthomas24", author: "Jdthomas24") {
        capability "Refresh"
        capability "Initialize"
        capability "Actuator"
        capability "Sensor"
        capability "TemperatureMeasurement"

        // ── Print Control ──────────────────────────────────────────
        command "pause"
        command "resume"
        command "cancel"
        command "emergencyStop"
        command "firmwareRestart"
        command "executeGcode", [[name: "gcode*", type: "STRING", description: "Raw GCode command to send to printer"]]

        // ── File Management ────────────────────────────────────────
        command "startPrint",     [[name: "filename*", type: "STRING", description: "Enter the NUMBER from filesList (e.g. 1, 2, 3) or the full path (e.g. folder/myfile.gcode)"]]
        command "startLastPrint"

        // ── Tile (full status dashboard) ───────────────────────────
        attribute "aaStatusTile",            "string"  // HTML status dashboard tile
        attribute "filesListTile",         "string"  // HTML recent prints tile

        // ── Kept for automations & rules ───────────────────────────
        attribute "printState",            "enum",   ["standby", "printing", "paused", "complete", "error", "cancelled"]
        attribute "filename",              "string"
        attribute "error",                 "string"
        attribute "currentLayer",          "number"
        attribute "totalLayers",           "number"
        attribute "chamberTemp (°C)",      "number"
        attribute "mcuTemp (°C)",          "number"
        attribute "filamentDetected",      "enum",   ["true", "false", "unknown"]
        attribute "lastPrint",             "string"
        attribute "filesList (1-10)",      "string"
        attribute "filesList (11-20)",     "string"
    }
}

preferences {
    input(name: "setupTip", type: "hidden", title: """<div style='background:#fff3cd;border:1px solid #ffc107;border-radius:4px;padding:8px;margin-bottom:8px;font-size:12px;'>
        <b>⚠️ Setup Tip:</b> Name this Hubitat device to match your printer <i>before</i> saving preferences — e.g. "3D - CR10 Pro 1".<br>
        The device name appears in <b>Printer Info</b> so you can confirm you have the correct port assigned to the right printer.<br>
        <b>Sonicpad users:</b> create one Hubitat device per printer, each with the same IP but a different port.
        </div>""")
    input(name: "ipAddress",      type: "string",   title: "<b>Printer IP Address:</b>",
          description: "<i>IP address of your Moonraker host.<br>Sonicpad users: all printers share the same IP — use Port to differentiate.</i>",
          required: true, width: 4)
    input(name: "port",           type: "string",   title: "<b>Moonraker Port:</b>",
          description: "<i>Default is 7125.<br><b>Sonicpad (4 USB ports):</b> Port 1=7125, Port 2=7126, Port 3=7127, Port 4=7128<br>Use the port matching your printer's USB port — unconnected ports will show offline.<br><b>Standard install:</b> check your moonraker.conf for the configured port</i>",
          defaultValue: "7125", required: true, width: 4)
    input(name: "useSSL",         type: "bool",     title: "<b>Use HTTPS:</b>",
          description: "<i>Enable only if your Moonraker uses SSL. Most local installs do not.</i>",
          defaultValue: false, width: 4)
    input(name: "apiKey",         type: "string",   title: "<b>API Key (optional):</b>",
          description: "<i>Only required if your Moonraker install enforces API key authentication.<br>Find yours at http://&lt;ip&gt;:&lt;port&gt;/access/api_key</i>",
          required: false, width: 4)
    input(name: "pollInterval",   type: "enum",     title: "<b>Poll Interval:</b>",
          options: ["10": "10 Seconds", "30": "30 Seconds", "60": "1 Minute", "300": "5 Minutes"],
          defaultValue: "30", required: true, width: 4,
          description: "<i>How often to poll for status. 10-30s recommended while printing.</i>")
    input(name: "recentFilesLimit", type: "number",  title: "<b>Recent Files Limit:</b> (Top 20 Recommended)",
          description: "<i>Max number of recent files to show in <b>filesList</b>. Sorted most recent first, folder paths stripped.<br>Use <b>startPrint</b> command with the full path if your file is not in the list.</i>",
          defaultValue: 20, required: true, width: 4)
    input(name: "tempUnit",       type: "enum",     title: "<b>Temperature Unit:</b>",
          options: ["F": "Fahrenheit (°F)", "C": "Celsius (°C)"],
          defaultValue: "C", required: true, width: 4)
    input(name: "deviceInfoDisable",  type: "bool", title: "Disable Info logging:",  defaultValue: false, width: 4)
    input(name: "deviceDebugEnable",  type: "bool", title: "Enable Debug logging:", description: "<i>Auto-disables after 30 minutes.</i>", defaultValue: false, width: 4)
}

// ============================================================
//  LIFECYCLE
// ============================================================
def installed() {
    initialize()
}

def updated() {
    cleanStaleState()
    cleanStaleAttributes()
    initialize()
}

void cleanStaleState() {
    if (state?.moonrakerVersion != null) state.remove("moonrakerVersion")
}

void cleanStaleAttributes() {
    List stale = [
        "healthStatus", "printerInfo", "statusTile", "printerStatusTile", "message",
        "progress (%)", "printTime (min)", "printTimeLeft (min)", "printETA",
        "filamentUsed (mm)", "fanSpeed (%)", "printsCompleted",
        "hotendTemp (°C)", "hotendTarget (°C)", "bedTemp (°C)", "bedTarget (°C)", "hotendTemp (° C)", "hotendTarget (° C)", "bedTemp (° C)", "bedTarget (° C)",
        "hotendTemp (°F)", "hotendTarget (°F)", "bedTemp (°F)", "bedTarget (°F)",
        "temperature", "filesCount",
        "temperature",
        "hotendTemp (°F)", "hotendTarget (°F)",
        "bedTemp (°F)", "bedTarget (°F)",
        "chamberTemp (°F)", "mcuTemp (°F)",
        "filesCount"
    ]
    device.currentStates?.collect{ ((new groovy.json.JsonSlurper().parseText(groovy.json.JsonOutput.toJson(it)))?.name) }
        ?.each{ n -> if (stale.contains(n)) { device.deleteCurrentState(n); logInfo "removed stale attribute: $n" } }
}

def logsOff() {
    device.updateSetting("deviceDebugEnable", [value: "false", type: "bool"])
    logInfo "debug logging disabled automatically"
}

void autoLogsOff() {
    if (settings?.deviceDebugEnable) runIn(1800, "logsOff")
    else unschedule("logsOff")
}

def initialize() {
    unschedule()
    autoLogsOff()
    logInfo "initializing Moonraker driver v${version()} at ${getBaseUrl()}"
    sendEvent(name: "healthStatus", value: "offline")
    getInfo()
    runIn(2, "discoverObjects")
    schedulePoll()
}

void schedulePoll() {
    Integer interval = (settings?.pollInterval ?: "30").toInteger()
    if (interval == 10)  { runEvery10Minutes(refresh); schedule("0/${interval} * * * * ?", refresh) }
    else if (interval == 30) { schedule("0/30 * * * * ?", refresh) }
    else if (interval == 60) { runEvery1Minute(refresh) }
    else                     { runEvery5Minutes(refresh) }
}

// ============================================================
//  REFRESH / POLL
// ============================================================
def refresh() {
    logDebug "executing refresh()"
    getStatus()
}

// ============================================================
//  API CALLS
// ============================================================
String getBaseUrl() {
    String scheme = settings?.useSSL ? "https" : "http"
    return "${scheme}://${settings?.ipAddress}:${settings?.port}"
}

Map getHeaders() {
    Map headers = ["Content-Type": "application/json"]
    if (settings?.apiKey) headers["X-Api-Key"] = settings.apiKey
    return headers
}

void getInfo() {
    // GET /printer/info — klipper state, hostname, version
    Map params = [
        uri:     getBaseUrl(),
        path:    "/printer/info",
        headers: getHeaders(),
        timeout: 10
    ]
    try {
        asynchttpGet("infoCallback", params, [method: "getInfo"])
    } catch (e) {
        logWarn "getInfo() error: $e"
        setOffline()
    }
}

void getStatus() {
    // Moonraker requires each object as a separate query param:
    // /printer/objects/query?print_stats&extruder&heater_bed&...
    // First do a discovery call to find filament sensor name, then query
    String queryString = [
        "print_stats",
        "virtual_sdcard",
        "extruder",
        "heater_bed",
        "display_status",
        "fan",
        "toolhead"
    ].join("&")

    // Append any known optional sensors stored from discovery
    // URL encode spaces in sensor names (e.g. "filament_switch_sensor filament_sensor" -> "filament_switch_sensor%20filament_sensor")
    if (state?.filamentSensorName) queryString += "&${URLEncoder.encode(state.filamentSensorName, 'UTF-8').replace('+','%20')}"
    if (state?.chamberSensorName)  queryString += "&${URLEncoder.encode(state.chamberSensorName,  'UTF-8').replace('+','%20')}"
    if (state?.mcuSensorName)      queryString += "&${URLEncoder.encode(state.mcuSensorName,       'UTF-8').replace('+','%20')}"

    Map params = [
        uri:     "${getBaseUrl()}/printer/objects/query?${queryString}",
        headers: getHeaders(),
        timeout: 10
    ]
    try {
        asynchttpGet("statusCallback", params, [method: "getStatus"])
    } catch (e) {
        logWarn "getStatus() error: $e"
        setOffline()
    }
}

void discoverObjects() {
    // GET /printer/objects/list - find filament sensor, chamber, mcu sensor names
    Map params = [
        uri:     getBaseUrl(),
        path:    "/printer/objects/list",
        headers: getHeaders(),
        timeout: 10
    ]
    try {
        asynchttpGet("discoverCallback", params, [method: "discoverObjects"])
    } catch (e) {
        logWarn "discoverObjects() error: $e"
    }
    // Also refresh file list on init
    runIn(3, "refreshFileList")
    // Also get server info for printer name
    Map serverParams = [
        uri:     getBaseUrl(),
        path:    "/server/info",
        headers: getHeaders(),
        timeout: 10
    ]
    try {
        asynchttpGet("serverInfoCallback", serverParams, [method: "serverInfo"])
    } catch (e) {
        logWarn "serverInfo() error: $e"
    }
}

void serverInfoCallback(resp, data) {
    logDebug "serverInfoCallback() status:${resp.status}"
    if (resp.status != 200) return
    try {
        Map json   = new JsonSlurper().parseText(resp.data)
        Map result = json?.result ?: [:]
        logDebug "serverInfo result keys: ${result.keySet()}"
        String version = result?.moonraker_version ?: result?.api_version_string ?: ""
        if (version && version != "?") { logDebug "moonraker version: $version" }
        else { logDebug "moonraker version unavailable (Sonicpad returns ? — this is normal)" }
    } catch (e) {
        logWarn "serverInfoCallback() parse error: $e"
    }
}

void discoverCallback(resp, data) {
    logDebug "discoverCallback() status:${resp.status}"
    if (resp.status != 200) return
    try {
        Map json = new JsonSlurper().parseText(resp.data)
        List objects = json?.result?.objects ?: []
        // Find filament sensor
        String filament = objects.find{ it.startsWith("filament_switch_sensor") }
        if (filament) { state.filamentSensorName = filament; logInfo "found filament sensor: $filament" }
        // Find chamber temp sensor
        String chamber = objects.find{ it.startsWith("temperature_sensor") && it.toLowerCase().contains("chamber") }
        if (chamber) { state.chamberSensorName = chamber; logInfo "found chamber sensor: $chamber" }
        // Find MCU temp sensor
        String mcu = objects.find{ it.startsWith("temperature_sensor") && (it.toLowerCase().contains("mcu") || it.toLowerCase().contains("rpi") || it.toLowerCase().contains("host")) }
        if (mcu) { state.mcuSensorName = mcu; logInfo "found MCU sensor: $mcu" }
    } catch (e) {
        logWarn "discoverCallback() parse error: $e"
    }
}

// ============================================================
//  CALLBACKS
// ============================================================
void infoCallback(resp, data) {
    logDebug "infoCallback() status:${resp.status}"
    if (resp.status == 200) {
        try {
            Map json = new JsonSlurper().parseText(resp.data)
            Map result = json?.result ?: [:]
            String klipperState = result?.state ?: "disconnected"
            String prevKlipperState = device.currentValue("klipperState") ?: ""
            if (prevKlipperState != klipperState) {
                if (klipperState == "ready") logInfo "klipper state is ready"
                else logWarn "klipper state changed to: $klipperState"
            }
            sendEventX(name: "klipperState", value: klipperState)
            state.lastKlipperState = klipperState
            // Build a readable info string for Current States using Hubitat device name
            String hostname    = result?.hostname ?: "unknown"
            String cpuInfo     = result?.cpu_info  ?: "unknown"
            state.hostname = hostname
            String info = "Name: ${device.displayName} | Host: ${hostname} | CPU: ${cpuInfo} | Port: ${settings?.port}"
            if (klipperState == "ready") {
                setOnline()
                getStatus()
            } else {
                setOffline()
            }
        } catch (e) {
            logWarn "infoCallback() parse error: $e"
            setOffline()
        }
    } else {
        logWarn "infoCallback() HTTP ${resp.status}: ${resp?.errorMessage}"
        setOffline()
    }
}

void statusCallback(resp, data) {
    logDebug "statusCallback() status:${resp.status}"
    if (resp.status != 200) {
        logWarn "statusCallback() HTTP ${resp.status}: ${resp?.errorMessage}"
        setOffline()
        return
    }
    setOnline()
    try {
        Map json    = new JsonSlurper().parseText(resp.data)
        Map status  = json?.result?.status ?: [:]

        // ---- print_stats ----
        Map printStats = status?.print_stats ?: [:]
        String printState = printStats?.state ?: "standby"
        // LOG: only on state change - complete, error get extra attention
        String prevPrintState = device.currentValue("printState") ?: ""
        if (prevPrintState != printState) {
            if (printState == "complete") { logInfo "*** PRINT COMPLETE: ${device.currentValue('filename')} ***"; runIn(5, "refreshFileList") }
            else if (printState == "error") logWarn "*** PRINT ERROR on ${device.displayName} ***"
            else if (printState == "printing") { logInfo "print started: ${printStats?.filename ?: 'unknown'}"; runIn(5, "refreshFileList") }
            else if (printState == "paused")   logInfo "print paused"
            else if (printState == "cancelled") logInfo "print cancelled"
        }
        sendEventX(name: "printState", value: printState,
                   logLevel: (printState == "error" ? "warn" : null))

        String filename = printStats?.filename ?: ""
        // Strip folder path for display — full path still used internally
        String filenameDisplay = filename ? (filename.contains("/") ? filename.tokenize("/").last() : filename) : "none"
        String filenameClean   = filenameDisplay.replaceAll(/(?i)\.gcode$/, "")
        sendEventX(name: "filename", value: (filenameClean ?: "none"))

        Integer printTimeSec = printStats?.print_duration?.toInteger() ?: 0
        Integer printTimeMin = Math.ceil(printTimeSec / 60).toInteger()

        Double filamentUsed = printStats?.filament_used ?: 0.0

        // Layer info (requires SET_PRINT_STATS_INFO in slicer)
        Integer currentLayer = printStats?.info?.current_layer ?: 0
        Integer totalLayers  = printStats?.info?.total_layer   ?: 0
        if (currentLayer > 0) sendEventX(name: "currentLayer", value: currentLayer)
        if (totalLayers  > 0) sendEventX(name: "totalLayers",  value: totalLayers)

        // Error message — log only on change to non-none value
        String errorMsg = printStats?.message ?: ""
        if (errorMsg) sendEventX(name: "error", value: errorMsg, descriptionText: "printer error: $errorMsg", logLevel: "warn")
        else          sendEventX(name: "error", value: "none")

        // ---- virtual_sdcard ----
        Map vsd = status?.virtual_sdcard ?: [:]
        Double progress = ((vsd?.progress ?: 0.0) * 100)
        Integer progressInt = Math.round(progress).toInteger()

        // Estimate time remaining - silent updates
        if (progressInt > 0 && progressInt < 100 && printTimeSec > 0) {
            Integer totalEstSec   = (printTimeSec / (progress / 100)).toInteger()
            Integer remainingSec  = totalEstSec - printTimeSec
            Integer remainingMin  = Math.ceil(remainingSec / 60).toInteger()
            Long etaMillis = now() + (remainingSec * 1000L)
            String eta = new Date(etaMillis).format("h:mm a", location.timeZone)
        } else if (progressInt == 100) {
        }

        // ---- extruder - silent updates ----
        // extruder and bed values collected below for statusTile
        Map extruder = status?.extruder ?: [:]
        Map bed      = status?.heater_bed ?: [:]

        // ---- fan ----
        Map fan = status?.fan ?: [:]
        Integer fanPct = fan?.speed != null ? Math.round((fan.speed.toDouble()) * 100).toInteger() : 0

        // ---- filament sensor ----
        Map filament = status?.find{ it.key?.startsWith("filament_switch_sensor") }?.value ?: [:]
        String filamentDetected = "unknown"
        if (filament?.filament_detected != null) {
            filamentDetected = filament.filament_detected ? "true" : "false"
            String prevDetected = device.currentValue("filamentDetected") ?: "unknown"
            if (filamentDetected == "false" && prevDetected != "false" && printState == "printing") {
                logWarn "*** FILAMENT RUNOUT on ${device.displayName} ***"
            }
        }

        // ---- filament detected attribute for rules ----
        sendEventX(name: "filamentDetected", value: filamentDetected)

        // ---- display_status (M117 message) ----
        Map display = status?.display_status ?: [:]
        String msg = display?.message ?: ""

        // ---- collect temp values for tile ----
        Double hotendC     = extruder?.temperature ?: 0.0
        Double hotendTargC = extruder?.target      ?: 0.0
        Double bedC        = bed?.temperature      ?: 0.0
        Double bedTargC    = bed?.target           ?: 0.0
        String unit        = tempUnitLabel()
        Double hotendVal   = convertTemp(hotendC).round(1)
        Double hotendTarg  = convertTemp(hotendTargC).round(1)
        Double bedVal      = convertTemp(bedC).round(1)
        Double bedTarg     = convertTemp(bedTargC).round(1)

        // ---- still send chamberTemp/mcuTemp for automation rules ----
        Map chamber = status?.find{ it.key?.startsWith("temperature_sensor") && it.key?.contains("chamber") }?.value ?: [:]
        if (chamber?.temperature != null) sendEventX(name: "chamberTemp (°C)", value: convertTemp(chamber.temperature.toDouble()).round(1))
        Map mcu = status?.find{ it.key?.startsWith("temperature_sensor") && it.key?.contains("mcu") }?.value ?: [:]
        if (mcu?.temperature != null) sendEventX(name: "mcuTemp (°C)", value: convertTemp(mcu.temperature.toDouble()).round(1))

        // ---- build statusTile ----
        String klipperStateNow = state?.lastKlipperState ?: device.currentValue("klipperState") ?: "unknown"
        String healthNow       = "online"
        String printerName     = device.displayName
        String hostname        = state?.hostname ?: ""
        String port            = settings?.port ?: ""
        String filenameNow     = filenameClean ?: "none"
        Integer progressNow    = progressInt
        Integer printTimeNow   = printTimeMin
        String etaNow          = (progressInt > 0 && progressInt < 100) ?
                                    new Date(now() + ((((printTimeSec / (progressInt / 100)).toInteger()) - printTimeSec) * 1000L)).format("h:mm a", location.timeZone)
                                    : (progressInt == 100 ? "complete" : "--")
        Integer remainingNow   = (progressInt > 0 && progressInt < 100) ?
                                    Math.ceil(((printTimeSec / (progressInt / 100)).toInteger() - printTimeSec) / 60).toInteger() : 0
        Integer filamentInt    = filamentUsed.toInteger()

        // state badge colors
        String stateBg    = printState == "printing" ? "#1b4332" : printState == "paused" ? "#3d2b00" : printState == "error" ? "#3d0000" : "#16213e"
        String stateColor = printState == "printing" ? "#00ff87" : printState == "paused" ? "#ffd166" : printState == "error" ? "#ff6b6b" : "#aaa"
        String stateIcon  = printState == "printing" ? "&#9654; " : printState == "paused" ? "&#9646;&#9646; " : printState == "error" ? "&#9888; " : ""
        String klipperBg  = klipperStateNow == "ready" ? "#16213e" : "#3d0000"
        String klipperCol = klipperStateNow == "ready" ? "#00b4d8" : "#ff6b6b"

        StringBuilder tile = new StringBuilder()
        tile.append("<div style=\"font-family:sans-serif;font-size:12px;background:#1a1a2e;border-radius:10px;overflow:hidden;\">")

        // Header
        tile.append("<div style=\"background:#0f3460;padding:8px 12px;display:flex;align-items:center;justify-content:space-between;\">")
        tile.append("<div><div style=\"color:#00b4d8;font-weight:bold;font-size:13px;\">${printerName}</div>")
        if (hostname) tile.append("<div style=\"color:#aaa;font-size:10px;\">${hostname} &middot; port ${port}</div>")
        tile.append("</div>")
        tile.append("<div style=\"display:flex;gap:6px;align-items:center;\">")
        tile.append("<span style=\"background:#1b4332;color:#00ff87;font-size:10px;padding:2px 8px;border-radius:10px;\">&#9679; online</span>")
        tile.append("<span style=\"background:${klipperBg};color:${klipperCol};font-size:10px;padding:2px 8px;border-radius:10px;\">klipper: ${klipperStateNow}</span>")
        tile.append("</div></div>")

        // Print state + filename bar
        tile.append("<div style=\"background:#16213e;border-bottom:1px solid #0f3460;padding:6px 12px;display:flex;align-items:center;gap:8px;\">")
        tile.append("<span style=\"background:${stateBg};color:${stateColor};font-size:10px;padding:2px 10px;border-radius:10px;font-weight:bold;\">${stateIcon}${printState}</span>")
        if (filenameNow != "none") tile.append("<span style=\"color:#eee;font-size:11px;font-weight:bold;\">${filenameNow}</span>")
        tile.append("</div>")

        // Error banner — only show when error is present
        String errorVal = device.currentValue("error") ?: "none"
        if (errorVal && errorVal != "none") {
            tile.append("<div style=\"background:#3d0000;border-left:3px solid #ff6b6b;padding:6px 12px;\">")
            tile.append("<span style=\"color:#ffaaaa;font-size:11px;\">&#9888; ${errorVal}</span>")
            tile.append("</div>")
        }

        // Temps row
        tile.append("<div style=\"padding:8px 12px;\">")
        tile.append("<div style=\"display:grid;grid-template-columns:1fr 1fr;gap:6px;margin-bottom:8px;\">")
        tile.append("<div style=\"background:#0f3460;border-radius:6px;padding:6px 10px;\">")
        tile.append("<div style=\"color:#aaa;font-size:9px;margin-bottom:2px;\">HOTEND</div>")
        tile.append("<div style=\"display:flex;align-items:baseline;gap:4px;\"><span style=\"color:#ff6b6b;font-size:16px;font-weight:bold;\">${hotendVal}°</span>")
        tile.append("<span style=\"color:#888;font-size:10px;\">/ ${hotendTarg}° target</span></div></div>")
        tile.append("<div style=\"background:#0f3460;border-radius:6px;padding:6px 10px;\">")
        tile.append("<div style=\"color:#aaa;font-size:9px;margin-bottom:2px;\">BED</div>")
        tile.append("<div style=\"display:flex;align-items:baseline;gap:4px;\"><span style=\"color:#ffa94d;font-size:16px;font-weight:bold;\">${bedVal}°</span>")
        tile.append("<span style=\"color:#888;font-size:10px;\">/ ${bedTarg}° target</span></div></div>")
        tile.append("</div>")

        // Progress bar (only when printing/paused)
        if (printState in ["printing", "paused"]) {
            tile.append("<div style=\"margin-bottom:6px;\">")
            tile.append("<div style=\"display:flex;justify-content:space-between;margin-bottom:3px;\"><span style=\"color:#aaa;font-size:10px;\">progress</span><span style=\"color:#eee;font-size:10px;font-weight:bold;\">${progressNow}%</span></div>")
            tile.append("<div style=\"background:#0f3460;border-radius:3px;height:5px;\"><div style=\"background:#00b4d8;width:${progressNow}%;height:5px;border-radius:3px;\"></div></div></div>")

            // Time row
            tile.append("<div style=\"display:grid;grid-template-columns:1fr 1fr 1fr;gap:4px;margin-bottom:8px;\">")
            tile.append("<div style=\"background:#0f3460;border-radius:5px;padding:5px 8px;text-align:center;\"><div style=\"color:#aaa;font-size:9px;\">elapsed</div><div style=\"color:#eee;font-size:11px;font-weight:bold;\">${printTimeNow} min</div></div>")
            tile.append("<div style=\"background:#0f3460;border-radius:5px;padding:5px 8px;text-align:center;\"><div style=\"color:#aaa;font-size:9px;\">remaining</div><div style=\"color:#00ff87;font-size:11px;font-weight:bold;\">${remainingNow} min</div></div>")
            tile.append("<div style=\"background:#0f3460;border-radius:5px;padding:5px 8px;text-align:center;\"><div style=\"color:#aaa;font-size:9px;\">ETA</div><div style=\"color:#eee;font-size:11px;font-weight:bold;\">${etaNow}</div></div>")
            tile.append("</div>")
        }

        // Footer
        String filamentStatus = filamentDetected == "true" ? "OK" : filamentDetected == "false" ? "RUNOUT!" : "unknown"
        String filamentCol    = filamentDetected == "false" ? "#ff6b6b" : "#888"
        tile.append("<div style=\"border-top:1px solid #0f3460;padding:5px 12px;display:flex;justify-content:space-between;align-items:center;margin-top:2px;\">")
        tile.append("<span style=\"color:#888;font-size:9px;\">fan: ${fanPct}% &nbsp;&middot;&nbsp; filament: <span style=\"color:${filamentCol}\">${filamentStatus}</span> &nbsp;&middot;&nbsp; ${filamentInt}mm used</span>")
        tile.append("<span style=\"color:#888;font-size:9px;\">")
        Integer completedCount = state?.completedCount ?: 0
        String printsDoneStr = completedCount > 0 ? "${completedCount} prints done" : ""
        if (printsDoneStr) tile.append("${printsDoneStr}")
        tile.append("</span>")
        tile.append("</div></div></div>")

        sendEvent(name: "aaStatusTile", value: tile.toString(), displayed: false)

    } catch (e) {
        logWarn "statusCallback() parse error: $e"
    }
}

// ============================================================
//  COMMANDS
// ============================================================
def pause() {
    logInfo "executing pause()"
    postGcode("PAUSE")
}

def resume() {
    logInfo "executing resume()"
    postGcode("RESUME")
}

def cancel() {
    logInfo "executing cancel()"
    postCommand("/printer/print/cancel")
}

def emergencyStop() {
    logWarn "executing emergencyStop()"
    postCommand("/printer/emergency_stop")
}

def firmwareRestart() {
    logInfo "executing firmwareRestart()"
    postCommand("/printer/firmware_restart")
}

def executeGcode(String gcode) {
    logInfo "executing gcode: $gcode"
    Map params = [
        uri:     getBaseUrl(),
        path:    "/printer/gcode/script",
        headers: getHeaders(),
        body:    [script: gcode],
        timeout: 10
    ]
    try {
        asynchttpPost("commandCallback", params, [method: "executeGcode", gcode: gcode])
    } catch (e) {
        logWarn "executeGcode() error: $e"
    }
}

void postCommand(String path) {
    Map params = [
        uri:     getBaseUrl(),
        path:    path,
        headers: getHeaders(),
        body:    [:],
        timeout: 10
    ]
    try {
        asynchttpPost("commandCallback", params, [method: path])
    } catch (e) {
        logWarn "postCommand($path) error: $e"
    }
}

void postGcode(String gcode) {
    Map params = [
        uri:     getBaseUrl(),
        path:    "/printer/gcode/script",
        headers: getHeaders(),
        body:    [script: gcode],
        timeout: 10
    ]
    try {
        asynchttpPost("commandCallback", params, [method: "postGcode", gcode: gcode])
    } catch (e) {
        logWarn "postGcode() error: $e"
    }
}

void commandCallback(resp, data) {
    logDebug "commandCallback() method:${data?.method} status:${resp.status}"
    if (resp.status == 200) {
        logInfo "command '${data?.method}' accepted"
        runIn(2, "refresh")
    } else {
        logWarn "command '${data?.method}' failed: HTTP ${resp.status} ${resp?.errorMessage}"
    }
}

// ============================================================
//  HELPERS
// ============================================================
void setOnline() {
    if (device.currentValue("healthStatus") != "online") logInfo "printer is online"
}

void setOffline() {
    if (device.currentValue("healthStatus") != "offline") logWarn "printer is offline"
    sendEventX(name: "klipperState", value: "disconnected")
}

Double convertTemp(Double celsius) {
    if (settings?.tempUnit == "C") return celsius
    return (celsius * 9/5) + 32
}

String tempUnitLabel() {
    return (settings?.tempUnit == "C") ? "°C" : "°F"
}

void sendEventX(Map x) {
    if (x?.value != null && (device.currentValue(x?.name)?.toString() != x?.value?.toString() || x?.isStateChange)) {
        // Only log if logLevel explicitly set — null means silent update
        if (x?.logLevel == "warn" && x?.descriptionText)       logWarn(x.descriptionText)
        else if (x?.logLevel == "info" && x?.descriptionText)  logInfo(x.descriptionText)
        sendEvent(name: x.name, value: x.value, unit: x?.unit, descriptionText: x?.descriptionText, isStateChange: (x?.isStateChange ?: false))
    }
}

// ============================================================
//  FILE MANAGEMENT
// ============================================================
void refreshFileList() {
    logInfo "refreshing file list from print history"
    Integer limit = (settings?.recentFilesLimit ?: 20).toInteger()
    // Use history API — returns jobs sorted most recent first, gives us actual print order
    // Request extra to account for duplicates we'll deduplicate
    Map params = [
        uri:     "${getBaseUrl()}/server/history/list?limit=100&order=desc",
        headers: getHeaders(),
        timeout: 10
    ]
    try {
        asynchttpGet("filesCallback", params, [method: "refreshFileList"])
    } catch (e) {
        logWarn "refreshFileList() error: $e"
    }
}

void filesCallback(resp, data) {
    logDebug "filesCallback() status:${resp.status}"
    if (resp.status != 200) {
        logWarn "filesCallback() HTTP ${resp.status}: ${resp?.errorMessage}"
        return
    }
    try {
        Map json  = new JsonSlurper().parseText(resp.data)
        List jobs = json?.result?.jobs ?: []
        Integer limit = (settings?.recentFilesLimit ?: 20).toInteger()
        Integer totalJobs = (json?.result?.count ?: jobs.size()).toInteger()

        // Deduplicate — keep most recent occurrence of each filename
        // Pin in_progress jobs to top
        List seen       = []
        List inProgress = []
        List recentUnique = []

        jobs.each { job ->
            String fname = job?.filename ?: ""
            if (!fname) return
            if (job?.status == "in_progress") {
                if (!seen.contains(fname)) { inProgress << job; seen << fname }
            } else if (!seen.contains(fname)) {
                recentUnique << job
                seen << fname
            }
        }

        List allFiles    = (inProgress + recentUnique).take(limit)
        Integer totalCount = seen.size()
        List recentFiles = allFiles

        // Store full paths in state for numbered startPrint lookup
        // e.g. state.fileMap = ["1": "folder/file.gcode", "2": "other/file2.gcode"]
        Map fileMap = [:]
        List displayList = []
        recentFiles.eachWithIndex { file, idx ->
            String fullPath = file?.path ?: file?.filename ?: ""
            String number   = (idx + 1).toString()
            fileMap[number] = fullPath
            // Display: "1: filename [folder]" or "1: filename" if no folder
            String name     = fullPath.contains("/") ? fullPath.tokenize("/").last() : fullPath
            String folder   = fullPath.contains("/") ? fullPath.tokenize("/").dropRight(1).join("/") : ""
            // Strip .gcode extension for cleaner display
            String cleanName = name.replaceAll(/(?i)\.gcode$/, "")
            String display  = "${number}: ${cleanName}"
            displayList << display
        }
        state.filePathCache = groovy.json.JsonOutput.toJson(fileMap)
        state.remove("fileMap")

        // Build recentFilesForTile from the job-based allFiles list
        List recentFilesForTile = allFiles.collect { job ->
            String fullPath  = job?.filename ?: ""
            String name      = fullPath.contains("/") ? fullPath.tokenize("/").last() : fullPath
            String folder    = fullPath.contains("/") ? fullPath.tokenize("/").dropRight(1).join("/") : ""
            String cleanName = name.replaceAll(/(?i)\.gcode$/, "")
            [path: fullPath, name: cleanName, folder: folder, status: job?.status ?: ""]
        }

        // Split into 1-10 and 11-20 for plain text attributes
        List firstTen  = displayList.size() >= 10 ? displayList[0..9]   : displayList
        List secondTen = displayList.size() > 10  ? displayList[10..-1] : []

        // Count completed jobs only
        Integer completedCount = jobs.count{ it?.status == "completed" }.toInteger()
        state.completedCount = completedCount
        sendEventX(name: "printsCompleted", value: completedCount)

        // Last completed print (first non-in_progress job)
        String lastPrintFile = jobs.find{ it?.status == "completed" }?.filename ?: ""
        if (lastPrintFile) {
            String lastPrintName = lastPrintFile.contains("/") ? lastPrintFile.tokenize("/").last() : lastPrintFile
            String lastPrintClean = lastPrintName.replaceAll(/(?i)\.gcode$/, "")
            sendEventX(name: "lastPrint", value: lastPrintClean)
        }

        sendEventX(name: "filesList (1-10)",  value: (firstTen.join(" | ")   ?: "none"))
        sendEventX(name: "filesList (11-20)", value: (secondTen ? secondTen.join(" | ") : "none"))

        // Build HTML tile — dark styled table, in_progress pinned green at top
        Integer totalJobCount = totalJobs ?: totalCount
        StringBuilder html = new StringBuilder()
        html.append("<div style=\"font-family:sans-serif;font-size:11px;background:#1a1a2e;color:#eee;border-radius:8px;padding:8px;\">")
        html.append("<div style=\"text-align:center;font-weight:bold;font-size:12px;color:#00b4d8;margin-bottom:6px;\">")
        html.append("&#128438; Recent Prints <span style=\"color:#aaa;font-size:10px;\">(top ${recentFilesForTile.size()} of ${totalJobCount} jobs)</span></div>")

        // Last completed print banner
        String lastPrintVal = device.currentValue("lastPrint") ?: ""
        String currentPrintState = device.currentValue("printState") ?: "standby"
        if (lastPrintVal && lastPrintVal != "none" && !(currentPrintState in ["printing", "paused"])) {
            html.append("<div style=\"background:#16213e;border-left:3px solid #00ff87;padding:4px 8px;margin-bottom:6px;\">")
            html.append("<div style=\"font-size:9px;color:#aaa;\">last completed &middot; use startLastPrint to reprint</div>")
            html.append("<div style=\"color:#00ff87;font-weight:bold;font-size:11px;margin-top:1px;\">${lastPrintVal}</div>")
            html.append("</div>")
        }

        html.append("<table style=\"width:100%;border-collapse:collapse;\">")
        recentFilesForTile.eachWithIndex { file, idx ->
            String number    = (idx + 1).toString()
            Boolean isPrinting = file.status == "in_progress"
            String rowBg     = isPrinting ? "#1b4332" : (idx % 2 == 0 ? "#16213e" : "#0f3460")
            String numColor  = isPrinting ? "#00ff87" : "#00b4d8"
            String nameColor = isPrinting ? "#00ff87" : "#eee"
            String indicator = isPrinting ? " &#9654; printing" : ""
            html.append("<tr style=\"background:${rowBg};\">")
            html.append("<td style=\"padding:3px 5px;color:${numColor};font-weight:bold;width:24px;min-width:24px;text-align:center;\">${number}</td>")
            html.append("<td style=\"padding:3px 5px;color:${nameColor};\">${file.name}${indicator}</td></tr>")
        }
        html.append("</table>")
        html.append("<div style=\"color:#888;font-size:9px;margin-top:4px;text-align:center;\">Use number with startPrint command</div>")
        html.append("</div>")
        sendEventX(name: "filesListTile", value: html.toString())
        logInfo "found $totalCount gcode files — showing ${displayList.size()} most recent"
    } catch (e) {
        logWarn "filesCallback() parse error: $e"
    }
}

def startPrint(String filename) {
    if (!filename) { logWarn "startPrint() no filename provided"; return }
    if (device.currentValue("printState") == "printing") {
        logWarn "startPrint() rejected — printer is already printing"
        return
    }
    // Accept a number (1-10) as shortcut — looks up full path from fileMap
    String resolvedFile = filename.trim()
    if (resolvedFile.isInteger()) {
        Map _fmMap = state?.filePathCache ? (new groovy.json.JsonSlurper().parseText(state.filePathCache)) : [:]
        String mapped = _fmMap?.get(resolvedFile)
        if (mapped) {
            logInfo "startPrint() resolved #${resolvedFile} -> ${mapped}"
            resolvedFile = mapped
        } else {
            logWarn "startPrint() no file found for number ${resolvedFile} — run refreshFileList first"
            return
        }
    }
    logInfo "starting print: $resolvedFile"
    Map params = [
        uri:         getBaseUrl(),
        path:        "/printer/print/start",
        headers:     getHeaders() + ["Content-Type": "application/json"],
        body:        groovy.json.JsonOutput.toJson([filename: resolvedFile]),
        contentType: "application/json",
        timeout:     10
    ]
    try {
        asynchttpPost("commandCallback", params, [method: "startPrint", filename: resolvedFile])
    } catch (e) {
        logWarn "startPrint() error: $e"
    }
}

def startLastPrint() {
    // Use the full path from fileMap entry 1 (most recent job) if available
    Map _fmMap = state?.filePathCache ? (new groovy.json.JsonSlurper().parseText(state.filePathCache)) : [:]
    String lastFile = _fmMap?.get("1") ?: device.currentValue("filename") ?: ""
    if (!lastFile || lastFile == "none") {
        logWarn "startLastPrint() no previous filename found"
        return
    }
    logInfo "re-printing last file: $lastFile"
    startPrint(lastFile)
}

// ============================================================
//  LOG HELPERS
// ============================================================
def logInfo(msg)  { if (!deviceInfoDisable)  log.info  "${device.displayName} ${msg}" }
def logDebug(msg) { if (deviceDebugEnable)   log.debug "${device.displayName} ${msg}" }
def logWarn(msg)  {                           log.warn  "${device.displayName} ${msg}" }
def logError(msg) {                           log.error "${device.displayName} ${msg}" }
