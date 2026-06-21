/**
 * Leviton LDATA / LWHEM Smart Panel - Parent Driver
 * Ported from: https://github.com/rwoldberg/ldata-ha
 * Hubitat port: jthomas24
 * Version: 1.2.1
 */

import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import hubitat.helper.InterfaceUtils

metadata {
    definition(
        name: "Leviton Smart Load Center Parent",
        namespace: "jdthomas24",
        author: "Community Port from rwoldberg/ldata-ha",
        description: "Leviton Smart Panel (LDATA/LWHEM) integration with breaker monitoring and control",
        version: "1.2.1"
    ) {
        capability "Initialize"
        capability "Refresh"
        capability "PresenceSensor"

        attribute "panelVoltage",    "number"
        attribute "panelFrequency",  "number"
        attribute "totalPower",      "number"
        attribute "panelConnected",  "string"
        attribute "panelFirmware",   "string"
        attribute "wsStatus",        "string"
        attribute "lastUpdate",      "string"

        command "reconnectWebSocket", [[name:"Force WebSocket reconnect"]]
        command "deleteChildren",     [[name:"Delete all child devices (use with caution)"]]
    }

    preferences {
        input name: "email",          type: "text",     title: "My Leviton Email",     required: true
        input name: "password",       type: "password", title: "My Leviton Password",  required: true
        input name: "pollInterval",   type: "enum",     title: "REST Poll Interval (fallback / energy counters)",
              options: ["30", "60", "120", "300"], defaultValue: "60",
              description: "How often to poll REST API for breaker energy data. WebSocket handles real-time updates."
        input name: "allowBreakerControl",       type: "bool", title: "Allow Breaker Remote Control",    defaultValue: false,
              description: "Enable switch capability on breaker child devices (trip/reset remotely)"
        input name: "disableBandwidthKeepalive", type: "bool", title: "Disable Bandwidth Keepalive",     defaultValue: false,
              description: "Stop sending bandwidth:1 keepalive PUTs every minute. Disable if causing WS instability."
        input name: "debugLogging",              type: "bool", title: "Enable Debug Logging",            defaultValue: false
        input name: "wsLogging",                 type: "bool", title: "Enable WebSocket Raw Message Logging", defaultValue: false
    }
}

@groovy.transform.Field static final String BASE_URL     = "https://my.leviton.com/api"
@groovy.transform.Field static final String WS_URI       = "wss://socket.cloud.leviton.com/"
@groovy.transform.Field static final String MYAPP_ORIGIN = "https://myapp.leviton.com"
@groovy.transform.Field static final List   LEG1_POSITIONS = [
    1, 2, 5, 6, 9, 10, 13, 14, 17, 18, 21, 22, 25, 26, 29, 30,
    33, 34, 37, 38, 41, 42, 45, 46, 49, 50, 53, 54, 57, 58, 61, 62, 65, 66
]

def installed() { log.info "[LDATA] Driver installed"; initialize() }

def updated() {
    log.info "[LDATA] Preferences updated — reinitialising"
    unschedule()
    closeWebSocket()
    state.authToken = null; state.refreshToken = null; state.userId = null
    state.accountId = null; state.residenceIds = []
    state.panels = [:]; state.breakers = [:]; state.cts = [:]
    state.wsReconnectPending = false; state.wsConnected = false; state.wsLastSeen = 0
    if (settings.debugLogging) {
        log.info "[LDATA] Debug logging enabled — will auto-disable in 30 minutes"
        runIn(1800, "disableDebugLogging")
    }
    pauseExecution(1000)
    initialize()
}

def initialize() {
    log.info "[LDATA] Initialising..."
    sendEvent(name: "wsStatus", value: "Initialising")
    sendEvent(name: "presence", value: "not present")
    if (!state.authToken) {
        if (!doLogin()) { log.error "[LDATA] Login failed — check credentials"; sendEvent(name: "wsStatus", value: "Auth Failed"); return }
    }
    if (!getResidentialAccount()) { log.error "[LDATA] Could not get Residential Account"; return }
    if (!getResidences())          { log.error "[LDATA] Could not get Residence IDs"; return }
    if (!fetchAndParsePanels())    { log.warn  "[LDATA] Panel fetch failed — will retry on next poll" }
    connectWebSocket()
    schedule("0/25 * * * * ?", "wsPing")
    def interval = (settings.pollInterval ?: "60").toInteger()
    if (interval <= 30)       runEvery30Seconds("restPoll")
    else if (interval <= 60)  runEvery1Minute("restPoll")
    else if (interval <= 120) runEvery2Minutes("restPoll")
    else                      runEvery5Minutes("restPoll")
    runEvery1Minute("bandwidthKeepalive")
    log.info "[LDATA] Initialisation complete"
}

def refresh() {
    log.info "[LDATA] Manual refresh triggered"
    if (!state.authToken) { initialize(); return }
    restPoll()
}

private boolean doLogin() {
    logDebug "[LDATA] Logging in as ${settings.email}"
    try {
        def result = null
        httpPost([uri: "${BASE_URL}/Person/login?include=user", contentType: "application/json",
                  requestContentType: "application/json", headers: buildHeaders(),
                  body: JsonOutput.toJson([email: settings.email, password: settings.password]), timeout: 15]) { resp ->
            if (resp.status == 200) result = resp.data
        }
        if (result) {
            state.authToken = result.id; state.refreshToken = result.id
            state.userId = result.userId; state.fullAuth = result
            logDebug "[LDATA] Login OK, userId=${state.userId}"; return true
        }
    } catch (Exception e) { log.error "[LDATA] Login exception: ${e.message}" }
    return false
}

