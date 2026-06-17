/**
 *  Roborock Local TCP - Parent App
 *
 *  Copyright 2025
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  This app:
 *    1. Logs into Roborock cloud (one-time) to get localKey, duid, rooms, scenes
 *    2. Creates a child device per vacuum found on the account
 *    3. Polls each vacuum via direct Java TCP socket (synchronous — response guaranteed)
 *    4. Pushes status to child devices via sendEvent
 *    5. Accepts command callbacks from child driver via executeCommand()
 *
 *  INSTALL ORDER:
 *    1. Install this app code (Apps Code)
 *    2. Install the companion driver code (Drivers Code)
 *    3. Add App → Roborock Local TCP
 *    4. Enter credentials and save — devices are created automatically
 */
public static String version() { return "1.0.0" }

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import java.util.Random
import java.text.SimpleDateFormat
import java.net.Socket
import java.net.InetSocketAddress

@Field static final String  salt               = "TXdfu\$jyZ#TZHsg4"
@Field static final Integer ROBOROCK_LOCAL_PORT = 58867
@Field static final Integer TCP_TIMEOUT_MS      = 5000   // 5s connect + read timeout
@Field static final Map     life               = [main: 300, side: 200, filter: 150, sensor: 30, highSpeed: 300]

definition(
    name:        "Roborock Local TCP",
    namespace:   "bloodtick-local",
    author:      "Hubitat",
    description: "Local TCP control for Roborock vacuums. No cloud dependency after initial setup.",
    category:    "Convenience",
    iconUrl:     "",
    iconX2Url:   "",
    singleInstance: false
)

preferences {
    page(name: "mainPage")
    page(name: "credentialsPage")
    page(name: "devicesPage")
}

// ── Pages ─────────────────────────────────────────────────────────────────────
def mainPage() {
    dynamicPage(name: "mainPage", title: "<b>Roborock Local TCP</b>  v${version()}", install: true, uninstall: true) {
        section {
            href "credentialsPage", title: "Account & Login", description: credentialsSummary()
            href "devicesPage",     title: "Discovered Vacuums", description: devicesSummary()
        }
        section("<b>Poll Settings</b>") {
            input "pollInterval", "enum", title: "Poll Interval",
                options: ["15":"15 Seconds","30":"30 Seconds","60":"1 Minute","120":"2 Minutes"],
                defaultValue: "30", required: true
        }
        section("<b>Logging</b>") {
            input "logEnable",   "bool", title: "Enable Info Logging",  defaultValue: true
            input "debugEnable", "bool", title: "Enable Debug Logging", defaultValue: false
        }
        section {
            paragraph "<i>After saving, the app will log into Roborock, discover vacuums, and create devices automatically.</i>"
        }
    }
}

def credentialsPage() {
    dynamicPage(name: "credentialsPage", title: "<b>Roborock Account</b>", nextPage: "mainPage") {
        section("<b>Login Credentials</b>") {
            input "username",  "string",   title: "Roborock Username (email)", required: true
            input "password",  "password", title: "Roborock Password",         required: true
            input "regionUri", "enum",     title: "Account Region",
                options: ["https://usiot.roborock.com":"US","https://euiot.roborock.com":"EU",
                          "https://cniot.roborock.com":"CN","https://ruiot.roborock.com":"RU"],
                defaultValue: "https://usiot.roborock.com", required: true
        }
        section("<b>Two-Factor Authentication (if required)</b>") {
            input "emailPin", "string", title: "Email PIN (leave blank if not needed)", required: false
            paragraph "<i>If your account requires email verification, enter the PIN sent to your email here after first save attempt.</i>"
        }
    }
}

def devicesPage() {
    dynamicPage(name: "devicesPage", title: "<b>Discovered Vacuums</b>", nextPage: "mainPage") {
        List vacuums = state?.vacuums ?: []
        if (!vacuums) {
            section { paragraph "No vacuums discovered yet. Save credentials first." }
        } else {
            vacuums.each { v ->
                section("<b>${v.cloudName}</b>  (${v.duid})") {
                    input "name_${v.duid}", "string", title: "Device Name (override)",
                        defaultValue: v.cloudName, required: false
                    input "ip_${v.duid}", "string", title: "Local IP Address",
                        defaultValue: v.ip ?: "", required: false,
                        description: "Assign a static/reserved IP in your router"
                    paragraph "Model: ${v.model ?: 'unknown'}  |  Firmware: ${v.fv ?: 'unknown'}"
                    paragraph "localKey: ${v.localKey ? '✓ present' : '✗ missing'}"
                }
            }
        }
    }
}

String credentialsSummary() {
    if (!settings?.username) return "Tap to configure"
    return "Username: ${settings.username}  |  Region: ${settings.regionUri?.contains('us') ? 'US' : settings.regionUri?.contains('eu') ? 'EU' : settings.regionUri?.contains('cn') ? 'CN' : 'RU'}"
}

