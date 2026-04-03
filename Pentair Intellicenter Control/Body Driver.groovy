metadata {
    definition(
        name: "Pentair IntelliCenter Body",
        namespace: "intellicenter",
        author: "jdthomas24",
        description: "Pool / Spa controller — pump, temperature and heat control"
    ) {
        attribute "switch",          "string"
        attribute "temperature",     "number"
        attribute "heatingSetpoint", "number"
        attribute "maxSetTemp",      "number"
        attribute "heaterMode",      "string"
        attribute "heatSource",      "string"
        attribute "bodyStatus",      "string"
        attribute "tile",            "string"
        attribute "heatLock",        "string"

        command "refresh"

        // ── Pump ──────────────────────────────────────────────────
        command "Turn On"    // Start pump only, no heat change
        command "Turn Off"   // Stop pump completely

        // ── Heat ──────────────────────────────────────────────────
        // "Heat Off" stops the heater but leaves the pump running.
        // Different from "Turn Off" which stops the pump entirely.
        command "Heat Off"

        // "Heat And Start" — sets temp + source and starts pump in one action.
        // If pump is already running it just applies the heat settings.
        command "Heat And Start", [
            [name: "degrees*", type: "NUMBER",  description: "Target temperature °F"],
            [name: "source*",  type: "ENUM",    description: "Heat source",
             constraints: ["Heater", "Solar Only", "Solar Preferred", "Heat Pump", "Heat Pump Preferred"]]
        ]

        // ── Individual controls (still available on device page) ──
        command "Set Temperature", [[name: "degrees*", type: "NUMBER", description: "Set point °F (40–104)"]]
        command "Set Heat Source",  [[name: "source*",  type: "ENUM",
            constraints: ["Off", "Heater", "Solar Only", "Solar Preferred", "Heat Pump", "Heat Pump Preferred"]]]

        command "Disable Heat"
        command "Enable Heat"
    }

    preferences {
        input "minSetPoint",  "number", title: "Minimum Set Point (°F)",      defaultValue: 40,  required: true
        input "maxSetPoint",  "number", title: "Maximum Set Point (°F)",       defaultValue: 104, required: true
        input "endpointBase", "text",   title: "App Endpoint Base (auto-set)", required: false
        input "debugMode",    "bool",   title: "Debug Logging",                defaultValue: false
    }
}

// ============================================================
// ===================== LIFECYCLE ===========================
// ============================================================
def installed() {
    log.info "IntelliCenter Body installed: ${device.displayName}"
    sendEvent(name: "heatLock", value: "unlocked")
    debounceTile()
}

def updated() {
    log.info "IntelliCenter Body updated: ${device.displayName}"
    debounceTile()
}

// ============================================================
// ===================== PUMP ON / OFF =======================
// ============================================================
def on() {
    if (debugMode) log.debug "${device.displayName}: on()"
    sendEvent(name: "switch",     value: "on")
    sendEvent(name: "bodyStatus", value: "On")
    parent?.setBodyStatus(device.deviceNetworkId, "ON")
    debounceTile()
}

def off() {
    if (debugMode) log.debug "${device.displayName}: off()"
    sendEvent(name: "switch",     value: "off")
    sendEvent(name: "bodyStatus", value: "Off")
    parent?.setBodyStatus(device.deviceNetworkId, "OFF")
    debounceTile()
}

// ============================================================
// ===================== HEAT OFF ============================
// Stops the heater only — pump keeps running.
// Sets heat source to Off on the controller.
// ============================================================
def "Heat Off"() {
    if (debugMode) log.debug "${device.displayName}: Heat Off — setting HTSRC to Off"
    sendEvent(name: "heatSource", value: "Off")
    parent?.setBodyHeatSource(device.deviceNetworkId, "Off")
    debounceTile()
}

// ============================================================
// ===================== HEAT AND START ======================
// One-tap action: sets target temp + heat source + starts pump.
// If pump is already on it just applies the heat settings.
// This is the main "I want a hot spa/pool" button.
// ============================================================
def "Heat And Start"(degrees, source) {
    def temp = degrees.toInteger()
    def minT = (minSetPoint ?: 40).toInteger()
    def maxT = (maxSetPoint ?: 104).toInteger()

    if (temp < minT || temp > maxT) {
        log.warn "${device.displayName}: set point ${temp}°F out of range (${minT}–${maxT}°F) — ignoring"
        return
    }
    if (device.currentValue("heatLock") == "locked") {
        log.warn "${device.displayName} — heat is disabled. Use Enable Heat first."
        return
    }

    if (debugMode) log.debug "${device.displayName}: Heat And Start — ${temp}°F via ${source}"

    // 1. Set temperature
    sendEvent(name: "heatingSetpoint", value: temp, unit: "°F")
    parent?.setBodySetPoint(device.deviceNetworkId, temp)

    // 2. Set heat source
    sendEvent(name: "heatSource", value: source)
    parent?.setBodyHeatSource(device.deviceNetworkId, source)

    // 3. Start pump (if not already on)
    if (device.currentValue("switch") != "on") {
        sendEvent(name: "switch",     value: "on")
        sendEvent(name: "bodyStatus", value: "On")
        parent?.setBodyStatus(device.deviceNetworkId, "ON")
    }

    debounceTile()
}