private boolean validateToken() {
    if (!state.authToken || !state.userId) return false
    try {
        def ok = false
        httpGet([uri: "${BASE_URL}/Person/${state.userId}/residentialPermissions",
                 contentType: "application/json", headers: buildAuthHeaders(), timeout: 15]) { resp ->
            if (resp.status == 200) ok = true
        }
        return ok
    } catch (Exception e) { logDebug "[LDATA] Token validation failed: ${e.message}"; return false }
}

private boolean ensureAuth() {
    if (state.authToken && validateToken()) return true
    logDebug "[LDATA] Token invalid — re-logging in"
    return doLogin()
}

private boolean getResidentialAccount() {
    if (state.accountId) return true
    try {
        httpGet([uri: "${BASE_URL}/Person/${state.userId}/residentialPermissions",
                 contentType: "application/json", headers: buildAuthHeaders(), timeout: 15]) { resp ->
            if (resp.status == 200 && resp.data)
                resp.data.each { item -> if (item.residentialAccountId && !state.accountId) state.accountId = item.residentialAccountId }
        }
    } catch (Exception e) { log.error "[LDATA] getResidentialAccount: ${e.message}" }
    return state.accountId != null
}

private boolean getResidences() {
    if (state.residenceIds?.size() > 0) return true
    state.residenceIds = []
    try {
        httpGet([uri: "${BASE_URL}/ResidentialAccounts/${state.accountId}/residences",
                 contentType: "application/json", headers: buildAuthHeaders(), timeout: 15]) { resp ->
            if (resp.status == 200 && resp.data) resp.data.each { res -> if (res.id) state.residenceIds << res.id.toString() }
        }
    } catch (Exception e) { logDebug "[LDATA] getResidences (list): ${e.message}" }
    if (!state.residenceIds) {
        try {
            httpGet([uri: "${BASE_URL}/ResidentialAccounts/${state.accountId}",
                     contentType: "application/json", headers: buildAuthHeaders(), timeout: 15]) { resp ->
                if (resp.status == 200 && resp.data?.primaryResidenceId) state.residenceIds << resp.data.primaryResidenceId.toString()
            }
        } catch (Exception e) { logDebug "[LDATA] getResidences (single): ${e.message}" }
    }
    try {
        httpGet([uri: "${BASE_URL}/Person/${state.userId}/residentialPermissions",
                 contentType: "application/json", headers: buildAuthHeaders(), timeout: 15]) { resp ->
            if (resp.status == 200 && resp.data)
                resp.data.each { item -> if (item.residenceId && !(item.residenceId.toString() in state.residenceIds)) state.residenceIds << item.residenceId.toString() }
        }
    } catch (Exception e) { logDebug "[LDATA] getResidences (permissions): ${e.message}" }
    state.residenceIds = state.residenceIds.unique().findAll { it }
    logDebug "[LDATA] Residence IDs: ${state.residenceIds}"
    return state.residenceIds.size() > 0
}

private boolean fetchAndParsePanels() {
    if (!ensureAuth() || !state.residenceIds) return false
    def allPanels = []
    state.residenceIds.each { rid -> fetchWHEMSPanels(rid, allPanels); fetchLDATAPanels(rid, allPanels) }
    if (!allPanels) { log.warn "[LDATA] No panels found"; return false }
    parsePanels(allPanels); return true
}

private void fetchWHEMSPanels(String residenceId, List allPanels) {
    try {
        httpGet([uri: "${BASE_URL}/Residences/${residenceId}/iotWhems",
                 contentType: "application/json", headers: buildAuthHeaders(), timeout: 20]) { resp ->
            if (resp.status == 200 && resp.data) {
                resp.data.each { panel ->
                    panel.ModuleType = "WHEMS"
                    panel.rmsVoltage    = panel.rmsVoltageA  ?: panel.rmsVoltage  ?: 0
                    panel.rmsVoltage2   = panel.rmsVoltageB  ?: panel.rmsVoltage2 ?: 0
                    panel.updateVersion = panel.version      ?: panel.updateVersion ?: "0"
                    try { bandwidthToggle(panel.id, "WHEMS") } catch (Exception ignored) {}
                    pauseExecution(2000)
                    panel.residentialBreakers = getWhemsBreakers(panel.id)
                    panel.CTs = getWhemsCtClamps(panel.id)
                    allPanels << panel
                    logDebug "[LDATA] Found WHEMS panel: ${panel.name} (${panel.id}), FW=${panel.updateVersion}"
                }
            }
        }
    } catch (Exception e) { logDebug "[LDATA] fetchWHEMSPanels error: ${e.message}" }
}

private void fetchLDATAPanels(String residenceId, List allPanels) {
    def hdrs = buildAuthHeaders(); hdrs["filter"] = '{"include":["residentialBreakers"]}'
    try {
        httpGet([uri: "${BASE_URL}/Residences/${residenceId}/residentialBreakerPanels",
                 contentType: "application/json", headers: hdrs, timeout: 20]) { resp ->
            if (resp.status == 200 && resp.data) {
                resp.data.each { panel ->
                    panel.ModuleType = "LDATA"; panel.updateVersion = panel.updateVersion ?: "0"
                    try { bandwidthToggle(panel.id, "LDATA") } catch (Exception ignored) {}
                    allPanels << panel
                    logDebug "[LDATA] Found LDATA panel: ${panel.name} (${panel.id})"
                }
            }
        }
    } catch (Exception e) { logDebug "[LDATA] fetchLDATAPanels error: ${e.message}" }
}