String devicesSummary() {
    List vacuums = state?.vacuums ?: []
    if (!vacuums) return "No vacuums discovered yet"
    return "${vacuums.size()} vacuum(s): ${vacuums.collect { it.cloudName }.join(', ')}"
}

// ── Lifecycle ─────────────────────────────────────────────────────────────────
def installed() {
    logInfo "Roborock Local TCP app installed"
    initialize()
}

def updated() {
    logInfo "Roborock Local TCP app updated"
    unschedule()
    initialize()
}

def uninstalled() {
    logInfo "Roborock Local TCP app uninstalled — removing child devices"
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

def initialize() {
    logInfo "initialize()"
    if (!settings?.username || !settings?.password) {
        logWarn "No credentials set — skipping initialization"
        return
    }
    // Cloud login and device discovery
    runIn(2, "cloudLogin")
}

// ── Cloud Login & Discovery ───────────────────────────────────────────────────
def cloudLogin() {
    logInfo "cloudLogin() — logging into Roborock cloud"

    Map loginResult = login()
    if (loginResult?.msg != "success") {
        // Check if 2FA is needed
        if (loginResult?.code?.toInteger() == 2031) {
            logWarn "2FA required — requesting email code"
            sendEmailCode()
            if (settings?.emailPin) {
                logInfo "Using provided email PIN"
                Map codeResult = loginWithCode(settings.emailPin)
                if (codeResult?.msg != "success") {
                    logError "Email PIN login failed: ${codeResult?.msg}"
                    return
                }
                logInfo "2FA login successful"
            } else {
                logWarn "Email PIN not provided — please enter PIN in credentials page and save again"
                return
            }
        } else {
            logError "Login failed: ${loginResult?.msg}"
            return
        }
    } else {
        logInfo "Cloud login successful"
    }

    // Get home data
    runIn(2, "discoverDevices")
}

def discoverDevices() {
    logInfo "discoverDevices() — fetching home data"

    Map homeDetail = getHomeDetail()
    if (!homeDetail) { logError "Failed to get home detail"; return }

    Map homeData = getHomeData(homeDetail.rrHomeId)
    if (!homeData) { logError "Failed to get home data"; return }

    // Build vacuum list
    List vacuums = []
    homeData.devices?.each { device ->
        String productId = device.productId
        Map product = homeData.products?.find { it.id == productId }
        Map vacuum = [
            duid:      device.duid,
            cloudName: device.name,
            localKey:  device.localKey,
            model:     product?.model ?: "unknown",
            fv:        device.fv ?: "unknown",
            ip:        device.ip ?: "",
            online:    device.online ?: false,
            schema:    product?.schema ?: []
        ]
        vacuums << vacuum
        logInfo "Found vacuum: ${vacuum.cloudName} (${vacuum.duid}) model:${vacuum.model}"
    }

    // Store rooms
    state.rooms = homeData.rooms?.collectEntries { [(it.id.toString()): it.name] } ?: [:]
    state.vacuums = vacuums
    state.rriot = state.loginData?.rriot
    state.homeDataTimestamp = now()

    logInfo "Discovered ${vacuums.size()} vacuum(s)"

    // Create/update child devices
    runIn(2, "createChildDevices")
}

def createChildDevices() {
    logInfo "createChildDevices()"
    List vacuums = state?.vacuums ?: []

    vacuums.each { v ->
        String dni = "roborock-local-${v.duid}"
        String deviceName = settings?."name_${v.duid}" ?: v.cloudName
        String deviceIP   = settings?."ip_${v.duid}"   ?: v.ip ?: ""

        def existingDevice = getChildDevice(dni)
        if (!existingDevice) {
            logInfo "Creating child device: ${deviceName} (${dni})"
            try {
                def child = addChildDevice(
                    "bloodtick-local",
                    "Roborock Vacuum (Local TCP)",
                    dni,
                    [name: deviceName, label: deviceName]
                )
                logInfo "Child device created: ${child.displayName}"
            } catch (Exception e) {
                logError "Failed to create child device for ${v.duid}: ${e.message}"
                return
            }
        } else {
            logInfo "Child device already exists: ${existingDevice.displayName}"
            // Update label if name override changed
            if (existingDevice.label != deviceName) {
                existingDevice.setLabel(deviceName)
            }
        }

        // Push config to child device
        def child = getChildDevice(dni)
        if (child) {
            child.updateDataValue("duid",     v.duid)
            child.updateDataValue("localKey", v.localKey)
            child.updateDataValue("model",    v.model)
            child.updateDataValue("firmware", v.fv)
            child.updateDataValue("localIP",  deviceIP)
            child.sendEvent(name: "name", value: deviceName)
            // Push rooms and scenes
            pushRoomsToDevice(child, v.duid)
        }
    }

    // Get scenes for each vacuum
    runIn(3, "fetchAllScenes")

    // Start polling
    runIn(5, "startPolling")
}

void pushRoomsToDevice(def child, String duid) {
    // Build room map from get_room_mapping style — stored in state after first poll
    // For now push the cloud rooms list as a starting point
    Map roomsMap = state?.rooms ?: [:]
    if (roomsMap) {
        String roomsList = roomsMap.sort { a, b -> a.key.toInteger() <=> b.key.toInteger() }
                                   .findAll { k, v -> v != null }
                                   .collect { k, v -> "$k: $v" }.join(" | ")
        child.sendEvent(name: "roomsList", value: roomsList)
        logDebug "Pushed ${roomsMap.size()} rooms to ${child.displayName}"
    }
}

def fetchAllScenes() {
    List vacuums = state?.vacuums ?: []
    vacuums.each { v ->
        try {
            List scenes = getDeviceScenes(v.duid)
            if (scenes) {
                String dni = "roborock-local-${v.duid}"
                def child = getChildDevice(dni)
                if (child) {
                    Map scenesMap = scenes.collectEntries { [(it.id.toString()): it.name] }
                    String scenesList = scenesMap.sort { a, b -> a.key.toInteger() <=> b.key.toInteger() }
                                                 .collect { k, v2 -> "$k: $v2" }.join(" | ")
                    child.sendEvent(name: "scenesList", value: scenesList)
                    logDebug "Pushed ${scenesMap.size()} scenes to ${child.displayName}"
                }
            }
        } catch (Exception e) {
            logWarn "fetchAllScenes error for ${v.duid}: ${e.message}"
        }
    }
}

// ── Polling ───────────────────────────────────────────────────────────────────
def startPolling() {
    logInfo "startPolling() — interval: ${settings?.pollInterval ?: '30'}s"
    unschedule('pollAllVacuums')
    Integer interval = (settings?.pollInterval ?: "30").toInteger()
    if (interval == 15)       schedule("0/15 * * * * ?", "pollAllVacuums")
    else if (interval == 30)  schedule("0/30 * * * * ?", "pollAllVacuums")
    else if (interval == 60)  runEvery1Minute("pollAllVacuums")
    else if (interval == 120) runEvery2Minutes("pollAllVacuums")
    // Immediate first poll
    runIn(2, "pollAllVacuums")
}

def pollAllVacuums() {
    List vacuums = state?.vacuums ?: []
    if (!vacuums) { logDebug "No vacuums to poll"; return }
    vacuums.each { v ->
        String ip = settings?."ip_${v.duid}" ?: v.ip ?: ""
        if (!ip) {
            logWarn "No IP for ${v.cloudName} — skipping poll. Set IP in Devices page."
            return
        }
        try {
            pollVacuum(v.duid, v.localKey, ip)
        } catch (Exception e) {
            logWarn "Poll error for ${v.cloudName}: ${e.message}"
            String dni = "roborock-local-${v.duid}"
            def child = getChildDevice(dni)
            if (child) markOffline(child, v.duid)
        }
    }
}

void pollVacuum(String duid, String localKey, String ip) {
    logDebug "pollVacuum: ${duid} at ${ip}"

    // get_status
    Map statusResult = sendLocalCommand(ip, duid, localKey, "get_prop", ["get_status"])
    if (statusResult != null) {
        String dni = "roborock-local-${duid}"
        def child = getChildDevice(dni)
        if (child) {
            processStatusResult(child, duid, statusResult)
            child.sendEvent(name: "healthStatus", value: "online")
            child.sendEvent(name: "connectionMode", value: "local")
        }
    } else {
        String dni = "roborock-local-${duid}"
        def child = getChildDevice(dni)
        if (child) markOffline(child, duid)
    }

    // get_consumable every 5th poll
    String pollKey = "pollCount_${duid}"
    Integer count = (state?."$pollKey" ?: 0).toInteger() + 1
    state."$pollKey" = count
    if (count % 5 == 0) {
        Map consumableResult = sendLocalCommand(ip, duid, localKey, "get_consumable", [])
        if (consumableResult != null) {
            String dni = "roborock-local-${duid}"
            def child = getChildDevice(dni)
            if (child) processConsumableResult(child, consumableResult)
        }
    }

    // get_room_mapping every 10th poll
    if (count % 10 == 0) {
        Map roomResult = sendLocalCommand(ip, duid, localKey, "get_room_mapping", [])
        if (roomResult != null) {
            String dni = "roborock-local-${duid}"
            def child = getChildDevice(dni)
            if (child) processRoomMapping(child, duid, roomResult)
        }
    }
}

void markOffline(def child, String duid) {
    String current = child.currentValue("healthStatus")
    if (current != "offline") {
        logWarn "Marking ${child.displayName} offline"
        child.sendEvent(name: "healthStatus",   value: "offline")
        child.sendEvent(name: "connectionMode", value: "offline")
    }
}

// ── Command callback from child driver ────────────────────────────────────────
// Called by child driver when user presses a command button
def executeCommand(String duid, String command, List params = []) {
    logInfo "executeCommand: duid=${duid} command=${command} params=${params}"

    Map v = state?.vacuums?.find { it.duid == duid }
    if (!v) { logWarn "executeCommand: no vacuum found for duid=${duid}"; return }

    String ip = settings?."ip_${duid}" ?: v.ip ?: ""
    if (!ip) { logWarn "executeCommand: no IP for ${duid}"; return }

    Map result = sendLocalCommand(ip, duid, v.localKey, command, params)

    String dni = "roborock-local-${duid}"
    def child = getChildDevice(dni)

    if (result != null) {
        logInfo "executeCommand: ${command} accepted"
        if (child) {
            child.sendEvent(name: "healthStatus", value: "online")
            // Schedule a status refresh after command
            runIn(3, "pollAllVacuums")
        }
    } else {
        logWarn "executeCommand: ${command} failed or no response"
        if (child) markOffline(child, duid)
    }
}

// Scene execution goes via cloud API
def executeScene(String duid, String sceneId) {
    logInfo "executeScene: duid=${duid} sceneId=${sceneId}"
    try {
        Map rriot = state?.loginData?.rriot
        if (!rriot) { logWarn "executeScene: no rriot data — re-login needed"; return }
        setDeviceScene(sceneId, rriot)
        runIn(3, "pollAllVacuums")
    } catch (Exception e) {
        logWarn "executeScene error: ${e.message}"
    }
}

// ── Java TCP Socket (synchronous request/response) ────────────────────────────
Map sendLocalCommand(String ip, String duid, String localKey, String command, List params) {
    logDebug "sendLocalCommand: ip=${ip} command=${command}"

    if (!localKey) { logWarn "sendLocalCommand: no localKey for ${duid}"; return null }

    Integer id = (Integer) ((state.sequence ?: 1000) & 0xFFFFFFFF)
    state.sequence = (id + 1) & 0xFFFFFFFF
    Integer timestamp = (Integer) (now() / 1000)
    Integer protocol = 101

    Map inner = [id: id, method: command, params: params]
    String payload = JsonOutput.toJson([t: timestamp, dps: ["$protocol": JsonOutput.toJson(inner)]])
    byte[] msgBytes = buildMessage(localKey, id, protocol, timestamp, payload.getBytes("UTF-8"))

    Socket sock = null
    try {
        sock = new Socket()
        sock.connect(new InetSocketAddress(ip, ROBOROCK_LOCAL_PORT), TCP_TIMEOUT_MS)
        sock.setSoTimeout(TCP_TIMEOUT_MS)

        // Send
        OutputStream out = sock.getOutputStream()
        out.write(msgBytes)
        out.flush()
        logDebug "sendLocalCommand: sent ${msgBytes.length} bytes id=${id}"

        // Read response — Roborock sends a framed binary response
        InputStream ins = sock.getInputStream()
        byte[] response = readResponse(ins)

        if (!response || response.length < 23) {
            logWarn "sendLocalCommand: response too short (${response?.length} bytes)"
            return null
        }

        // Decrypt and parse
        Map parsed = parseResponse(response, localKey)
        if (!parsed) return null

        logDebug "sendLocalCommand: response: ${parsed}"
        return parsed

    } catch (java.net.SocketTimeoutException e) {
        logWarn "sendLocalCommand: timeout connecting/reading from ${ip}:${ROBOROCK_LOCAL_PORT}"
        return null
    } catch (java.net.ConnectException e) {
        logWarn "sendLocalCommand: connection refused at ${ip}:${ROBOROCK_LOCAL_PORT}"
        return null
    } catch (Exception e) {
        logWarn "sendLocalCommand: error: ${e.message}"
        return null
    } finally {
        try { sock?.close() } catch (Exception e) { /* ignore */ }
    }
}

byte[] readResponse(InputStream ins) {
    // Read at least the 19-byte header first to get payload length
    byte[] header = new byte[19]
    int totalRead = 0
    long deadline = System.currentTimeMillis() + TCP_TIMEOUT_MS

    while (totalRead < 19) {
        if (System.currentTimeMillis() > deadline) {
            logWarn "readResponse: timeout reading header"
            return null
        }
        int available = ins.available()
        if (available > 0) {
            int read = ins.read(header, totalRead, 19 - totalRead)
            if (read < 0) break
            totalRead += read
        } else {
            Thread.sleep(10)
        }
    }

    if (totalRead < 19) {
        logWarn "readResponse: only got ${totalRead} header bytes"
        return null
    }

    // Check version
    String version = new String(header[0..2] as byte[], "UTF-8")
    if (version != "1.0") {
        logWarn "readResponse: unexpected version '${version}'"
        return null
    }

    // Extract payload length from header bytes 17-18
    int payloadLen = ((header[17] & 0xFF) << 8) | (header[18] & 0xFF)
    int totalLen = 19 + payloadLen + 4  // header + payload + CRC32

    byte[] fullMsg = new byte[totalLen]
    System.arraycopy(header, 0, fullMsg, 0, 19)

    // Read remainder
    int remaining = totalLen - 19
    int offset = 19
    while (remaining > 0) {
        if (System.currentTimeMillis() > deadline) {
            logWarn "readResponse: timeout reading payload"
            return null
        }
        int available = ins.available()
        if (available > 0) {
            int toRead = Math.min(available, remaining)
            int read = ins.read(fullMsg, offset, toRead)
            if (read < 0) break
            offset    += read
            remaining -= read
        } else {
            Thread.sleep(10)
        }
    }

    logDebug "readResponse: read ${offset} of ${totalLen} bytes"
    return fullMsg
}

Map parseResponse(byte[] message, String localKey) {
    // Verify CRC32
    Integer crc32 = CRC32(message, message.length - 4)
    Integer expectedCrc = readInt32BE(message, message.length - 4)
    if (crc32 != expectedCrc) {
        logWarn "parseResponse: CRC mismatch"
        return null
    }

    Integer timestamp  = readInt32BE(message, 11)
    Integer protocol   = readInt16BE(message, 15)
    Integer payloadLen = readInt16BE(message, 17)

    if (protocol != 102) {
        logDebug "parseResponse: ignoring protocol ${protocol}"
        return null
    }

    byte[] payload = message[19..(19 + payloadLen - 1)]
    String key = encodeTimestamp(timestamp) + localKey + salt
    byte[] decrypted = decrypt(payload, key)

    try {
        return new JsonSlurper().parseText(new String(decrypted, "UTF-8"))
    } catch (Exception e) {
        logWarn "parseResponse: JSON parse error: ${e.message}"
        return null
    }
}

// ── Process results → push events to child device ─────────────────────────────
void processStatusResult(def child, String duid, Map resp) {
    logDebug "processStatusResult for ${child.displayName}"
    resp?.result?.each { result ->
        if (!(result instanceof Map)) return

        // Determine switch state
        Boolean isOn = ((result?.in_cleaning ?: 0).toInteger() != 0 ||
                        (result?.is_locating ?: 0).toInteger() != 0 ||
                        (result?.is_exploring ?: 0).toInteger() != 0)
        child.sendEvent(name: "switch", value: isOn ? "on" : "off")

        // State
        Integer stateCode = result?.state?.toInteger() ?: 0
        if (result?.battery?.toInteger() == 100 && stateCode == 8) stateCode = 100
        String stateVal = stateCodes[stateCode]?.toLowerCase() ?: stateCode.toString()
        child.sendEvent(name: "state", value: stateVal)

        // Battery
        if (result?.battery != null)
            child.sendEvent(name: "battery", value: result.battery.toInteger(), unit: "%")

        // Error
        Integer errCode = result?.error_code?.toInteger() ?: 0
        String errVal = errorCodes[errCode]?.toLowerCase() ?: errCode.toString()
        child.sendEvent(name: "error", value: errVal)

        // Fan power
        if (result?.fan_power != null) {
            String fp = fanPowerCodes[result.fan_power.toInteger()]?.toLowerCase() ?: result.fan_power.toString()
            child.sendEvent(name: "fanPower", value: fp)
        }

        // Clean time
        if (result?.clean_time != null) {
            Integer mins = Math.ceil(result.clean_time.toInteger() / 60).toInteger()
            child.sendEvent(name: "cleanTime (min)", value: mins)
        }

        // Clean area
        if (result?.clean_area != null) {
            Integer area = result.clean_area.toInteger() / 92903.04
            child.sendEvent(name: "cleanArea (sq ft)", value: area)
        }

        // Clean percent
        if (result?.clean_percent != null) {
            Integer pct = result.clean_percent.toInteger()
            if (pct == 0 && (result?.clean_area?.toInteger() ?: 0) > 1) pct = 100
            child.sendEvent(name: "cleanPercent (%)", value: pct, unit: "%")
        }

        // Locating
        if (result?.is_locating != null) {
            child.sendEvent(name: "locating", value: result.is_locating == 0 ? "false" : "true")
        }

        // Dust collection
        if (result?.dust_collection_status != null) {
            child.sendEvent(name: "dustCollection", value: result.dust_collection_status == 0 ? "off" : "on")
        }

        // Mop mode
        if (result?.mop_mode != null) {
            String mm = mopModeCodes[result.mop_mode.toInteger()]?.toLowerCase() ?: result.mop_mode.toString()
            child.sendEvent(name: "mopMode", value: mm)
        }

        // Dock error
        if (result?.dock_error_status != null) {
            String de = dockErrorCodes[result.dock_error_status.toInteger()]?.toLowerCase() ?: result.dock_error_status.toString()
            child.sendEvent(name: "dockError", value: de)
        }
    }
}

void processConsumableResult(def child, Map resp) {
    logDebug "processConsumableResult for ${child.displayName}"
    resp?.result?.each { result ->
        if (!(result instanceof Map)) return

        if (result?.main_brush_work_time != null) {
            Integer p = Math.max(0, (100 - Math.floor((result.main_brush_work_time.toInteger() / (life.main * 3600)) * 100).toInteger()))
            child.sendEvent(name: "remainingMainBrush (%)", value: p, unit: "%")
        }
        if (result?.side_brush_work_time != null) {
            Integer p = Math.max(0, (100 - Math.floor((result.side_brush_work_time.toInteger() / (life.side * 3600)) * 100).toInteger()))
            child.sendEvent(name: "remainingSideBrush (%)", value: p, unit: "%")
        }
        if (result?.sensor_dirty_time != null) {
            Integer p = Math.max(0, (100 - Math.floor((result.sensor_dirty_time.toInteger() / (life.sensor * 3600)) * 100).toInteger()))
            child.sendEvent(name: "remainingSensors (%)", value: p, unit: "%")
        }
        if (result?.strainer_work_times != null) {
            Integer p = Math.max(0, (100 - Math.floor((result.strainer_work_times.toInteger() / life.filter) * 100).toInteger()))
            child.sendEvent(name: "remainingFilter (%)", value: p, unit: "%")
        }
        if (result?.cleaning_brush_work_times != null) {
            Integer p = Math.max(0, (100 - Math.floor((result.cleaning_brush_work_times.toInteger() / life.highSpeed) * 100).toInteger()))
            child.sendEvent(name: "remainingHighSpeedMaintBrush (%)", value: p, unit: "%")
        }
    }
}

void processRoomMapping(def child, String duid, Map resp) {
    logDebug "processRoomMapping for ${child.displayName}"
    Map roomsMap = state?.rooms ?: [:]
    if (!roomsMap || !resp?.result) return

    Map rooms = [:]
    resp.result.each { mapping ->
        String segId  = mapping[0].toString()
        String roomId = mapping[1].toString()
        String name   = roomsMap[roomId]
        if (name) rooms[segId] = name
    }

    if (rooms) {
        String roomsList = rooms.sort { a, b -> a.key.toInteger() <=> b.key.toInteger() }
                                .collect { k, v -> "$k: $v" }.join(" | ")
        child.sendEvent(name: "roomsList", value: roomsList)
        logDebug "Updated room mapping: ${rooms}"
    }
}

// ── Cloud API calls ───────────────────────────────────────────────────────────
Map login() {
    String uri = getBaseURL() ?: settings.regionUri
    Map response = [:]
    try {
        String qs = "username=${URLEncoder.encode(settings.username, 'UTF-8')}" +
                    "&password=${URLEncoder.encode(settings.password, 'UTF-8')}" +
                    "&needtwostepauth=false"
        httpPostJson(uri: uri, path: "/api/v1/login", queryString: qs,
                     headers: ['header_clientid': generateHash(settings.username)]) { resp ->
            if (resp.status == 200) {
                response = resp.data
                if (resp.data?.msg == "success") {
                    state.loginData = resp.data
                    logInfo "Login successful"
                }
            }
        }
    } catch (Exception e) { logWarn "login error: ${e.message}" }
    return response
}

void sendEmailCode() {
    String uri = getBaseURL() ?: settings.regionUri
    try {
        String qs = "username=${URLEncoder.encode(settings.username, 'UTF-8')}&type=auth"
        httpPostJson(uri: uri, path: "/api/v1/sendEmailCode", queryString: qs,
                     headers: ['header_clientid': generateHash(settings.username)]) { resp ->
            if (resp.status == 200 && resp.data?.msg == "success")
                logInfo "Email code sent"
            else
                logWarn "sendEmailCode failed: ${resp.data?.msg}"
        }
    } catch (Exception e) { logWarn "sendEmailCode error: ${e.message}" }
}

Map loginWithCode(String pin) {
    String uri = getBaseURL() ?: settings.regionUri
    Map response = [:]
    try {
        String qs = "username=${URLEncoder.encode(settings.username, 'UTF-8')}" +
                    "&verifycode=${URLEncoder.encode(pin, 'UTF-8')}" +
                    "&verifycodetype=AUTH_EMAIL_CODE"
        httpPostJson(uri: uri, path: "/api/v1/loginWithCode", queryString: qs,
                     headers: ['header_clientid': generateHash(settings.username)]) { resp ->
            response = resp.data
            if (resp.data?.msg == "success") state.loginData = resp.data
        }
    } catch (Exception e) { logWarn "loginWithCode error: ${e.message}" }
    return response
}

Map getHomeDetail() {
    Map loginData = state?.loginData
    if (!loginData) { logWarn "getHomeDetail: no login data"; return null }
    String uri = state?.base ?: settings.regionUri
    Map result = null
    try {
        httpGet(uri: uri, path: "/api/v1/getHomeDetail",
                headers: ['header_clientid': (md5hex(settings.username).bytes.encodeBase64().toString()),
                          'Authorization': loginData.token]) { resp ->
            if (resp.status == 200) result = resp.data?.data
        }
    } catch (Exception e) { logWarn "getHomeDetail error: ${e.message}" }
    return result
}

Map getHomeData(String rrHomeId) {
    Map rriot = state?.loginData?.rriot
    if (!rriot) { logWarn "getHomeData: no rriot data"; return null }
    String path = "/v2/user/homes/${rrHomeId}"
    Map result = null
    try {
        httpGet(uri: rriot.r.a, path: path,
                headers: ['Authorization': getHawkAuthentication(rriot.u, rriot.s, rriot.h, path)]) { resp ->
            if (resp.status == 200) result = resp.data?.result
        }
    } catch (Exception e) { logWarn "getHomeData error: ${e.message}" }
    return result
}

List getDeviceScenes(String duid) {
    Map rriot = state?.loginData?.rriot
    if (!rriot) return []
    String path = "/user/scene/device/${duid}"
    List result = []
    try {
        httpGet(uri: rriot.r.a, path: path,
                headers: ['Authorization': getHawkAuthentication(rriot.u, rriot.s, rriot.h, path)]) { resp ->
            if (resp.status == 200) result = resp.data?.result ?: []
        }
    } catch (Exception e) { logWarn "getDeviceScenes error: ${e.message}" }
    return result
}

void setDeviceScene(String sceneId, Map rriot) {
    String path = "/user/scene/${sceneId}/execute"
    try {
        httpPost(uri: rriot.r.a, path: path,
                 headers: ['Authorization': getHawkAuthentication(rriot.u, rriot.s, rriot.h, path)],
                 contentType: "application/json",
                 body: JsonOutput.toJson([sceneId: sceneId])) { resp ->
            logInfo "setDeviceScene ${sceneId}: ${resp.data?.status}"
        }
    } catch (Exception e) { logWarn "setDeviceScene error: ${e.message}" }
}

String getBaseURL() {
    String uri = settings.regionUri
    String response = null
    try {
        httpPostJson(uri: uri, path: "/api/v1/getUrlByEmail",
                     queryString: "email=${URLEncoder.encode(settings.username, 'UTF-8')}") { resp ->
            if (resp.status == 200) {
                response = resp.data?.data?.url
                if (response && response != settings.regionUri) state.base = response
            }
        }
    } catch (Exception e) { logWarn "getBaseURL error: ${e.message}" }
    return response
}

// ── Message build/parse ───────────────────────────────────────────────────────
byte[] buildMessage(String localKey, Integer msgId, Integer protocol, Integer timestamp, byte[] payload) {
    String key = encodeTimestamp(timestamp) + localKey + salt
    byte[] encrypted = encrypt(payload, key)

    Integer randomInt = new Random().nextInt(900000) + 100000
    int totalLen = 23 + encrypted.length
    byte[] msg = new byte[totalLen]

    msg[0] = 49; msg[1] = 46; msg[2] = 48
    writeInt32BE(msg, msgId, 3)
    writeInt32BE(msg, randomInt, 7)
    writeInt32BE(msg, timestamp, 11)
    writeInt16BE(msg, protocol, 15)
    writeInt16BE(msg, encrypted.length, 17)
    for (int i = 0; i < encrypted.length; i++) msg[19 + i] = encrypted[i]
    writeInt32BE(msg, CRC32(msg, msg.length - 4), msg.length - 4)
    return msg
}

// ── Crypto helpers ────────────────────────────────────────────────────────────
byte[] decrypt(byte[] payload, String key) {
    byte[] k = md5bin(key)
    Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding")
    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, "AES"))
    return c.doFinal(payload)
}