// ============================================================
// ===================== TEMPERATURE =========================
// ============================================================
def setHeatingSetpoint(temp) {
    sendEvent(name: "heatingSetpoint", value: temp.toInteger(), unit: "°F")
    debounceTile()
}

def adjustSetPointUp() {
    def current = (device.currentValue("heatingSetpoint") ?: 80).toInteger()
    "Set Temperature"(current + 1)
}

def adjustSetPointDown() {
    def current = (device.currentValue("heatingSetpoint") ?: 80).toInteger()
    "Set Temperature"(current - 1)
}

// ============================================================
// ===================== HEAT SOURCE =========================
// ============================================================
def setHeatSource(source) {
    sendEvent(name: "heatSource", value: source)
    debounceTile()
}

// ============================================================
// ===================== HEAT LOCKOUT ========================
// ============================================================
def "Disable Heat"() {
    log.info "${device.displayName} — heat disabled"
    sendEvent(name: "heatLock", value: "locked")
    debounceTile()
}

def "Enable Heat"() {
    log.info "${device.displayName} — heat enabled"
    sendEvent(name: "heatLock", value: "unlocked")
    debounceTile()
}

// ============================================================
// ===================== COMMAND WRAPPERS ====================
// ============================================================
def "Turn On"()  { on() }
def "Turn Off"() { off() }

def "Set Temperature"(degrees) {
    def temp = degrees.toInteger()
    def minT = (minSetPoint ?: 40).toInteger()
    def maxT = (maxSetPoint ?: 104).toInteger()
    if (temp < minT || temp > maxT) {
        log.warn "${device.displayName}: ${temp}°F out of range (${minT}–${maxT}°F)"
        return
    }
    sendEvent(name: "heatingSetpoint", value: temp, unit: "°F")
    parent?.setBodySetPoint(device.deviceNetworkId, temp)
    if (debugMode) log.debug "${device.displayName}: set point ${temp}°F sent"
    debounceTile()
}

def "Set Heat Source"(source) {
    if (device.currentValue("heatLock") == "locked") {
        log.warn "${device.displayName} — heat is disabled. Use Enable Heat first."
        return
    }
    sendEvent(name: "heatSource", value: source)
    parent?.setBodyHeatSource(device.deviceNetworkId, source)
    if (debugMode) log.debug "${device.displayName}: heat source '${source}' sent"
    debounceTile()
}

// ============================================================
// ===================== REFRESH =============================
// ============================================================
def refresh() {
    parent?.componentRefresh(this)
}

// ============================================================
// ===================== TILE DEBOUNCE =======================
// ============================================================
def debounceTile() {
    unschedule(renderTile)
    runIn(3, renderTile)
}