private List getWhemsBreakers(String panelId) {
    def hdrs = buildAuthHeaders(); hdrs["filter"] = "{}"
    try {
        def result = null
        httpGet([uri: "${BASE_URL}/IotWhems/${panelId}/residentialBreakers", contentType: "application/json", headers: hdrs, timeout: 15]) { resp ->
            if (resp.status == 200) result = resp.data
        }
        return result ?: []
    } catch (Exception e) { logDebug "[LDATA] getWhemsBreakers error: ${e.message}"; return [] }
}

private List getWhemsCtClamps(String panelId) {
    def hdrs = buildAuthHeaders(); hdrs["filter"] = "{}"
    try {
        def result = null
        httpGet([uri: "${BASE_URL}/IotWhems/${panelId}/iotCts", contentType: "application/json", headers: hdrs, timeout: 15]) { resp ->
            if (resp.status == 200) result = resp.data
        }
        return result ?: []
    } catch (Exception e) { logDebug "[LDATA] getWhemsCtClamps error: ${e.message}"; return [] }
}

private void parsePanels(List panelsJson) {
    def newBreakers = [:]; def newCts = [:]; def newPanels = [:]
    panelsJson.each { panel ->
        def panelId = panel.id?.toString(); if (!panelId) return
        def fwStr = panel.updateVersion?.toString() ?: "0"
        def fwMajor = 0; try { fwMajor = fwStr.split("\\.")[0].toInteger() } catch (ignored) {}
        def panelData = [
            id: panelId, name: panel.name ?: "Panel ${panelId}", firmware: fwStr,
            model: panel.model ?: "unknown", panel_type: panel.ModuleType ?: "WHEMS",
            connected: panel.connected ?: false, serialNumber: panelId, fwMajor: fwMajor
        ]
        if (panel.model == "DAU" && panel.status == "READY") panelData.connected = true
        def v1 = safeFloat(panel, "rmsVoltage"); def v2 = safeFloat(panel, "rmsVoltage2")
        panelData.voltage1 = v1; panelData.voltage2 = v2; panelData.voltage = (v1 + v2) / 2.0
        def f1 = safeFloat(panel, "frequencyA"); def f2 = safeFloat(panel, "frequencyB")
        panelData.frequency1 = f1; panelData.frequency2 = f2; panelData.frequency = (f1 + f2) / 2.0
        newPanels[panelId] = panelData
        sendEvent(name: "panelVoltage",   value: roundF(panelData.voltage, 1))
        sendEvent(name: "panelFrequency", value: roundF(panelData.frequency, 2))
        sendEvent(name: "panelFirmware",  value: fwStr)
        sendEvent(name: "panelConnected", value: panelData.connected ? "online" : "offline")

        def cts = panel.CTs ?: []
        cts.each { ct ->
            if (ct.usageType && ct.usageType != "NOT_USED") {
                def ctId = ct.id?.toString(); if (!ctId) return
                def ctData = [
                    id: ctId, panel_id: panelId, name: ct.usageType,
                    channel: ct.channel?.toString() ?: "?",
                    power1: safeFloat(ct, "activePower"),   power2: safeFloat(ct, "activePower2"),
                    current1: safeFloat(ct, "rmsCurrent"),  current2: safeFloat(ct, "rmsCurrent2"),
                    consumption1: safeFloat(ct, "energyConsumption"), consumption2: safeFloat(ct, "energyConsumption2"),
                    import1: safeFloat(ct, "energyImport"), import2: safeFloat(ct, "energyImport2")
                ]
                ctData.power = ctData.power1 + ctData.power2
                ctData.current = (ctData.current1 + ctData.current2) / 2.0
                ctData.consumption = ctData.consumption1 + ctData.consumption2
                ctData.importEnergy = ctData.import1 + ctData.import2
                newCts[ctId] = ctData; ensureCtChild(ctData)
            }
        }

        def breakerList = panel.residentialBreakers ?: []
        Float totalPower = 0.0
        breakerList.each { breaker ->
            def model = breaker.model
            if (!model || model == "NONE-2" || model == "NONE-1") return
            def bId = breaker.id?.toString()
            def pos = breaker.position ?: 0
            def isLeg1 = LEG1_POSITIONS.contains(pos)
            def poles = breaker.poles ?: 1
            def bd = [
                id: bId, panel_id: panelId, name: breaker.name ?: "Breaker ${pos}",
                position: pos, leg: isLeg1 ? 1 : 2, poles: poles,
                rating: breaker.currentRating, model: model,
                serialNumber: breaker.serialNumber, hardware: breaker.hwVersion,
                firmware: breaker.firmwareVersionMeter,
                state: breaker.currentState ?: "ManualON",
                remoteState: (breaker.remoteState in [null, ""]) ? "RemoteON" : breaker.remoteState,
                canRemoteOn: breaker.canRemoteOn ?: false, blinkLED: breaker.blinkLED ?: false,
                branch_type: breaker.branchType ?: ""
            ]
            def rawP = safeFloat(breaker, "power"); def rawP2 = safeFloat(breaker, "power2")
            if (isLeg1) { bd.power1 = rawP; bd.power2 = rawP2 } else { bd.power1 = rawP2; bd.power2 = rawP }
            bd.power = bd.power1 + bd.power2
            def rawC = safeFloat(breaker, "rmsCurrent"); def rawC2 = safeFloat(breaker, "rmsCurrent2")
            if (isLeg1) { bd.current1 = rawC; bd.current2 = rawC2 } else { bd.current1 = rawC2; bd.current2 = rawC }
            bd.current = (poles == 2) ? ((bd.current1 + bd.current2) / 2.0) : (bd.current1 + bd.current2)
            def rawV = safeFloat(breaker, "rmsVoltage"); def rawV2 = safeFloat(breaker, "rmsVoltage2")
            if (isLeg1) { bd.voltage1 = rawV; bd.voltage2 = rawV2 } else { bd.voltage1 = rawV2; bd.voltage2 = rawV }
            bd.voltage = bd.voltage1 + bd.voltage2
            bd.frequency = (poles == 2) ? ((safeFloat(breaker, "lineFrequency") + safeFloat(breaker, "lineFrequency2")) / 2.0) : safeFloat(breaker, "lineFrequency")
            bd.consumption1 = safeFloat(breaker, "energyConsumption"); bd.consumption2 = safeFloat(breaker, "energyConsumption2")
            bd.consumption = bd.consumption1 + bd.consumption2
            bd.importEnergy1 = safeFloat(breaker, "energyImport"); bd.importEnergy2 = safeFloat(breaker, "energyImport2")
            bd.importEnergy = bd.importEnergy1 + bd.importEnergy2
            newBreakers[bId] = bd; totalPower += bd.power as Float
            ensureBreakerChild(bd)
        }
        newPanels[panelId].totalPower = totalPower
        sendEvent(name: "totalPower", value: roundF(totalPower, 1))
    }
    state.panels = newPanels; state.breakers = newBreakers; state.cts = newCts
    sendEvent(name: "lastUpdate", value: new Date().toString())
    logDebug "[LDATA] parsePanels: ${newPanels.size()} panels, ${newBreakers.size()} breakers, ${newCts.size()} CTs"
}