byte[] encrypt(byte[] payload, String key) {
    byte[] k = md5bin(key)
    Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding")
    c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "AES"))
    return c.doFinal(payload)
}

Integer CRC32(bytes, length) {
    def crc = 0xFFFFFFFF
    for (int i = 0; i < length; i++) {
        def b = bytes[i] & 0xFF
        crc = crc ^ b
        for (int j = 7; j >= 0; j--) {
            def mask = -(crc & 1)
            crc = (crc >>> 1) ^ (0xEDB88320 & mask)
        }
    }
    return (crc ^ 0xFFFFFFFFL)
}

Integer readInt32BE(byte[] data, Integer start) {
    return (((data[start] & 0xFF) << 24) | ((data[start+1] & 0xFF) << 16) |
            ((data[start+2] & 0xFF) << 8) | (data[start+3] & 0xFF))
}

Integer readInt16BE(byte[] data, Integer start) {
    return (((data[start] & 0xFF) << 8) | (data[start+1] & 0xFF))
}

void writeInt32BE(byte[] msg, Integer value, Integer start) {
    msg[start]   = (byte)((value >> 24) & 0xFF)
    msg[start+1] = (byte)((value >> 16) & 0xFF)
    msg[start+2] = (byte)((value >> 8)  & 0xFF)
    msg[start+3] = (byte)(value & 0xFF)
}