// ============================================================
// ===================== TILE RENDERER =======================
//
// UX design — three clear sections a user understands:
//
//  [1] STATUS BAR — big temp, current water, setpoint arc
//
//  [2] HEAT section:
//        "🔥 Heat & Start" button — one tap does everything:
//           sets target temp (with +/- adjusters), picks heat
//           source, and starts the pump.
//        If pump is already running, just changes heat settings.
//        "❄ Heat Off" button — stops heater, pump keeps running
//        Heat Disabled banner replaces this entire section when locked.
//
//  [3] PUMP section:
//        Green  "▶ Start Pump (No Heat)" when pump is off
//        Green  "● Running" badge (non-clickable) when pump is on
//        Red    "⏹ Stop Everything" button — always visible
//
// Language is plain English. No technical terms.
// ============================================================
def renderTile() {
    def sw       = device.currentValue("switch")           ?: "off"
    def temp     = (device.currentValue("temperature")     ?: 0).toDouble()
    def setpt    = (device.currentValue("heatingSetpoint") ?: 0).toDouble()
    def maxTemp  = (device.currentValue("maxSetTemp")      ?: 104).toDouble()
    def htmode   = device.currentValue("heaterMode")       ?: "—"
    def htsrc    = device.currentValue("heatSource")       ?: "Off"
    def heatLock = device.currentValue("heatLock")         ?: "unlocked"

    def isOn     = (sw == "on")
    def isLocked = (heatLock == "locked")
    def isHeating = isOn && htsrc != "Off" && htsrc != "—"

    def name = device.displayName
    def dni  = device.deviceNetworkId
    def base = endpointBase ?: ""

    def url    = { String cmd -> "${base}/body/${dni}/${cmd}" }
    def srcUrl = { String src -> "${base}/body/${dni}/heatsource/${src.replaceAll(' ','_').toLowerCase()}" }
    def hasUrl = base ? true : false

    // ── Arc gauge ─────────────────────────────────────────────
    def minT      = (minSetPoint ?: 40).toDouble()
    def maxT      = (maxSetPoint ?: 104).toDouble()
    def arcStart  = 125.0; def arcEnd = 415.0; def arcRange = arcEnd - arcStart
    def clamp     = { v, lo, hi -> Math.max((double)lo, Math.min((double)hi, (double)v)) }
    def tempFrac  = clamp((temp  - minT) / (maxT - minT), 0.0, 1.0)
    def setptFrac = clamp((setpt - minT) / (maxT - minT), 0.0, 1.0)
    def toRad     = { deg -> deg * Math.PI / 180.0 }
    def arcPath   = { double s, double e ->
        double cx = 110, cy = 110, r = 88
        double x1 = cx + r * Math.cos(toRad(s - 90)); double y1 = cy + r * Math.sin(toRad(s - 90))
        double x2 = cx + r * Math.cos(toRad(e - 90)); double y2 = cy + r * Math.sin(toRad(e - 90))
        "M ${x1.round(2)} ${y1.round(2)} A ${r} ${r} 0 ${((e-s)>180)?1:0} 1 ${x2.round(2)} ${y2.round(2)}"
    }
    def tempAngle  = arcStart + tempFrac  * arcRange
    def setptAngle = arcStart + setptFrac * arcRange
    def dotX = (110 + 88 * Math.cos(toRad(setptAngle - 90))).round(2)
    def dotY = (110 + 88 * Math.sin(toRad(setptAngle - 90))).round(2)
    def trackPath = arcPath(arcStart, arcEnd)
    def setptPath = arcPath(arcStart, setptAngle)
    def tempPath  = arcPath(arcStart, tempAngle)

    // ── Set point adjusters ───────────────────────────────────
    def btnUpFetch = hasUrl ? "fetch('${url('setpointup')}');"   : ""
    def btnDnFetch = hasUrl ? "fetch('${url('setpointdown')}');" : ""

    // ── Heat source selector buttons ──────────────────────────
    def heatSources = ["Heater", "Solar Only", "Solar Preferred", "Heat Pump", "Heat Pump Preferred"]
    def srcBtns = heatSources.collect { lbl ->
        def active    = (htsrc?.equalsIgnoreCase(lbl)) ? "ic-src-active" : ""
        def disClass  = isLocked ? "ic-src-disabled" : ""
        def fetchCall = (!isLocked && hasUrl) ? "fetch('${srcUrl(lbl)}').then(()=>fetch('${url('on')}'));" : ""
        "<button class='ic-src ${active} ${disClass}' onclick=\"${fetchCall}\" ${isLocked ? 'disabled' : ''}>${lbl}</button>"
    }.join("")
    // "Off" source — always shown separately so it's clearly "turn heat off not pump"
    def heatOffActive = (htsrc == "Off" || htsrc == "—") ? "ic-src-active" : ""
    def heatOffFetch  = (!isLocked && hasUrl) ? "fetch('${url('heatoff')}');" : ""
    def heatOffBtn    = "<button class='ic-src-off ${heatOffActive}' onclick=\"${heatOffFetch}\" ${isLocked ? 'disabled' : ''}>❄ Heat Off (pump keeps running)</button>"

    // ── Heat section ──────────────────────────────────────────
    def heatSectionHtml
    if (isLocked) {
        heatSectionHtml = """
    <div class='ic-heat-disabled-banner'>
      <span class='ic-heat-disabled-icon'>🔥</span>
      HEATING DISABLED
      <div class='ic-heat-disabled-sub'>Use "Enable Heat" command to restore</div>
    </div>"""
    } else {
        // Determine what label the heat+start button should show
        def heatBtnLabel = isHeating
            ? "🔥 Heating — Tap to Change Settings"
            : "🔥 Heat & Start"
        def heatBtnClass = isHeating ? "ic-btn-heat-active" : "ic-btn-heat"
        // Heat & Start taps the setpointup/down buttons to set, then starts.
        // The easiest UX is: pick source → tap Heat & Start. Source is already
        // selected by the source buttons. Heat+Start just needs to send STATUS:ON.
        // The set point is already adjusted with +/-.
        def heatStartFetch = hasUrl ? "fetch('${url('on')}');" : ""

        heatSectionHtml = """
    <div class='ic-section-label'>🌡 Target Temperature</div>
    <div class='ic-adj-row' style='margin-bottom:10px;'>
      <button class='ic-adj' onclick="${btnDnFetch}">−</button>
      <div class='ic-setval'>${Math.round(setpt)}°F</div>
      <button class='ic-adj' onclick="${btnUpFetch}">+</button>
    </div>
    <div class='ic-section-label'>🔥 Heat Source — tap to select, then press Heat &amp; Start</div>
    <div class='ic-srcbtns' style='margin-bottom:8px;'>${srcBtns}</div>
    <button class='${heatBtnClass}' onclick="${heatStartFetch}">${heatBtnLabel}</button>
    <div style='margin-top:6px;'>${heatOffBtn}</div>"""
    }

    // ── Pump section ──────────────────────────────────────────
    def onFetch  = hasUrl ? "fetch('${url('on')}');"  : ""
    def offFetch = hasUrl ? "fetch('${url('off')}');" : ""

    def pumpSectionHtml
    if (isOn) {
        def runningLabel = isHeating ? "🔥 Running — Heating to ${Math.round(setpt)}°F" : "🏊 Running — No Heat"
        pumpSectionHtml = """
  <div class='ic-pump-running-badge'>${runningLabel}</div>
  <button class='ic-btn-stop' onclick="${offFetch}">⏹  Stop Everything (pump + heat)</button>"""
    } else {
        pumpSectionHtml = """
  <button class='ic-btn-run' onclick="${onFetch}">▶  Start Pump Only (no heat)</button>
  <div class='ic-pump-off-badge'>⏹ Everything is Off</div>"""
    }

    def noBase = !base ? "<div class='ic-warn'>⚠ Open app and click Done to activate controls</div>" : ""
    def pumpColor = isOn ? "#4ade80" : "#ef4444"

    def html = """<style>
.ic{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#0f172a;border-radius:20px;padding:16px 14px 14px;color:#fff;max-width:260px;margin:0 auto;box-sizing:border-box;}
.ic *{box-sizing:border-box;}
.ic-title{font-size:16px;font-weight:800;text-align:center;margin-bottom:8px;color:#e2e8f0;}
.ic-warn{color:#fbbf24;font-size:10px;text-align:center;margin-bottom:6px;}
.ic-gauge{position:relative;width:200px;height:128px;margin:0 auto 6px;}
.ic-gauge svg{width:200px;height:200px;overflow:visible;}
.ic-center{position:absolute;top:18px;left:50%;transform:translateX(-50%);text-align:center;pointer-events:none;white-space:nowrap;}
.ic-mode{font-size:9px;color:#94a3b8;letter-spacing:1px;text-transform:uppercase;}
.ic-temp{font-size:40px;font-weight:800;line-height:1;color:#fff;}
.ic-unit{font-size:11px;color:#94a3b8;}
.ic-setlbl{font-size:10px;color:#38bdf8;margin-top:2px;}
.ic-row{display:flex;gap:7px;margin-bottom:10px;}
.ic-box{flex:1;background:#1e3a5f;border-radius:11px;padding:8px 6px;text-align:center;}
.ic-blbl{font-size:8px;color:#64748b;text-transform:uppercase;letter-spacing:.5px;margin-bottom:2px;}
.ic-bval{font-size:14px;font-weight:700;color:#e2e8f0;}
.ic-divider{border:none;border-top:1px solid #1e3a5f;margin:10px 0;}
.ic-section-label{font-size:9px;color:#64748b;text-transform:uppercase;letter-spacing:.6px;margin-bottom:6px;font-weight:600;}
.ic-adj-row{display:flex;align-items:center;gap:7px;}
.ic-adj{width:40px;height:40px;border-radius:50%;border:none;background:#1e3a5f;color:#38bdf8;font-size:22px;font-weight:700;cursor:pointer;flex-shrink:0;}
.ic-setval{flex:1;text-align:center;font-size:26px;font-weight:800;color:#38bdf8;}
.ic-srcbtns{display:flex;flex-wrap:wrap;gap:4px;}
.ic-src{padding:5px 9px;border-radius:8px;border:1.5px solid #1e3a5f;background:#0f172a;color:#64748b;font-size:10px;font-weight:600;cursor:pointer;transition:all .15s;}
.ic-src-active{border-color:#f97316;color:#f97316;background:#1c0a00;}
.ic-src-disabled{opacity:0.3;cursor:not-allowed;}
.ic-src-off{width:100%;padding:8px;border-radius:9px;border:1.5px solid #1e3a5f;background:#0f172a;color:#94a3b8;font-size:11px;font-weight:600;cursor:pointer;text-align:center;}
.ic-src-off.ic-src-active{border-color:#38bdf8;color:#38bdf8;background:#0c1a2e;}
/* ── Heat buttons ── */
.ic-btn-heat{width:100%;padding:14px;border-radius:12px;border:none;background:#c2410c;color:#fff;font-size:15px;font-weight:800;cursor:pointer;box-shadow:0 4px 14px rgba(194,65,12,0.5);margin-bottom:0;}
.ic-btn-heat:active{background:#9a3412;}
.ic-btn-heat-active{width:100%;padding:14px;border-radius:12px;border:2px solid #f97316;background:#1c0a00;color:#f97316;font-size:14px;font-weight:800;cursor:pointer;margin-bottom:0;}
/* ── Heat disabled ── */
.ic-heat-disabled-banner{background:#7f1d1d;border:2px solid #ef4444;border-radius:10px;padding:10px 12px 8px;text-align:center;font-size:15px;font-weight:900;color:#fca5a5;letter-spacing:1.5px;text-transform:uppercase;}
.ic-heat-disabled-icon{font-size:18px;display:block;margin-bottom:4px;}
.ic-heat-disabled-sub{font-size:9px;font-weight:400;color:#f87171;margin-top:4px;letter-spacing:0;text-transform:none;}
/* ── Pump buttons ── */
.ic-btn-run{width:100%;padding:13px;border-radius:12px;border:1.5px solid #166534;background:#052e16;color:#4ade80;font-size:14px;font-weight:700;cursor:pointer;margin-bottom:6px;}
.ic-btn-run:active{background:#14532d;}
.ic-pump-running-badge{width:100%;padding:12px;border-radius:12px;border:2px solid #16a34a;background:#052e16;color:#4ade80;font-size:13px;font-weight:800;text-align:center;margin-bottom:6px;}
.ic-btn-stop{width:100%;padding:13px;border-radius:12px;border:none;background:#991b1b;color:#fff;font-size:14px;font-weight:800;cursor:pointer;box-shadow:0 4px 14px rgba(153,27,27,0.4);}
.ic-btn-stop:active{background:#7f1d1d;}
.ic-pump-off-badge{width:100%;padding:8px;border-radius:10px;border:1px solid #1e3a5f;background:transparent;color:#475569;font-size:11px;text-align:center;margin-top:6px;}
</style>
<div class='ic'>
  <div class='ic-title'>${name}</div>
  ${noBase}
  <div class='ic-gauge'>
    <svg viewBox='0 0 220 220'>
      <path d='${trackPath}' stroke='#1e3a5f' stroke-width='13' fill='none' stroke-linecap='round'/>
      <path d='${setptPath}' stroke='#1d3460' stroke-width='13' fill='none' stroke-linecap='round'/>
      <path d='${tempPath}'  stroke='#1d6fbf' stroke-width='13' fill='none' stroke-linecap='round'/>
      <circle cx='${dotX}' cy='${dotY}' r='7' fill='#38bdf8' stroke='#0f172a' stroke-width='3'/>
    </svg>
    <div class='ic-center'>
      <div class='ic-mode'>${htmode}</div>
      <div class='ic-temp'>${Math.round(temp)}</div>
      <div class='ic-unit'>°F current</div>
      <div class='ic-setlbl'>Target ${Math.round(setpt)}°F</div>
    </div>
  </div>
  <div class='ic-row'>
    <div class='ic-box'><div class='ic-blbl'>Target</div><div class='ic-bval'>${Math.round(setpt)}°</div></div>
    <div class='ic-box'><div class='ic-blbl'>Max</div><div class='ic-bval'>${Math.round(maxTemp)}°</div></div>
    <div class='ic-box'><div class='ic-blbl'>Pump</div><div class='ic-bval' style='color:${pumpColor};'>${isOn ? "On" : "Off"}</div></div>
  </div>
  <hr class='ic-divider'>
  ${heatSectionHtml}
  <hr class='ic-divider'>
  ${pumpSectionHtml}
</div>"""

    sendEvent(name: "tile", value: html, displayed: false)
}