private void ensureBreakerChild(Map bd) {
    def dni = "LDATA-BREAKER-${bd.id}"
    def child = getChildDevice(dni)
    if (!child) {
        child = addChildDevice("jdthomas24", "Leviton Smart Load Center Child", dni,
            [label: "${device.displayName} - ${bd.name} (${bd.panel_id})", isComponent: false])
        log.info "[LDATA] Created breaker child: ${child.label}"
    }
    updateBreakerChild(child, bd)
}

private void ensureCtChild(Map ctd) {
    def dni = "LDATA-CT-${ctd.panel_id}-${ctd.id}"
    def child = getChildDevice(dni)
    if (!child) {
        child = addChildDevice("jdthomas24", "Leviton Smart Load Center Child", dni,
            [label: "${device.displayName} - CT ${ctd.name} (Ch${ctd.channel})", isComponent: false])
        log.info "[LDATA] Created CT child: ${child.label}"
    }
    updateCtChild(child, ctd)
}

private void updateBreakerChild(def child, Map bd) {
    def isOn = (bd.state == "ManualON" && bd.remoteState == "RemoteON")
    child.parse([
        [name: "switch",            value: isOn ? "on" : "off"],
        [name: "power",             value: roundF(bd.power, 1)],
        [name: "current",           value: roundF(bd.current, 3)],
        [name: "voltage",           value: roundF(bd.voltage, 1)],
        [name: "frequency",         value: roundF(bd.frequency, 2)],
        [name: "ampRating",         value: bd.rating],
        [name: "position",          value: bd.position],
        [name: "leg",               value: bd.leg],
        [name: "poles",             value: bd.poles],
        [name: "breakerModel",      value: bd.model],
        [name: "canRemoteOn",       value: bd.canRemoteOn],
        [name: "breakerState",      value: bd.state],
        [name: "remoteState",       value: bd.remoteState],
        [name: "energyConsumption", value: roundF(bd.consumption, 3)],
        [name: "energyImport",      value: roundF(bd.importEnergy, 3)],
        [name: "deviceType",        value: "breaker"],
        [name: "breakerId",         value: bd.id]
    ])
}

private void updateCtChild(def child, Map ctd) {
    child.parse([
        [name: "power",             value: roundF(ctd.power, 1)],
        [name: "current",           value: roundF(ctd.current, 3)],
        [name: "energyConsumption", value: roundF(ctd.consumption, 3)],
        [name: "energyImport",      value: roundF(ctd.importEnergy, 3)],
        [name: "channel",           value: ctd.channel],
        [name: "deviceType",        value: "ct"],
        [name: "ctId",              value: ctd.id]
    ])
}