void writeInt16BE(byte[] msg, Integer value, Integer start) {
    msg[start]   = (byte)((value >> 8) & 0xFF)
    msg[start+1] = (byte)(value & 0xFF)
}

byte[] md5bin(String input) {
    return MessageDigest.getInstance("MD5").digest(input.getBytes("UTF-8"))
}

String md5hex(String input) {
    return MessageDigest.getInstance("MD5").digest(input.getBytes("UTF-8")).encodeHex()
}

String encodeTimestamp(int timestamp) {
    String hex = new BigInteger(Long.toString(timestamp)).toString(16).padLeft(8, '0')
    List<String> h = hex.toList()
    int[] order = [5, 6, 3, 7, 1, 2, 0, 4]
    return order.collect { h[it] }.join('')
}

String generateHash(String username) {
    MessageDigest md = MessageDigest.getInstance("MD5")
    md.update(username.getBytes("UTF-8"))
    md.update(app.id.toString().getBytes("UTF-8"))
    return md.digest().encodeBase64().toString()
}

String getHawkAuthentication(String id, String secret, String key, String path) {
    Integer timestamp = now() / 1000
    String nonce = UUID.randomUUID().toString().replaceAll('-', '').take(8)
    String prestr = "$id:$secret:${nonce}:${timestamp}:${md5hex(path)}::"
    Mac mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key?.getBytes("UTF-8"), "HmacSHA256"))
    return "Hawk id=\"${id}\", s=\"${secret}\", ts=\"${timestamp}\", nonce=\"${nonce}\", mac=\"${mac.doFinal(prestr.getBytes("UTF-8")).encodeBase64()}\""
}