def deleteChildren() {
    log.warn "[LDATA] Deleting all child devices"
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

def breakerOn(String breakerId) {
    if (!settings.allowBreakerControl) { log.warn "[LDATA] Breaker control disabled"; return }
    if (!ensureAuth()) { log.error "[LDATA] breakerOn: auth failed"; return }
    def hdrs = buildAuthHeaders(); hdrs["Referer"] = "https://my.leviton.com/home/residential-breakers/${breakerId}/settings"
    try {
        httpPutJson([uri: "${BASE_URL}/ResidentialBreakers/${breakerId}", headers: hdrs, body: [remoteOn: true], timeout: 15]) { resp ->
            if (resp.status == 200) { def child = getChildDevice("LDATA-BREAKER-${breakerId}"); if (child) child.parse([[name: "switch", value: "on"]]) }
            else log.warn "[LDATA] breakerOn HTTP ${resp.status}"
        }
    } catch (Exception e) { log.error "[LDATA] breakerOn error: ${e.message}" }
}

def breakerOff(String breakerId) {
    if (!settings.allowBreakerControl) { log.warn "[LDATA] Breaker control disabled"; return }
    if (!ensureAuth()) { log.error "[LDATA] breakerOff: auth failed"; return }
    def hdrs = buildAuthHeaders(); hdrs["Referer"] = "https://my.leviton.com/home/residential-breakers/${breakerId}/settings"
    try {
        httpPutJson([uri: "${BASE_URL}/ResidentialBreakers/${breakerId}", headers: hdrs, body: [remoteTrip: true], timeout: 15]) { resp ->
            if (resp.status == 200) { def child = getChildDevice("LDATA-BREAKER-${breakerId}"); if (child) child.parse([[name: "switch", value: "off"]]) }
            else log.warn "[LDATA] breakerOff HTTP ${resp.status}"
        }
    } catch (Exception e) { log.error "[LDATA] breakerOff error: ${e.message}" }
}

private void bandwidthToggle(String panelId, String panelType = "WHEMS") {
    def url = (panelType == "LDATA") ? "${BASE_URL}/ResidentialBreakerPanels/${panelId}" : "${BASE_URL}/IotWhems/${panelId}"
    def hdrs = buildAuthHeaders()
    try {
        httpPutJson([uri: url, headers: hdrs, body: [bandwidth: 1], timeout: 5]) {}
        httpPutJson([uri: url, headers: hdrs, body: [bandwidth: 0], timeout: 5]) {}
        httpPutJson([uri: url, headers: hdrs, body: [bandwidth: 1], timeout: 5]) {}
    } catch (Exception e) { logDebug "[LDATA] bandwidthToggle error: ${e.message}" }
}

def bandwidthKeepalive() {
    if (settings.disableBandwidthKeepalive || !state.panels || !state.authToken) return
    state.panels.each { panelId, panel ->
        def url = (panel.panel_type == "LDATA") ? "${BASE_URL}/ResidentialBreakerPanels/${panelId}" : "${BASE_URL}/IotWhems/${panelId}"
        try { httpPutJson([uri: url, headers: buildAuthHeaders(), body: [bandwidth: 1], timeout: 5]) {}; logDebug "[LDATA] Bandwidth keepalive: panel ${panelId}" }
        catch (Exception e) { logDebug "[LDATA] Bandwidth keepalive error: ${e.message}" }
    }
}

def restPoll() {
    if (!state.authToken || !state.panels) { logDebug "[LDATA] restPoll: skipped"; return }
    logDebug "[LDATA] REST poll starting"
    def updated = false
    state.panels.each { panelId, panelData ->
        try {
            getWhemsBreakers(panelId)?.each { rawBreaker ->
                def bModel = rawBreaker.model
                if (!bModel || bModel == "NONE-2" || bModel == "NONE-1") return
                def bId = rawBreaker.id?.toString()
                if (!bId || !state.breakers.containsKey(bId)) return
                def existing = state.breakers[bId] as Map
                applyBreakerUpdate(bId, existing, rawBreaker, "REST")
                state.breakers[bId] = existing
                def child = getChildDevice("LDATA-BREAKER-${bId}")
                if (child) updateBreakerChild(child, existing)
                updated = true
            }
            recalcTotalPower(panelId)
            getWhemsCtClamps(panelId)?.each { rawCt ->
                if (rawCt.usageType == "NOT_USED") return
                def ctId = rawCt.id?.toString(); if (!ctId) return
                def existing = state.cts[ctId] as Map; if (!existing) return
                applyCtUpdate(existing, rawCt); state.cts[ctId] = existing
                def child = getChildDevice("LDATA-CT-${panelId}-${ctId}")
                if (child) updateCtChild(child, existing)
                updated = true
            }
        } catch (Exception e) { log.warn "[LDATA] restPoll error for panel ${panelId}: ${e.message}" }
    }
    if (updated) sendEvent(name: "lastUpdate", value: new Date().toString())
    logDebug "[LDATA] REST poll complete"
}

private void applyBreakerUpdate(String breakerId, Map existing, Map raw, String source) {
    def leg = existing.leg ?: 1; def poles = existing.poles ?: 1
    if (raw.containsKey("power") || raw.containsKey("power2")) {
        def rawP = raw.containsKey("power") ? floatOrDefault(raw.power, 0) : null
        def rawP2 = raw.containsKey("power2") ? floatOrDefault(raw.power2, 0) : null
        if (leg == 1) { if (rawP != null) existing.power1 = rawP; if (rawP2 != null) existing.power2 = rawP2 }
        else          { if (rawP != null) existing.power2 = rawP; if (rawP2 != null) existing.power1 = rawP2 }
        existing.power = (existing.power1 ?: 0) + (existing.power2 ?: 0)
    }
    if (raw.containsKey("rmsCurrent") || raw.containsKey("rmsCurrent2")) {
        def rawC = raw.containsKey("rmsCurrent") ? floatOrDefault(raw.rmsCurrent, 0) : null
        def rawC2 = raw.containsKey("rmsCurrent2") ? floatOrDefault(raw.rmsCurrent2, 0) : null
        if (leg == 1) { if (rawC != null) existing.current1 = rawC; if (rawC2 != null) existing.current2 = rawC2 }
        else          { if (rawC != null) existing.current2 = rawC; if (rawC2 != null) existing.current1 = rawC2 }
        def c1 = existing.current1 ?: 0; def c2 = existing.current2 ?: 0
        existing.current = (poles == 2) ? ((c1 + c2) / 2.0) : (c1 + c2)
    }
    if (raw.containsKey("rmsVoltage") || raw.containsKey("rmsVoltage2")) {
        def rawV = raw.containsKey("rmsVoltage") ? floatOrDefault(raw.rmsVoltage, 0) : null
        def rawV2 = raw.containsKey("rmsVoltage2") ? floatOrDefault(raw.rmsVoltage2, 0) : null
        if (leg == 1) { if (rawV != null) existing.voltage1 = rawV; if (rawV2 != null) existing.voltage2 = rawV2 }
        else          { if (rawV != null) existing.voltage2 = rawV; if (rawV2 != null) existing.voltage1 = rawV2 }
        existing.voltage = (existing.voltage1 ?: 0) + (existing.voltage2 ?: 0)
    }
    if (raw.currentState) existing.state = raw.currentState
    if (raw.remoteState != null) existing.remoteState = (raw.remoteState == "") ? "RemoteON" : raw.remoteState
    if (raw.connected != null) existing.connected = raw.connected
    if (raw.blinkLED  != null) existing.blinkLED  = raw.blinkLED
    if (raw.containsKey("energyConsumption") || raw.containsKey("energyConsumption2")) {
        if (raw.containsKey("energyConsumption"))  existing.consumption1 = floatOrDefault(raw.energyConsumption,  existing.consumption1 ?: 0)
        if (raw.containsKey("energyConsumption2")) existing.consumption2 = floatOrDefault(raw.energyConsumption2, existing.consumption2 ?: 0)
        existing.consumption = (existing.consumption1 ?: 0) + (existing.consumption2 ?: 0)
    }
    if (raw.containsKey("energyImport") || raw.containsKey("energyImport2")) {
        if (raw.containsKey("energyImport"))  existing.importEnergy1 = floatOrDefault(raw.energyImport,  existing.importEnergy1 ?: 0)
        if (raw.containsKey("energyImport2")) existing.importEnergy2 = floatOrDefault(raw.energyImport2, existing.importEnergy2 ?: 0)
        existing.importEnergy = (existing.importEnergy1 ?: 0) + (existing.importEnergy2 ?: 0)
    }
}

private void applyCtUpdate(Map existing, Map raw) {
    if (raw.containsKey("activePower") || raw.containsKey("activePower2")) {
        if (raw.containsKey("activePower"))  existing.power1 = floatOrDefault(raw.activePower,  existing.power1 ?: 0)
        if (raw.containsKey("activePower2")) existing.power2 = floatOrDefault(raw.activePower2, existing.power2 ?: 0)
        existing.power = (existing.power1 ?: 0) + (existing.power2 ?: 0)
    }
    if (raw.containsKey("rmsCurrent") || raw.containsKey("rmsCurrent2")) {
        if (raw.containsKey("rmsCurrent"))  existing.current1 = floatOrDefault(raw.rmsCurrent,  existing.current1 ?: 0)
        if (raw.containsKey("rmsCurrent2")) existing.current2 = floatOrDefault(raw.rmsCurrent2, existing.current2 ?: 0)
        existing.current = ((existing.current1 ?: 0) + (existing.current2 ?: 0)) / 2.0
    }
    if (raw.containsKey("energyConsumption") || raw.containsKey("energyConsumption2")) {
        if (raw.containsKey("energyConsumption"))  existing.consumption1 = floatOrDefault(raw.energyConsumption,  existing.consumption1 ?: 0)
        if (raw.containsKey("energyConsumption2")) existing.consumption2 = floatOrDefault(raw.energyConsumption2, existing.consumption2 ?: 0)
        existing.consumption = (existing.consumption1 ?: 0) + (existing.consumption2 ?: 0)
    }
    if (raw.containsKey("energyImport") || raw.containsKey("energyImport2")) {
        if (raw.containsKey("energyImport"))  existing.import1 = floatOrDefault(raw.energyImport,  existing.import1 ?: 0)
        if (raw.containsKey("energyImport2")) existing.import2 = floatOrDefault(raw.energyImport2, existing.import2 ?: 0)
        existing.importEnergy = (existing.import1 ?: 0) + (existing.import2 ?: 0)
    }
}

private void recalcTotalPower(String panelId) {
    Float total = 0.0
    state.breakers?.each { bId, bd -> if (bd?.panel_id == panelId) total += floatOrDefault(bd?.power, 0.0f) }
    if (state.panels[panelId]) state.panels[panelId].totalPower = total
    sendEvent(name: "totalPower", value: roundF(total, 1))
}

def connectWebSocket() {
    logDebug "[LDATA] Connecting WebSocket to ${WS_URI}"
    sendEvent(name: "wsStatus", value: "Connecting")
    try {
        interfaces.webSocket.connect(WS_URI, headers: [
            "Origin": MYAPP_ORIGIN, "Cache-Control": "no-cache", "Pragma": "no-cache",
            "Sec-Fetch-Dest": "empty", "Sec-Fetch-Mode": "websocket", "Sec-Fetch-Site": "cross-site", "DNT": "1"
        ], ignoreSSLIssues: true)
    } catch (Exception e) {
        log.error "[LDATA] WebSocket connect error: ${e.message}"
        sendEvent(name: "wsStatus", value: "Error")
        if (!state.wsReconnectPending) { state.wsReconnectPending = true; runIn(30, "wsReconnect") }
    }
}

def closeWebSocket() {
    state.wsConnected = false
    try { interfaces.webSocket.close() } catch (ignored) {}
    sendEvent(name: "wsStatus", value: "Disconnected")
    sendEvent(name: "presence", value: "not present")
}

def reconnectWebSocket() {
    log.info "[LDATA] Manual WebSocket reconnect"
    state.wsReconnectPending = false; closeWebSocket(); pauseExecution(2000); connectWebSocket()
}

def wsReconnect() { state.wsReconnectPending = false; connectWebSocket() }

def wsPing() {
    if (!state.wsConnected) return
    def age = (now() - (state.wsLastSeen ?: 0)) / 1000
    if (age > 90) {
        log.warn "[LDATA] WS watchdog: no server message in ${age.toInteger()}s — reconnecting"
        state.wsConnected = false; closeWebSocket(); runIn(2, "connectWebSocket")
    } else {
        logDebug "[LDATA] WS watchdog: last message ${age.toInteger()}s ago"
    }
}

def disableDebugLogging() {
    log.info "[LDATA] Auto-disabling debug logging"
    device.updateSetting("debugLogging", [value: false, type: "bool"])
}

def webSocketStatus(String status) {
    logDebug "[LDATA] WS status: ${status}"
    if (status.startsWith("status: open")) {
        sendEvent(name: "wsStatus", value: "Authenticating")
        interfaces.webSocket.sendMessage(JsonOutput.toJson(buildWsAuthPayload()))
    } else if (status.startsWith("status: closing")) {
        // closing = server initiated close, not yet fully closed — just log, don't reconnect yet
        state.wsConnected = false
        sendEvent(name: "wsStatus", value: "Disconnected")
        sendEvent(name: "presence", value: "not present")
        logDebug "[LDATA] WebSocket closing: ${status}"
    } else if (status.startsWith("status: closed") || status.contains("failure")) {
        // closed = fully shut down — safe to reconnect now
        state.wsConnected = false
        sendEvent(name: "wsStatus", value: "Disconnected")
        sendEvent(name: "presence", value: "not present")
        if (status.contains("failure")) log.warn "[LDATA] WebSocket failure: ${status}"
        else logDebug "[LDATA] WebSocket closed: ${status}"
        if (!state.wsReconnectPending) { state.wsReconnectPending = true; runIn(5, "wsReconnect") }
    }
}

def parse(String message) {
    if (settings.wsLogging) log.debug "[LDATA] WS raw: ${message}"
    state.wsLastSeen = now()
    try {
        def payload = new JsonSlurper().parseText(message)
        if (payload.type == "status" && payload.status == "not ready") {
            logDebug "[LDATA] WS heartbeat: not ready (${payload.connectionId})"; return
        }
        if (payload.type == "status" && payload.status == "ready") {
            logDebug "[LDATA] WS authenticated — sending subscriptions"
            state.wsConnected = true; state.wsReconnectPending = false
            sendEvent(name: "wsStatus", value: "Connected")
            sendEvent(name: "presence", value: "present")
            sendWsSubscriptions(); return
        }
        if (payload.error) { log.error "[LDATA] WS auth error: ${payload.error}"; sendEvent(name: "wsStatus", value: "Auth Error"); return }
        if (payload.type == "notification") handleWsNotification(payload.notification)
    } catch (Exception e) { log.warn "[LDATA] WS parse error: ${e.message} | raw: ${message?.take(120)}" }
}

private Map buildWsAuthPayload() {
    if (state.fullAuth) return [token: state.fullAuth]
    return [token: [id: state.authToken, userId: state.userId, ttl: 5184000,
                    created: new Date().format("yyyy-MM-dd'T'HH:mm:ss.000'Z'"), scopes: null]]
}

// v1.2.1: Subscriptions are now queued and sent in small batches via runIn,
// instead of all ~70+ messages in one tight synchronous loop. Right after a
// reconnect the server tends to push an initial snapshot per subscription —
// sending everything at once meant the replies could all land in the same
// burst, which is what was tripping Hubitat's "excessive hub load"
// protection. Spreading the sends over a few seconds spreads the replies out
// too, alongside the per-event dedup fix already in the child driver.
private void sendWsSubscriptions() {
    def subs = []
    state.residenceIds?.each { resId -> subs << [type: "subscribe", subscription: [modelName: "Residence",           modelId: resId.toString()]] }
    state.panels?.each       { panelId, panel -> subs << [type: "subscribe", subscription: [modelName: "IotWhem",    modelId: panelId.toString()]] }
    state.breakers?.each     { bId, bd -> subs << [type: "subscribe", subscription: [modelName: "ResidentialBreaker", modelId: bId.toString()]] }
    state.cts?.each          { ctId, ctd -> subs << [type: "subscribe", subscription: [modelName: "IotCt",           modelId: ctId.toString()]] }
    logDebug "[LDATA] Queuing ${subs.size()} WS subscriptions for staggered send"
    state.subQueue = subs.collect { JsonOutput.toJson(it) }
    state.subQueueTotal = subs.size()
    sendNextSubscriptionBatch()
}

// v1.2.1: Sends a small batch of queued subscription messages, then
// reschedules itself one second later for the next batch until the queue is
// empty. Batch size of 10 keeps each tick lightweight without dragging the
// overall resubscribe-after-reconnect process out too long.
def sendNextSubscriptionBatch() {
    def queue = state.subQueue ?: []
    if (queue.isEmpty()) {
        logDebug "[LDATA] All ${state.subQueueTotal ?: 0} subscriptions sent"
        return
    }
    def batchSize = 10
    def batch     = queue.take(batchSize)
    def remaining = queue.drop(batchSize)
    state.subQueue = remaining
    batch.each { msg ->
        try { interfaces.webSocket.sendMessage(msg) }
        catch (Exception e) { logDebug "[LDATA] Subscription send error: ${e.message}" }
    }
    logDebug "[LDATA] Sent batch of ${batch.size()} subscriptions (${remaining.size()} remaining)"
    if (!remaining.isEmpty()) {
        runIn(1, "sendNextSubscriptionBatch")
    }
}

private void handleWsNotification(Map notification) {
    if (!notification) return
    def modelName = notification.modelName; def data = notification.data
    if (!data) return
    logDebug "[LDATA] WS notification: ${modelName} ${notification.modelId}"
    switch (modelName) {
        case "ResidentialBreaker": handleWsBreakerUpdate(data, notification.modelId); break
        case "IotCt":              handleWsCtUpdate(data, notification.modelId); break
        case "IotWhem":            handleWsPanelUpdate(data); break
        default:                   logDebug "[LDATA] WS unhandled model: ${modelName}"
    }
    sendEvent(name: "lastUpdate", value: new Date().toString())
}

private void handleWsBreakerUpdate(Map data, def modelId) {
    def bId = (data.id ?: modelId)?.toString()
    if (!bId || !state.breakers?.containsKey(bId)) return
    def existing = state.breakers[bId] as Map
    applyBreakerUpdate(bId, existing, data, "WS")
    state.breakers[bId] = existing
    recalcTotalPower(existing.panel_id)
    def child = getChildDevice("LDATA-BREAKER-${bId}")
    if (child) updateBreakerChild(child, existing)
}

private void handleWsCtUpdate(Map data, def modelId) {
    def ctId = (data.id ?: modelId)?.toString()
    if (!ctId || !state.cts?.containsKey(ctId)) return
    def existing = state.cts[ctId] as Map
    applyCtUpdate(existing, data); state.cts[ctId] = existing
    def child = getChildDevice("LDATA-CT-${existing.panel_id}-${ctId}")
    if (child) updateCtChild(child, existing)
}

private void handleWsPanelUpdate(Map data) {
    def panelId = data.id?.toString()
    if (!panelId || !state.panels?.containsKey(panelId)) return
    def panel = state.panels[panelId] as Map
    if (data.ResidentialBreaker instanceof List) data.ResidentialBreaker.each { handleWsBreakerUpdate(it as Map, it.id) }
    if (data.IotCt instanceof List)              data.IotCt.each              { handleWsCtUpdate(it as Map, it.id) }
    if (data.connected != null) panel.connected = data.connected
    def v1Raw = data.rmsVoltage ?: data.rmsVoltageA; def v2Raw = data.rmsVoltage2 ?: data.rmsVoltageB
    if (v1Raw != null) panel.voltage1 = floatOrDefault(v1Raw, panel.voltage1 ?: 0)
    if (v2Raw != null) panel.voltage2 = floatOrDefault(v2Raw, panel.voltage2 ?: 0)
    if (v1Raw != null || v2Raw != null) { panel.voltage = ((panel.voltage1 ?: 0) + (panel.voltage2 ?: 0)) / 2.0; sendEvent(name: "panelVoltage", value: roundF(panel.voltage, 1)) }
    if (data.frequencyA != null) panel.frequency1 = floatOrDefault(data.frequencyA, panel.frequency1 ?: 0)
    if (data.frequencyB != null) panel.frequency2 = floatOrDefault(data.frequencyB, panel.frequency2 ?: 0)
    if (data.frequencyA != null || data.frequencyB != null) { panel.frequency = ((panel.frequency1 ?: 0) + (panel.frequency2 ?: 0)) / 2.0; sendEvent(name: "panelFrequency", value: roundF(panel.frequency, 2)) }
    state.panels[panelId] = panel
}

private Map buildHeaders() {
    return ["Accept": "application/json, text/plain, */*", "Accept-Language": "en-US,en;q=0.5",
            "Content-Type": "application/json", "cache-control": "no-cache", "pragma": "no-cache",
            "user-agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0",
            "host": "my.leviton.com", "Origin": MYAPP_ORIGIN, "Referer": "${MYAPP_ORIGIN}/",
            "Connection": "keep-alive", "DNT": "1",
            "Sec-Fetch-Dest": "empty", "Sec-Fetch-Mode": "cors", "Sec-Fetch-Site": "same-site"]
}

private Map buildAuthHeaders() { def h = buildHeaders(); h["authorization"] = state.authToken ?: ""; return h }

private float safeFloat(Map obj, String key, float defaultVal = 0.0) {
    try { def v = obj[key]; if (v == null) return defaultVal; return v.toFloat() } catch (ignored) { return defaultVal }
}
private float floatOrDefault(def value, float defaultVal = 0.0) {
    try { if (value == null) return defaultVal; return value.toFloat() } catch (ignored) { return defaultVal }
}
private float roundF(def value, int decimals = 1) {
    try { if (value == null) return 0.0f; return value.toFloat().round(decimals) } catch (ignored) { return 0.0f }
}
private void logDebug(String msg) { if (settings.debugLogging) log.debug msg }