// ── Code maps ─────────────────────────────────────────────────────────────────
@Field static final Map errorCodes = [
    0:"No error",1:"Laser sensor fault",2:"Collision sensor fault",3:"Wheel floating",
    4:"Cliff sensor fault",5:"Main brush blocked",6:"Side brush blocked",7:"Wheel blocked",
    8:"Device stuck",9:"Dust bin missing",10:"Filter blocked",11:"Magnetic field detected",
    12:"Low battery",13:"Charging problem",14:"Battery failure",15:"Wall sensor fault",
    16:"Uneven surface",17:"Side brush failure",18:"Suction fan failure",
    19:"Unpowered charging station",20:"Unknown Error",21:"Laser pressure sensor problem",
    22:"Charge sensor problem",23:"Dock problem",24:"No-go zone or invisible wall detected",
    254:"Bin full",255:"Internal error",256:"Wifi Offline",257:"Authorization error"
]

@Field static final Map stateCodes = [
    0:"Unknown",1:"Initiating",2:"Sleeping",3:"Idle",4:"Remote Control",
    5:"Cleaning",6:"Returning Dock",7:"Manual Mode",8:"Charging",9:"Charging Error",
    10:"Paused",11:"Spot Cleaning",12:"In Error",13:"Shutting Down",14:"Updating",
    15:"Docking",16:"Go To",17:"Zone Clean",18:"Room Clean",22:"Emptying Dust Bin",
    23:"Washing the mop",26:"Going to wash the mop",28:"In call",29:"Mapping",
    100:"Charged",500:"Authorization error",501:"Authorization Requires PIN",
    502:"Waiting for Authorization PIN",503:"Authorized",504:"Error requesting PIN"
]

@Field static final Map fanPowerCodes  = [101:"Quiet",102:"Balanced",103:"Turbo",104:"Max",105:"Off",106:"Auto",108:"Max+"]
@Field static final Map mopModeCodes   = [300:"Standard",301:"Deep",302:"Custom",303:"Deep+",304:"Fast"]
@Field static final Map mopWaterModeCodes = [0:"Default",200:"Off",201:"Low",202:"Medium",203:"High",204:"Auto",207:"Custom"]
@Field static final Map dockErrorCodes = [
    0:"No error",34:"Duct Blockage",38:"Water Empty",39:"Waste Water Tank Full",
    40:"Water Filter Not Installed",42:"Check the Water Filter Has Been Correctly Installed",
    44:"Dirty Tank Latch Open",46:"No Dust Bin",53:"Cleaning Tank Full Blocked"
]

// ── Logging ───────────────────────────────────────────────────────────────────
def logInfo(msg)  { if (settings?.logEnable   != false) log.info  "${app.name} ${msg}" }
def logDebug(msg) { if (settings?.debugEnable == true)  log.debug "${app.name} ${msg}" }
def logWarn(msg)  { log.warn  "${app.name} ${msg}" }
def logError(msg) { log.error "${app.name} ${msg}" }

