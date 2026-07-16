definition(
    name: "Battery Monitor 2.0",
    namespace: "jdthomas24",
    author: "Jdthomas24",
    description: "Advanced Hubitat battery monitoring with analytics, trends and replacement tracking. Recurring scan schedule, confidence-weighted health, EWMA smoothing.",
    category: "Convenience",
    importUrl: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/refs/heads/main/Battery%20Monitor%202.0/Raw%20Code/BatteryMonitor2.0.groovy",
    iconUrl: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/refs/heads/main/Tests%20-%20Groovy%20RAW/Battery%20Monitor%202.0%20BETA%20Tests",
    iconX2Url: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/refs/heads/main/Battery%20Monitor%202.0/Raw%20Code/BatteryMonitor2.0.groovy",
    version: "2.5.33",
    doNotFocus: true,
    oauth: true
)

// ============================================================
// ===================== OAUTH MAPPINGS ======================
// ============================================================
mappings {
    path("/dashboard") { action: [GET: "serveDashboardPage"]  }
    path("/refresh")   { action: [GET: "forceRefreshEndpoint"] }
}

// ============================================================
// ===================== LIFECYCLE ===========================
// ============================================================
def installed() {
    if (debugMode) log.debug "Installed - initializing app"
    applyCustomLabel()
    initialize()
}

def updated() {
    if (debugMode) log.debug "Updated - re-initializing app"
    applyCustomLabel()
    unschedule()
    unsubscribe()

    initialize()

    runIn(1800, disableDebugLogging)

    def devList    = autoDevices ?: []
    def currentIds = devList.collect { it.id as String }
    state.history?.keySet()?.findAll { !currentIds.contains(it) }?.each { removedId ->
        state.history.remove(removedId)
        state.trend?.remove(removedId)
        if (debugMode) log.debug "Cleaned up removed device: ${removedId}"
    }

    if (state.replacements) {
        def before = state.replacements.size()
        state.replacements = state.replacements.findAll { r ->
            r.deviceId ? currentIds.contains(r.deviceId) : true
        }
        def pruned = before - state.replacements.size()
        if (pruned > 0 && debugMode) log.debug "Purged ${pruned} orphaned replacement history entr${pruned == 1 ? 'y' : 'ies'}"
    }

    if (state.pendingReplacement) {
        def pendingBefore = state.pendingReplacement.size()
        state.pendingReplacement = state.pendingReplacement.findAll { id, _ ->
            currentIds.contains(id)
        }
        def pendingPruned = pendingBefore - state.pendingReplacement.size()
        if (pendingPruned > 0 && debugMode) log.debug "Pruned ${pendingPruned} orphaned pending-replacement entr${pendingPruned == 1 ? 'y' : 'ies'}"
    }

    def migrationDirty  = false
    def migratedHistory = [:]
    state.history?.each { id, data ->
        if (data && !data.firstSeenDate) {
            def newData = new HashMap(data)
            newData.firstSeenDate = newData.replacedTime ?: newData.lastDate ?: now()
            migratedHistory[id]   = newData
            migrationDirty        = true
            if (debugMode) log.debug "Migrated firstSeenDate for device ${id}: ${new Date(newData.firstSeenDate as long)}"
        } else {
            migratedHistory[id] = data
        }
    }
    if (migrationDirty) state.history = migratedHistory

    def didMigrate = false
    state.replacements?.each { r ->
        if (!r.deviceId) {
            def match = autoDevices?.find { it.displayName == r.device }
            if (match) {
                r.deviceId = match.id
                didMigrate = true
                if (debugMode) log.debug "Migrated replacement entry deviceId for ${r.device}: ${r.deviceId}"
            }
        }
    }
    if (didMigrate) state.replacements = state.replacements

    def devList2 = autoDevices ?: []
    devList2.each { device ->
        def legacyInfo = settings["battInfo_${device.id}"]
        if (legacyInfo && legacyInfo != "" && !legacyInfo.startsWith("_sep") && !settings["battType_${device.id}"]) {
            def parts = legacyInfo.tokenize(" x")
            if (parts.size() >= 2) {
                def migratedType  = parts[0..-2].join(" ")
                def migratedCount = parts[-1]
                app.updateSetting("battType_${device.id}",  [value: migratedType,  type: "enum"])
                app.updateSetting("battCount_${device.id}", [value: migratedCount.toInteger(), type: "number"])
            } else {
                app.updateSetting("battType_${device.id}", [value: legacyInfo, type: "enum"])
                app.updateSetting("battCount_${device.id}", [value: 1, type: "number"])
            }
            app.removeSetting("battInfo_${device.id}")
            if (debugMode) log.debug "Migrated battery catalog entry for ${device.displayName}: ${legacyInfo}"
        }
    }
}

def disableDebugLogging() {
    log.info "Battery Monitor: auto-disabling debug logging after 30 minutes"
    app.updateSetting("debugMode", [value: false, type: "bool"])
}

def initialize() {
    if (debugMode) log.debug "Initialization complete"
    if (state.replacements         == null) state.replacements         = []
    if (state.history              == null) state.history              = [:]
    if (state.trend                == null) state.trend                = [:]
    if (state.notifSnoozedUntil    == null) state.notifSnoozedUntil    = 0
    if (state.ignoredDeviceIds     == null) state.ignoredDeviceIds     = []
    if (state.pendingReplacement   == null) state.pendingReplacement   = [:]

    if (!state.accessToken) {
        try {
            createAccessToken()
        } catch (e) {
            log.error "Battery Monitor: OAuth is not enabled. Please enable OAuth in the App Code screen."
        }
    }

    scheduleReportFrequency()
    scheduleScanInterval()

    def devList = autoDevices ?: []
    if (devList) {
        subscribe(devList, "battery", batteryHandler)
    }
}

def applyCustomLabel() {
    if (settings?.customAppName) {
        if (app.label != settings?.customAppName) {
            app.updateLabel(settings.customAppName)
            if (debugMode) log.debug "App label updated to: ${settings.customAppName}"
        }
    }
}

// ============================================================
// ===================== IGNORED DEVICE HELPER ===============
// ============================================================
def isIgnored(device) {
    if (!device) return false
    def ignoredIds = (settings?.ignoredDevices?.collect { it as String }) ?: []
    return ignoredIds.contains(device.id as String)
}

// ============================================================
// ===================== PREFERENCES =========================
// ============================================================
preferences {
    page(name: "mainPage")
    page(name: "summaryPage")
    page(name: "historyPage")
    page(name: "deleteHistoryPage")
    page(name: "deleteHistoryConfirmPage")
    page(name: "infoPage")
    page(name: "forceScanPage")
    page(name: "sendNotificationPage")
    page(name: "deviceManagePage")
    page(name: "deviceActionsPage")
    page(name: "bulkActionsPage")
    page(name: "bulkActionsResultPage")
    page(name: "ignoredDevicesPage")
    page(name: "detectionSettingsPage")
    page(name: "batteryTypesPage")
}

// ============================================================
// ===================== MAIN PAGE ===========================
// ============================================================
def mainPage() {
    applyCustomLabel()

    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {

        def currentLabel = app.label ?: "Battery Monitor 2.0"
        def appNameTitle = "<b>App Display Name</b> — <span style='color:blue;'>${currentLabel}</span>"
        section(appNameTitle, hideable: true, hidden: true) {
            paragraph "Enter a name to rename this app in your Hubitat app list."
            input "customAppName", "text",
                  title: "Custom App Name",
                  description: "Rename how this app appears in your Hubitat app list",
                  required: false
        }

        def portalEnabled    = state.accessToken != null
        def portalStatus     = portalEnabled ? "<span style='color:blue;'>Enabled</span>" : "<span style='color:red;'>Not Enabled</span>"
        def portalSectionTitle = "<b>🌐 Battery Web Portal</b> — ${portalStatus}"

        section(portalSectionTitle, hideable: true, hidden: portalEnabled) {
            if (portalEnabled) {
                def cloudUrl = getFullApiServerUrl()
                def localUrl = getFullLocalApiServerUrl()
                paragraph "<div style='padding:10px; background-color:#d1ecf1; border:1px solid #bee5eb; color:#0c5460; border-radius:4px;'>" +
                          "<b>Cloud URL (use anywhere):</b><br>" +
                          "<a href='${cloudUrl}/dashboard?access_token=${state.accessToken}' target='_blank' style='color:#0c5460; word-wrap:break-word;'>${cloudUrl}/dashboard?access_token=${state.accessToken}</a><br><br>" +
                          "<b>Local URL (use at home):</b><br>" +
                          "<a href='${localUrl}/dashboard?access_token=${state.accessToken}' target='_blank' style='color:#0c5460; word-wrap:break-word;'>${localUrl}/dashboard?access_token=${state.accessToken}</a><br><br>" +
                          "</div>"
            } else {
                def hubIp = location?.hub?.localIP ?: ""
                paragraph "<div style='padding:10px; background-color:#f8d7da; border:1px solid #f5c6cb; color:#721c24; border-radius:4px;'>" +
                          "<b>OAuth is not yet enabled.</b> To activate the web portal:<br><br>" +
                          "1. Go to <b>Apps Code</b> in the Hubitat menu" + (hubIp ? " — <a href='http://${hubIp}/app/list' target='_blank' style='color:#721c24;'>tap here to open Apps Code</a>" : "") + "<br>" +
                          "2. Find <b>Battery Monitor 2.0</b> in the list and open it<br>" +
                          "3. Click <b>OAuth</b> in the top-right of the code editor<br>" +
                          "4. Click <b>Enable OAuth in App</b> → <b>Update</b><br>" +
                          "5. Return here and tap <b>Done</b> to save — the portal URLs will appear above." +
                          "</div>"
            }
        }

        def devicesSelected = (autoDevices?.size() ?: 0) > 0
        def devSectionTitle = devicesSelected
            ? "<b>Selected Monitored Devices</b> — <span style='color:blue;'>${autoDevices.size()} selected</span>"
            : "<b>Selected Monitored Devices</b>"

        section(devSectionTitle, hideable: true, hidden: devicesSelected) {
            paragraph "<b>⚠ Important: The app automatically detects all devices reporting battery levels. " +
                      "Select the devices you want to monitor from the list below. Only selected devices will be tracked for trends, battery health, and notifications.</b>"
            paragraph "<span style='color:red; font-weight:bold;'>Note for mobile users:</span> If your device names are long, they may extend past the screen in the selection list. This is a UI limitation on smaller screens. You can still select devices as usual."
            paragraph "<span style='color:red; font-weight:bold;'>IMPORTANT: After selecting devices, you MUST click 'Done' to exit the app BEFORE viewing the battery report. Skipping this step may cause an error.</span>"
            input "autoDevices", "capability.battery",
                  title: "Select battery devices to monitor",
                  multiple: true,
                  required: false
        }

        if (devicesSelected) {
            def devList = autoDevices ?: []
            if (devList) {
                if (!state.history) state.history = [:]
                if (!state.trend)   state.trend   = [:]
                devList.each { device ->
                    app.updateSetting("deviceName_${device.id}", [value: device.displayName, type: "string"])
                    if (!state.history[device.id]) {
                        def currentLevel = device.currentValue("battery")
                        state.history[device.id] = [
                            lastLevel:     currentLevel != null ? currentLevel.toInteger() : 100,
                            lastDate:      now(),
                            lastScanDate:  now(),
                            firstSeenDate: now(),
                            drain:         0.3,
                            samples:       [],
                            justReplaced:  false
                        ]
                        state.trend[device.id] = "Stable"
                    }
                }
            }
        }

        section("") {
            input "scanInterval", "enum",
                  title: "<b>Battery Scan Interval</b>",
                  description: "How often battery levels are read. More frequent = faster health ratings. Devices also update on their own battery events.",
                  options: ["1": "Hourly", "3": "Every 3 Hours", "6": "Every 6 Hours"],
                  defaultValue: "3",
                  submitOnChange: true
        }

        def snoozed         = state.notifSnoozedUntil && state.notifSnoozedUntil >= now()
        def snoozeHoursLeft = snoozed ? Math.ceil((state.notifSnoozedUntil - now()) / 3600000).toInteger() : 0
        def snoozeSectionTitle = snoozed
            ? "<b>Notification Snooze</b> — <span style='color:orange;'>😴 ${snoozeHoursLeft}h remaining</span>"
            : "<b>Notification Snooze</b> — <span style='color:red;'>Off</span>"
        section(snoozeSectionTitle, hideable: true, hidden: !snoozed) {
            paragraph "Silence all Battery Monitor notifications for a set duration. Useful when traveling or away from home."
            if (snoozed) {
                paragraph "<b><span style='color:orange;'>😴 Notifications snoozed — ${snoozeHoursLeft}h remaining</span></b>"
                input "snoozeConfirmClear", "bool",
                      title: "✅ Clear snooze — resume notifications now",
                      defaultValue: false,
                      submitOnChange: true
                if (settings?.snoozeConfirmClear == true) {
                    state.notifSnoozedUntil = 0
                    app.updateSetting("snoozeConfirmClear", [value: false, type: "bool"])
                    paragraph "✅ Snooze cleared — notifications resumed."
                }
            } else {
                input "snoozeDurationDays", "number",
                      title: "Snooze duration (days):",
                      defaultValue: 7,
                      required: true
                input "snoozeConfirm", "bool",
                      title: "😴 Confirm — snooze notifications",
                      defaultValue: false,
                      submitOnChange: true
                if (settings?.snoozeConfirm == true) {
                    def days = (settings?.snoozeDurationDays ?: 7).toInteger()
                    state.notifSnoozedUntil = now() + (days * 86400000)
                    app.updateSetting("snoozeConfirm", [value: false, type: "bool"])
                    paragraph "😴 Notifications snoozed for ${days} day(s)."
                }
            }
        }

        def notifOn              = settings?.enablePush != false
        def notifSectionTitle    = "<b>Notifications</b> — <span style='color:${notifOn ? "blue" : "red"};'>${notifOn ? "On" : "Off"}</span>"
        section(notifSectionTitle, hideable: true, hidden: true) {
            paragraph "ℹ️ Enable the toggle below to reveal notification settings including frequency, timing, device targets, and which battery groups to include in reports."
            input "enablePush", "bool", title: "Enable notifications", defaultValue: true, submitOnChange: true

            if (settings?.enablePush != false) {
                input "reportFrequency", "enum",
                      title: "Notification Frequency:",
                      options: ["daily": "Daily", "every2": "Every 2 Days", "every3": "Every 3 Days", "weekly": "Weekly"],
                      defaultValue: "daily"
                input "summaryTime", "time", title: "Notification Time:", required: false
                input "notifyDevices", "capability.notification", title: "Notification devices", multiple: true, required: false
                input "enablePushover", "bool", title: "⚙️ Enable Pushover Markup", defaultValue: false
                input "pushoverDevices", "capability.notification",
                      title: "Pushover notification devices <b>(receives Pushover-formatted message)</b>",
                      multiple: true, required: false
                input "pushoverPrefix", "text",
                      title: "Pushover tags <b>(Only used if Enable Pushover Markup is toggled ON)</b>",
                      description: "Pushover-specific additions to the Battery Monitor notifications, e.g. [H][TITLE=Battery Report][HTML][SELFDESTRUCT=43200]",
                      required: false
                paragraph "<b>Report Sections (choose which battery groups to include in notifications):</b>"
                input "notifyPoor",      "bool", title: "🔴 Include Poor (≤25%)",                            defaultValue: true
                input "notifyFair",      "bool", title: "🟠 Include Fair (26–70%)",                          defaultValue: true
                input "notifyGood",      "bool", title: "🟢 Include Good (71–99%)",                          defaultValue: false
                input "notifyExcellent", "bool", title: "🟢 Include Excellent (100%)",                       defaultValue: false
                input "notifyHighDrain", "bool", title: "⚠️ Include Health (Fair, Poor, & High Drain Only)", defaultValue: true
                input "notifyStale",     "bool", title: "⚠️ Include Stale Devices",                          defaultValue: true
                input "staleThresholdHours", "number",
                      title: "<b>Mark device as stale if no activity for X hours</b>",
                      defaultValue: 24
                input "suppressEmptyReport",  "bool", title: "🔕 Don't send notification if nothing to report <b>(Skips Notification entirely when all enabled toggles are Empty)</b>", defaultValue: false
                input "notifyIncludeAppLink", "bool", title: "🔗 Include link to Battery Monitor app <b>(Local Only)</b>", defaultValue: false
                paragraph "<b>Send notification now:</b>"
                href(name: "toSendNotification", page: "sendNotificationPage", title: "📤 Send Notification Now")
            }
        }

        section("<b>Reports:</b>") {
            href(name: "toSummary",   page: "summaryPage",      title: "<b>Battery Summary & Trends</b>",     description: "Battery levels, health, drain rates and trends")
            href(name: "toHistory",   page: "historyPage",      title: "<b>Battery Replacement History</b>",  description: "Auto and manual replacement log")
            href(name: "toDevManage", page: "deviceManagePage", title: "<b>🔋 Device Battery Management</b>", description: "Assign battery types, log replacements, reset drain history, view history")
        }

        section("<b>Help & Support</b>") {
            href(name: "toInfo", page: "infoPage",
                 title: "📖 App Guide & Reference",
                 description: "Colors, drain rates, trends, confidence, and replacement detection explained")
            paragraph rawHtml: true, """
<div style='padding:4px 0;'>
  <a href='https://community.hubitat.com/t/release-battery-monitor-2-0/162329/288' target='_blank'
     style='display:block; background:#f8f8f8; border:1px solid #ddd; border-radius:6px; padding:10px 14px; text-decoration:none; color:#333; margin-bottom:6px;'>
    <span style='font-size:14px;'>💬 <b>Hubitat Community Thread</b></span><br>
    <span style='font-size:12px; color:#888;'>Questions, feedback, bug reports, and release notes</span>
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
            input "debugMode", "bool", title: "Debug Logging (auto-disables after 30 min)", defaultValue: false, submitOnChange: true
            paragraph "<span style='color:#94a3b8; font-size:11px;'>Battery Monitor v2.5.33</span>"
        }
    }
}

// ============================================================
// ===================== REPORT SCHEDULING ==================
// ============================================================
def scheduleReportFrequency() {
    unschedule("reportScheduler")
    if (!summaryTime) return
    if (settings?.enablePush == false) return
    schedule(summaryTime, reportScheduler)
}

def scheduleScanInterval() {
    unschedule("scanAllDevices")
    def interval = (settings?.scanInterval ?: "3").toInteger()
    def cronExpr = ""
    switch (interval) {
        case 1:  cronExpr = "0 0 * * * ?";   break
        case 3:  cronExpr = "0 0 */3 * * ?"; break
        case 6:  cronExpr = "0 0 */6 * * ?"; break
        default: cronExpr = "0 0 */3 * * ?"; break
    }
    schedule(cronExpr, scanAllDevices)
    if (debugMode) log.debug "Battery scan scheduled every ${interval}h (cron: ${cronExpr})"
}

def scanAllDevices() {
    def devList = (autoDevices ?: []).findAll { !isIgnored(it) }
    if (!devList) return
    if (debugMode) log.debug "Running scheduled battery scan for ${devList.size()} device(s)"
    log.info "Battery Monitor: scan started — ${devList.size()} device(s)"

    confirmPendingReplacements()

    def poor  = []
    def stale = []

    devList.each { device ->
        try {
            app.updateSetting("deviceName_${device.id}", [value: device.displayName, type: "string"])
            def level = device.currentValue("battery")?.toInteger()
            if (level != null) {
                updateBattery(device, level)
                if (debugMode) log.debug "Scanned ${device.displayName}: ${level}%"
                if (level <= 25 && !isBatteryDead(device)) poor  << "${device.displayName} (${level}%)"
                if (isStale(device))                        stale << device.displayName
            }
        } catch (e) {
            log.warn "Scan failed for ${device.displayName}: ${e.message}"
        }
    }

    def ts = new Date().format("MM/dd h:mm a", location.timeZone)
    def msg = "SCAN: ${devList.size()} device(s) scanned at ${ts}."
    if (poor.size()  > 0) msg += " Low battery: ${poor.join(', ')}."
    if (stale.size() > 0) msg += " Stale: ${stale.join(', ')}."
    log.info "Battery Monitor: scan complete — ${devList.size()} device(s) processed"
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

def scheduledSummary() {
    // v2.5.31→v2.5.32: Fix — enablePush previously only gated sendPush(); notifyDevices and
    // pushoverDevices kept firing even with the toggle off (and stayed hidden/uneditable in the
    // UI once off, with no way to clear them). This makes the master switch authoritative.
    if (settings?.enablePush == false) {
        if (debugMode) log.debug "Notifications disabled (enablePush off) — skipping summary"
        return
    }

    if (state.notifSnoozedUntil && state.notifSnoozedUntil >= now()) {
        if (debugMode) log.debug "Notifications snoozed until ${new Date(state.notifSnoozedUntil)} — skipping summary"
        return
    }

    def devList = (autoDevices ?: []).findAll { it?.currentValue("battery") != null && !isIgnored(it) }
    if (!devList) return

    def categories = [
        "🔴 Poor":      [list: [], enabled: notifyPoor      != null ? notifyPoor      : true],
        "🟠 Fair":      [list: [], enabled: notifyFair      != null ? notifyFair      : true],
        "🟢 Good":      [list: [], enabled: notifyGood      != null ? notifyGood      : false],
        "🟢 Excellent": [list: [], enabled: notifyExcellent != null ? notifyExcellent : false]
    ]

    devList.each { device ->
        if (isBatteryDead(device)) return
        def lvl = device.currentValue("battery") != null ? device.currentValue("battery").toInteger() : 100
        def cat = lvl >= 100 ? "🟢 Excellent" : lvl > 70 ? "🟢 Good" : lvl > 25 ? "🟠 Fair" : "🔴 Poor"
        categories[cat].list << [device: device, name: device.displayName.trim(), level: lvl]
    }

    categories.each { cat, data ->
        categories[cat].list = data.list.sort { a, b ->
            a.level != b.level ? a.level <=> b.level : a.name <=> b.name
        }
    }

    def highDrainList = devList.findAll { device ->
        if (isBatteryDead(device)) return false
        def h = health(device)
        (h == "Poor" || h == "Fair") && getDrain(device) > 1.5
    }.collect { device ->
        def lvl = device.currentValue("battery") != null ? device.currentValue("battery").toInteger() : 100
        [name: device.displayName.trim(), level: lvl, health: health(device), drain: displayDrain(device)]
    }.sort { a, b -> a.level != b.level ? a.level <=> b.level : a.name <=> b.name }

    def deadBatteryList = devList.findAll { isBatteryDead(it) }.collect { device ->
        [name: device.displayName.trim()]
    }.sort { a, b -> a.name <=> b.name }

    def usePushover = (settings?.enablePushover == true && settings?.pushoverPrefix?.trim())
    def prefix  = ""
    def postfix = ""

    if (usePushover) {
        def tags          = settings.pushoverPrefix.trim()
        def priorityMatch = tags =~ /^(\[[EHLNS]\])(.*)/
        if (priorityMatch) {
            prefix  = priorityMatch[0][1]
            postfix = priorityMatch[0][2].trim()
        } else {
            postfix = tags
        }
    }

    def timestamp = new Date().format("MM/dd HH:mm", location.timeZone)
    def body      = "${prefix}🔋 Battery Summary — ${timestamp}\n"

    def staleDevices = devList.findAll { isStale(it) }.collect {
        def last        = getLastActivityTime(it)
        def inactiveStr = last ? formatInactive(last) : "unknown"
        [device: it, name: it.displayName, inactiveStr: inactiveStr]
    }

    categories.each { cat, data ->
        if (data.enabled) {
            if (data.list) {
                body += "\n${cat}:\n"
                data.list.each { dev ->
                    if (cat == "🔴 Poor") {
                        def info    = getCatalogBatteryInfo(dev.device)
                        def infoStr = info ? " (${info})" : ""
                        body += "• ${dev.level}% ${dev.name}${infoStr}\n"
                    } else {
                        body += "• ${dev.level}% ${dev.name}\n"
                    }
                }
            } else {
                body += "\n${cat}: None\n"
            }
        }
    }

    if (notifyHighDrain != null ? notifyHighDrain : true) {
        if (highDrainList) {
            body += "\n⚠️ High Drain (Fair/Poor):\n"
            highDrainList.each { dev -> body += "• ${dev.health} (${dev.drain}%) ${dev.name} (${dev.level}%)\n" }
        } else {
            body += "\n⚠️ High Drain (Fair/Poor): None\n"
        }
    }

    if (notifyStale != null ? notifyStale : true) {
        if (staleDevices) {
            body += "\n⚠️ Stale Devices:\n"
            staleDevices.each { d ->
                def info    = getCatalogBatteryInfo(d.device)
                def infoStr = info ? " (${info})" : ""
                body += "• ${d.name}${infoStr} — inactive ${d.inactiveStr}\n"
            }
        } else {
            body += "\n⚠️ Stale Devices: None\n"
        }
    }

    if (deadBatteryList) {
        body += "\n🪫 Dead Batteries:\n"
        deadBatteryList.each { dev ->
            def foundDev = dev.deviceId
                ? devList.find { it.id == dev.deviceId }
                : devList.find { it.displayName.trim() == dev.name }
            def info    = getCatalogBatteryInfo(foundDev)
            def infoStr = info ? " (${info})" : ""
            body += "• ${dev.name}${infoStr}\n"
        }
    }

    if (suppressEmptyReport != null ? suppressEmptyReport : false) {
        def hasContent = categories.any { cat, data -> data.enabled && data.list } ||
            ((notifyHighDrain != null ? notifyHighDrain : true) && highDrainList) ||
            ((notifyStale != null ? notifyStale : true) && staleDevices) ||
            deadBatteryList
        if (!hasContent) return
    }

    def pushoverBody = body
    def plainBody    = body

    if (notifyIncludeAppLink != null ? notifyIncludeAppLink : false) {
        def hubIp     = location.hub.localIP
        def htmlLink  = "\n🔗 <a href='http://${hubIp}/installedapp/configure/${app.id}/mainPage'>Battery Monitor</a>"
        def plainLink = "\n🔗 Battery Monitor: http://${hubIp}/installedapp/configure/${app.id}/mainPage"
        pushoverBody += htmlLink
        plainBody    += plainLink
    }

    if (postfix) pushoverBody += "${postfix}\n"

    if (settings?.enablePush)      sendPush(pushoverBody)
    if (settings?.pushoverDevices) settings.pushoverDevices.each { it.deviceNotification(pushoverBody) }
    if (settings?.notifyDevices)   notifyDevices.each { it.deviceNotification(plainBody) }

    def poorCount  = categories["🔴 Poor"]?.list?.size() ?: 0
    def staleCount = staleDevices?.size() ?: 0
    def deadCount  = deadBatteryList?.size() ?: 0
}

// ============================================================
// ===================== BATTERY HANDLER =====================
// ============================================================
def batteryHandler(evt) {
    def device = evt.device
    if (isIgnored(device)) return
    def level  = null
    try {
        level = evt.value ? (int) Double.parseDouble(evt.value) : null
    } catch (e) {
        log.warn "batteryHandler: Could not parse battery level '${evt.value}' for ${device?.displayName}: ${e.message}"
    }
    if (device && level != null) {
        updateBattery(device, level)
    }
}

def updateBattery(device, level) {
    def data = state.history[device.id]

    if (!data) {
        state.history[device.id] = [
            lastLevel:     level != null ? level : 100,
            lastDate:      now(),
            lastScanDate:  now(),
            firstSeenDate: now(),
            drain:         0.3,
            samples:       [],
            justReplaced:  false,
            zeroCount:     0
        ]
        state.trend[device.id] = "Stable"
        state.history = state.history
        data = state.history[device.id]
    }

    if (level <= 1) {
        data.zeroCount = (data.zeroCount ?: 0) + 1
    } else {
        data.zeroCount = 0
    }

    if (isBatteryDead(device)) {
        data.lastLevel    = level
        data.lastScanDate = now()
        state.history[device.id] = data
        state.history = state.history
        if (debugMode) log.debug "${device.displayName}: battery confirmed dead (${data.zeroCount} consecutive 0% readings)"
        return
    }

    if (level <= 1 && (data.zeroCount ?: 0) < 3) {
        data.justReplaced      = false
        data.replacedTime      = null
        data.drain             = 1.0
        data.samples           = []
        state.trend[device.id] = "Heavy Drain"
    }

    detectReplacement(device, level, data.lastLevel)

    def replacedAt = data.replacedTime ?: now()
    if (data.justReplaced && (now() - safeTime(replacedAt)) > 1000 * 60 * 60 * 24) {
        data.justReplaced = false
    }

    def days  = (now() - safeTime(data.lastDate)) / (1000 * 60 * 60 * 24)
    def hours = days * 24

    if (days > 0 && hours >= 1.0 && !data.justReplaced) {
        def lastLevel    = data.lastLevel != null ? data.lastLevel : 100
        def rawDrain     = (lastLevel - level) / days
        def clampedDrain = Math.max(0.0, Math.min(rawDrain, 5.0))
        def validSample  = (rawDrain > 0) || (rawDrain == 0 && hours >= 24)

        if (validSample) {
            def isOutlier = false
            if (data.samples && data.samples.size() >= 3) {
                def rollingAvg = data.samples.sum() / data.samples.size()
                if (rollingAvg > 0 && clampedDrain > rollingAvg * 4) {
                    isOutlier = true
                    if (debugMode) log.debug "${device.displayName}: outlier sample rejected — clampedDrain=${clampedDrain}, rollingAvg=${rollingAvg}"
                }
            }

            if (!isOutlier) {
                def alpha      = 0.3
                def prevSmooth = (data.samples && data.samples.size() > 0) ? data.samples[-1] : clampedDrain
                def smoothed   = alpha * clampedDrain + (1 - alpha) * prevSmooth
                data.samples << smoothed
                if (data.samples.size() > 10) data.samples.remove(0)
                data.lastDate = now()
            }

            if (data.samples && data.samples.size() > 0) {
                def avg  = data.samples.sum() / data.samples.size()
                data.drain = Math.min(avg, 3.0)
                updateTrend(device, data.drain)
            }
        }
    }

    data.lastLevel    = level
    data.lastScanDate = now()

    state.history[device.id] = data
    state.history = state.history
}

// ============================================================
// ===================== DEAD BATTERY DETECTION ==============
// ============================================================
def isBatteryDead(device) {
    def data      = state.history?.get(device.id)
    if (!data) return false
    def level     = device.currentValue("battery")
    def zeroCount = data.zeroCount ?: 0
    return (level != null && level.toInteger() <= 1 && zeroCount >= 3)
}

// ============================================================
// ===================== DETECT REPLACEMENT ==================
// ============================================================
def detectReplacement(device, newLevel, oldLevel) {
    newLevel = newLevel != null ? newLevel : 100
    oldLevel = oldLevel != null ? oldLevel
                                : (state.history[device.id]?.lastLevel != null
                                   ? state.history[device.id].lastLevel : 0)

    if (!state.history[device.id]) {
        state.history[device.id] = [
            lastLevel:    oldLevel,
            lastDate:     now(),
            lastScanDate: now(),
            drain:        0.3,
            samples:      [],
            justReplaced: false,
            zeroCount:    0
        ]
        state.trend[device.id] = "Stable"
    }

    def data = state.history[device.id]

    def sampleCount = data?.samples?.size() ?: 0
    if (sampleCount < 3) {
        if (debugMode) log.debug "${device.displayName}: replacement gate — only ${sampleCount}/3 prior samples, skipping"
        return
    }

    def firstSeen = data?.firstSeenDate ?: data?.lastDate ?: now()
    def ageDays   = (now() - (firstSeen as Long)) / (1000 * 60 * 60 * 24)
    if (ageDays < 3) {
        if (debugMode) log.debug "${device.displayName}: replacement gate — device only ${ageDays.toInteger()}d old (min 3d), skipping"
        return
    }

    def lastLogged = data?.lastReplacementLogged
    if (lastLogged) {
        def hoursSinceLast = (now() - (lastLogged as Long)) / (1000 * 60 * 60)
        if (hoursSinceLast < 12) {
            if (debugMode) log.debug "${device.displayName}: replacement gate — last replacement ${hoursSinceLast.toInteger()}h ago (cooldown 12h), skipping"
            return
        }
    }

    // v2.5.29→v2.5.30: Simplified detection — any upward jump of minJump% or more qualifies.
    // Batteries only drain naturally; any significant upward jump means a new battery was installed.
    def minJump   = (settings?.detectionMinJump ?: 30).toInteger()
    def largeJump = newLevel - oldLevel

    def qualifies = (largeJump >= minJump)
    if (!qualifies) {
        if (state.pendingReplacement?.containsKey(device.id)) {
            if (debugMode) log.debug "${device.displayName}: pending replacement cleared — jump ${largeJump}% < threshold ${minJump}% (${oldLevel}% → ${newLevel}%)"
            state.pendingReplacement.remove(device.id)
            state.pendingReplacement = state.pendingReplacement
        }
        return
    }

    def requireConfirm   = true
    def confirmWindowHrs = 48

    if (!state.pendingReplacement) state.pendingReplacement = [:]

    def pending = state.pendingReplacement[device.id]

    if (!pending) {
        state.pendingReplacement[device.id] = [
            stagedAt: now(),
            oldLevel: oldLevel,
            newLevel: newLevel,
            jumpSize: largeJump
        ]
        state.pendingReplacement = state.pendingReplacement
        if (debugMode) log.debug "${device.displayName}: replacement staged (awaiting confirmation) — ${oldLevel}% → ${newLevel}%, jump=${largeJump}%"
        return
    }

    def windowMs   = confirmWindowHrs * 60 * 60 * 1000
    def pendingAge = now() - (pending.stagedAt as Long)

    if (pendingAge > windowMs) {
        if (debugMode) log.debug "${device.displayName}: pending replacement expired (${(pendingAge / 3600000).toInteger()}h > ${confirmWindowHrs}h window) — re-staging"
        state.pendingReplacement[device.id] = [
            stagedAt: now(),
            oldLevel: oldLevel,
            newLevel: newLevel,
            jumpSize: largeJump
        ]
        state.pendingReplacement = state.pendingReplacement
        return
    }

    // Gate 5: level must still be above (pre-jump level + minJump) on confirming read
    def sustainThresh = (pending.oldLevel as Integer) + minJump
    if (newLevel < sustainThresh) {
        if (debugMode) log.debug "${device.displayName}: pending replacement cancelled — level dropped back to ${newLevel}% (must sustain ≥${sustainThresh}%)"
        state.pendingReplacement.remove(device.id)
        state.pendingReplacement = state.pendingReplacement
        return
    }

    state.pendingReplacement.remove(device.id)
    state.pendingReplacement = state.pendingReplacement
    data.zeroCount = 0
    logReplacement(device, newLevel, false)
    if (debugMode) log.debug "${device.displayName}: replacement CONFIRMED — ${pending.oldLevel}% → ${newLevel}%, staged ${(pendingAge / 60000).toInteger()}m ago"
}

// ============================================================
// ===================== CONFIRM PENDING REPLACEMENTS ========
// ============================================================
def confirmPendingReplacements() {
    if (!state.pendingReplacement || state.pendingReplacement.isEmpty()) return

    def minJump  = (settings?.detectionMinJump ?: 30).toInteger()
    def windowMs = 48 * 60 * 60 * 1000
    def toRemove = []

    state.pendingReplacement.each { deviceId, pending ->
        def device = autoDevices?.find { it.id == deviceId }
        if (!device) { toRemove << deviceId; return }

        def currentLevel = device.currentValue("battery")?.toInteger()
        if (currentLevel == null) return

        def pendingAge    = now() - (pending.stagedAt as Long)
        def sustainThresh = (pending.oldLevel as Integer) + minJump

        if (pendingAge > windowMs) {
            if (debugMode) log.debug "${device.displayName}: pending replacement EXPIRED during scan (${(pendingAge / 3600000).toInteger()}h old)"
            toRemove << deviceId
            return
        }

        if (currentLevel >= sustainThresh) {
            def histData = state.history[device.id]
            if (histData) histData.zeroCount = 0
            logReplacement(device, currentLevel, false)
            toRemove << deviceId
            if (debugMode) log.debug "${device.displayName}: replacement CONFIRMED by scan — level ${currentLevel}% sustained ≥${sustainThresh}%"
        } else {
            if (debugMode) log.debug "${device.displayName}: pending replacement DISCARDED by scan — level ${currentLevel}% dropped below sustain threshold ${sustainThresh}%"
            toRemove << deviceId
        }
    }

    if (toRemove) {
        toRemove.each { state.pendingReplacement.remove(it) }
        state.pendingReplacement = state.pendingReplacement
    }
}

// ============================================================
// ===================== TREND LOGIC =========================
// ============================================================
def updateTrend(device, drain) {
    if (!device || drain == null) return

    def devType      = (device?.name ?: device?.typeName ?: "").toLowerCase()
    def isLock       = devType.contains("lock")
    def isSensor     = devType.contains("contact") || devType.contains("motion")
    def isSlowSensor = devType.contains("smoke") || devType.contains("carbonmonoxide")

    def adjustedDrain = isLock       ? drain * 0.4 :
                        isSensor     ? drain * 0.5 :
                        isSlowSensor ? drain * 0.5 : drain

    if (adjustedDrain > 5) adjustedDrain = 0.3

    def hist = state.history[device.id]
    if (hist?.samples && hist.samples.size() >= 3) {
        def avg = hist.samples.sum() / hist.samples.size()
        if (avg > 3) adjustedDrain = Math.min(adjustedDrain, 1.0)
    }

    def stableThreshold   = isLock ? 0.9 : (isSensor || isSlowSensor) ? 0.6 : 0.3
    def moderateThreshold = isLock ? 2.0 : (isSensor || isSlowSensor) ? 1.5 : 0.8

    if (adjustedDrain <= stableThreshold)       state.trend[device.id] = "Stable"
    else if (adjustedDrain < moderateThreshold) state.trend[device.id] = "Moderate"
    else                                        state.trend[device.id] = "Heavy Drain"
}

// ============================================================
// ===================== CONFIDENCE HELPERS ==================
// ============================================================
def getConfidence(device) {
    def samples = state.history?.get(device.id)?.samples?.size() ?: 0
    def minN    = 5
    if (samples < 2)     return 0.05
    if (samples >= minN) return 1.0
    return Math.min(1.0, 0.05 + 0.95 * Math.pow((samples - 1) / (minN - 1.0), 1.5))
}

def getSampleQualityLabel(device, healthStr) {
    if (healthStr == "Pending") return null
    def conf  = getConfidence(device)
    def label = conf < 0.20 ? "Low" : conf < 0.60 ? "Medium" : conf < 1.0 ? "High" : "Full"
    return "<span style='color:#1a73e8;'>${label}</span>"
}

// ============================================================
// ===================== DRAIN / HEALTH HELPERS ==============
// ============================================================
def getDrain(device) {
    def d = state.history?.get(device.id)?.drain
    return (d != null && d > 0) ? d : 0.3
}
def displayDrain(device) { return String.format("%.2f", getDrain(device)) }

def estDays(device) {
    if (health(device) == "Pending") return null
    def level = device.currentValue("battery") != null ? device.currentValue("battery").toInteger() : 100
    def drain = getDrain(device)
    if (drain <= 0) drain = 0.3
    def est = Math.round(level / drain)
    return Math.min(est, 365)
}

def health(device) {
    def hist    = state.history?.get(device.id)
    def samples = hist?.samples?.size() ?: 0

    def devType      = (device?.name ?: device?.typeName ?: "").toLowerCase()
    def isLock       = devType.contains("lock")
    def isSensor     = devType.contains("contact") || devType.contains("motion")
    def isSlowSensor = devType.contains("smoke") || devType.contains("carbonmonoxide")
    def minSamples   = (isLock || isSlowSensor) ? 7 : 5

    def daysSinceReplaced = 999
    if (hist?.replacedTime) {
        daysSinceReplaced = (now() - (hist.replacedTime as Long)) / (1000 * 60 * 60 * 24)
    } else if (hist?.firstSeenDate) {
        daysSinceReplaced = (now() - (hist.firstSeenDate as Long)) / (1000 * 60 * 60 * 24)
    } else if (hist?.lastDate) {
        daysSinceReplaced = (now() - (hist.lastDate as Long)) / (1000 * 60 * 60 * 24)
    }

    def slowReporter = (daysSinceReplaced >= 14 && samples >= 2)
    if (!slowReporter && (samples < minSamples || daysSinceReplaced < 5)) return "Pending"

    def rawDrain      = getDrain(device)
    def adjustedDrain = isLock ? rawDrain * 0.4 : isSensor ? rawDrain * 0.5 : isSlowSensor ? rawDrain * 0.5 : rawDrain

    def conf     = getConfidence(device)
    def effDrain = 0.3 + conf * (adjustedDrain - 0.3)

    if (effDrain < 0.3)  return "Excellent"
    if (effDrain <= 0.8) return "Good"
    if (effDrain <= 1.5) return "Fair"
    return "Poor"
}

def getHealthDisplay(device) {
    def h       = health(device)
    def hist    = state.history?.get(device.id)
    def samples = hist?.samples?.size() ?: 0

    def devType      = (device?.name ?: device?.typeName ?: "").toLowerCase()
    def isLock       = devType.contains("lock")
    def isSlowSensor = devType.contains("smoke") || devType.contains("carbonmonoxide")
    def minSamples   = (isLock || isSlowSensor) ? 7 : 5

    if (h == "Pending") {
        def daysSinceReplaced = 0
        if (hist?.replacedTime) {
            daysSinceReplaced = ((now() - (hist.replacedTime as Long)) / (1000 * 60 * 60 * 24)).toInteger()
        } else if (hist?.firstSeenDate) {
            daysSinceReplaced = ((now() - (hist.firstSeenDate as Long)) / (1000 * 60 * 60 * 24)).toInteger()
        } else if (hist?.lastDate) {
            daysSinceReplaced = ((now() - (hist.lastDate as Long)) / (1000 * 60 * 60 * 24)).toInteger()
        }
        def minDays = 5
        return "<span style='color:#94a3b8; font-size:11px;'>⏳ ${samples}/${minSamples} samples &nbsp;·&nbsp; ${daysSinceReplaced}/${minDays} days</span>"
    }

    def colorMap = ["Excellent": "#22c55e", "Good": "#22c55e", "Fair": "#f97316", "Poor": "#ef4444"]
    def color    = colorMap[h] ?: "#94a3b8"
    return "<span style='color:${color};font-weight:bold;'>${h}</span>"
}

// ============================================================
// ===================== SAFE HISTORY HELPERS ================
// ============================================================
def safeTime(ts) { return (ts instanceof Number) ? ts : ts?.time }

def safeHistory(device) {
    if (!device) return [:]
    def data = state.history?.get(device.id)
    if (!data) {
        def currentLevel = device.currentValue("battery")
        data = [
            lastLevel:    currentLevel != null ? currentLevel.toInteger() : 100,
            lastDate:     now(),
            lastScanDate: now(),
            drain:        0.3,
            samples:      [],
            justReplaced: false,
            zeroCount:    0
        ]
        state.history[device.id] = data
        state.trend[device.id]   = "Stable"
    }
    return data
}

def getLastBatteryTime(device)  { return safeTime(state.history[device.id]?.lastScanDate ?: state.history[device.id]?.lastDate) }
def getLastActivityTime(device) { return safeTime(device.getLastActivity()) }

def getCatalogBatteryInfo(device) {
    if (!device) return null
    def battType  = settings["battType_${device.id}"]
    def battCount = settings["battCount_${device.id}"]
    if (battType && battType != "" && !battType.startsWith("_sep")) {
        def count        = (battCount != null && battCount.toString().trim() != "") ? battCount.toString().trim() : "1"
        def resolvedType = (battType == "Other") ? (settings["battCustomType_${device.id}"]?.trim() ?: "Other") : battType
        return "${resolvedType} x${count}"
    }
    def info = settings["battInfo_${device.id}"]
    if (!info || info == "" || info.startsWith("_sep")) return null
    return info
}

def isStale(device) {
    def lastActivity = getLastActivityTime(device)
    if (!lastActivity) return false
    def threshold = (settings?.staleThresholdHours != null && settings.staleThresholdHours > 0) ? settings.staleThresholdHours : 24
    def diffHours = (now() - lastActivity) / (1000 * 60 * 60)
    return diffHours >= threshold
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

def formatInactive(ts) {
    if (!ts) return "unknown"
    ts = safeTime(ts)
    def diffMs = now() - ts
    def mins   = (diffMs / (1000 * 60)).toInteger()
    def hours  = (diffMs / (1000 * 60 * 60)).toInteger()
    def days   = (diffMs / (1000 * 60 * 60 * 24)).toInteger()
    def weeks  = (days / 7).toInteger()
    def months = (days / 30).toInteger()
    if (months >= 1) return "${months}mo"
    if (weeks  >= 1) return "${weeks}w"
    if (days   >= 1) return "${days}d"
    if (hours  >= 1) return "${hours}h"
    return "${mins}m"
}

// ============================================================
// ===================== BATTERY DISPLAY =====================
// ============================================================
def getBatteryLevelDisplay(level, device = null) {
    if (device && isBatteryDead(device)) return "<span style='color:#ef4444;'>🪫 Dead</span>"
    level = (level instanceof Number ? level : null) != null ? level : 100
    def cat = level >= 100 ? "🟢 Excellent" : level > 70 ? "🟢 Good" : level > 25 ? "🟠 Fair" : "🔴 Poor"
    def label = "${cat} (${level}%)"
    def data         = (device && state.history?.containsKey(device.id)) ? safeHistory(device) : null
    def showTag      = data?.justReplaced == true
    def replacedTime = data?.replacedTime
    if (showTag) {
        replacedTime = safeTime(replacedTime)
        def hoursSinceReplacement = (now() - replacedTime) / (1000 * 60 * 60)
        if (hoursSinceReplacement >= 24) { if (data) data.justReplaced = false; showTag = false }
    }
    if (device && showTag) label += " (Recently Replaced)"
    return label
}

// ============================================================
// ===================== BATTERY REPLACEMENT LOGGER ==========
// ============================================================
def logReplacement(device, newLevel, manual = false) {
    if (!device) return

    def data = state.history[device.id]
    if (!data) {
        state.history[device.id] = [
            lastLevel:    newLevel != null ? newLevel : 100,
            lastDate:     now(),
            lastScanDate: now(),
            drain:        0.3,
            samples:      [],
            justReplaced: false,
            zeroCount:    0
        ]
        data = state.history[device.id]
        state.trend[device.id] = "Stable"
    }

    data.drain                 = 0.3
    data.samples               = []
    data.lastLevel             = newLevel
    data.lastDate              = now()
    data.lastScanDate          = now()
    data.firstSeenDate         = now()
    data.justReplaced          = true
    data.replacedTime          = now()
    data.zeroCount             = 0
    state.trend[device.id]     = "Stable"
    data.lastReplacementLogged = now()

    state.replacements = state.replacements?.findAll { it.device != device.displayName } ?: []
    state.replacements << [
        deviceId: device.id,
        device:   device.displayName,
        level:    newLevel,
        date:     new Date().format("MM/dd/yyyy", location.timeZone),
        type:     manual ? "manual" : "auto"
    ]
    state.replacements = state.replacements.sort { a, b -> b.date <=> a.date }.take(100)

    state.history[device.id] = data
    state.history = state.history

    def typeStr = manual ? "Manual" : "Auto-detected"
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

// ============================================================
// ===================== PORTAL ENDPOINT: REFRESH ============
// ============================================================
def forceRefreshEndpoint() {
    try {
        runIn(1, "scanAllDevices", [overwrite: true])
        return render(contentType: "text/html", data: getPortalRedirectHtml(2500, "Running battery scan..."), status: 200)
    } catch (e) {
        log.error "Battery Monitor portal refresh error: ${e}"
        return render(contentType: "text/html", data: "Error: ${e.message}", status: 500)
    }
}

// ============================================================
// ===================== PORTAL ENDPOINT: DASHBOARD ==========
// ============================================================
def serveDashboardPage() {
    try {
        def devList = (autoDevices ?: []).findAll { it?.currentValue("battery") != null && !isIgnored(it) }

        devList = devList.sort { a, b ->
            def levelA = a.currentValue("battery") != null ? a.currentValue("battery").toInteger() : 100
            def levelB = b.currentValue("battery") != null ? b.currentValue("battery").toInteger() : 100
            levelA != levelB ? levelA <=> levelB : a.displayName.trim() <=> b.displayName.trim()
        }

        def totalCount     = devList.size()
        def poorCount      = devList.count { it.currentValue("battery") != null && it.currentValue("battery").toInteger() <= 25 && !isBatteryDead(it) }
        def deadCount      = devList.count { isBatteryDead(it) }
        def staleCount     = devList.count { isStale(it) }
        def highDrainCount = devList.count { device ->
            def h = health(device)
            (h == "Poor" || h == "Fair") && getDrain(device) > 1.5
        }

        def css = """
body{font-family:-apple-system,BlinkMacSystemFont,sans-serif;padding:20px;background:#0d0d0d;color:#e0e0e0;margin:0}
.container{max-width:820px;margin:0 auto;background:#151515;padding:25px;border-radius:12px;box-sizing:border-box}
h2{text-align:center;color:#fff;margin:0 0 4px 0}
.subtitle{text-align:center;font-size:12px;color:#666;margin-bottom:20px}
.summary-box{display:flex;flex-wrap:wrap;gap:10px;margin-bottom:20px}
.summary-card{flex:1;min-width:90px;box-sizing:border-box;background:#1e1e1e;padding:12px;border-radius:8px;text-align:center;border-bottom:3px solid #333}
.summary-card b{display:block;font-size:22px;color:#fff;margin-bottom:4px}
.summary-card span{font-size:11px;color:#aaa;text-transform:uppercase}
.btn{display:block;background:#1f618d;color:#fff;padding:13px 20px;border-radius:8px;text-align:center;text-decoration:none;font-weight:600;margin-bottom:10px}
.btn:hover{background:#1a5276}
table{width:100%;border-collapse:collapse;font-size:13px;margin-top:15px}
th{background:#1e1e1e;color:#aaa;padding:8px 6px;text-align:left;border-bottom:2px solid #333;font-size:11px;text-transform:uppercase}
td{padding:8px 6px;border-bottom:1px solid #222;vertical-align:middle}
tr:hover td{background:#1a1a1a}
.badge{display:inline-block;padding:3px 8px;border-radius:10px;font-size:11px;font-weight:bold}
.badge-poor{background:#3b1212;color:#ef4444}
.badge-fair{background:#3b2a12;color:#f97316}
.badge-good{background:#12301a;color:#22c55e}
.badge-excellent{background:#12301a;color:#22c55e}
.badge-dead{background:#3b1212;color:#ef4444}
.badge-pending{background:#1e1e1e;color:#94a3b8}
.batt-bg{width:80px;background:#333;height:5px;border-radius:3px;overflow:hidden;display:inline-block;vertical-align:middle;margin-left:6px}
.batt-fg{height:100%}
.stale-tag{font-size:10px;color:#f97316;margin-left:4px}
.section-title{color:#fff;font-size:15px;font-weight:bold;margin:20px 0 8px 0;border-bottom:1px solid #333;padding-bottom:6px}
"""

        StringBuilder html = new StringBuilder()
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>")
        html.append("<title>Battery Monitor Portal</title><style>${css}</style>")
        html.append("<script>setTimeout(function(){location.reload();},120000);</script>")
        html.append("</head><body><div class='container'>")

        html.append("<h2>🔋 Battery Monitor</h2>")
        html.append("<p class='subtitle'>Live Dashboard &nbsp;·&nbsp; Auto-refreshes every 2 min</p>")

        html.append("<div class='summary-box'>")
        html.append("<div class='summary-card' style='border-bottom-color:#ef4444;'><b>${poorCount}</b><span>Low Battery</span></div>")
        html.append("<div class='summary-card' style='border-bottom-color:#f97316;'><b>${staleCount}</b><span>Stale</span></div>")
        html.append("<div class='summary-card' style='border-bottom-color:#f97316;'><b>${highDrainCount}</b><span>High Drain</span></div>")
        html.append("<div class='summary-card' style='border-bottom-color:#ef4444;'><b>${deadCount}</b><span>Dead</span></div>")
        html.append("<div class='summary-card' style='border-bottom-color:#1a73e8;'><b>${totalCount}</b><span>Total</span></div>")
        html.append("</div>")

        html.append("<a href='refresh?access_token=${state.accessToken}' class='btn'>🔄 Force Scan Now</a>")

        html.append("<div class='section-title'>All Devices</div>")
        html.append("<table><thead><tr>")
        html.append("<th>Device</th><th>Battery</th><th>Drain</th><th>Est Days</th><th>Health & Trend</th><th>Last Activity</th><th>Type</th>")
        html.append("</tr></thead><tbody>")

        devList.each { device ->
            def dead      = isBatteryDead(device)
            def level     = device.currentValue("battery") != null ? device.currentValue("battery").toInteger() : 0
            def h         = health(device)
            def drain     = getDrain(device)
            def est       = estDays(device)
            def stale     = isStale(device)
            def catalog   = getCatalogBatteryInfo(device) ?: "—"
            def lastActMs = getLastActivityTime(device)
            def lastAct   = lastActMs ? formatTimeAgo(lastActMs) : "N/A"
            def trend     = state.trend[device.id] ?: "Stable"

            // Slim battery column — dot + %
            def dotColor  = dead ? "#ef4444" : level >= 100 ? "#22c55e" : level > 70 ? "#22c55e" : level > 25 ? "#f97316" : "#ef4444"
            def recently  = (state.history?.containsKey(device.id) && state.history[device.id]?.justReplaced) ? " <span style='display:inline-block;background:#dbeafe;color:#1d4ed8;font-size:10px;font-weight:600;padding:1px 7px;border-radius:10px;'>✓ Replaced</span>" : ""
            def battDisp  = dead ? "🪫 Dead" : "<span style='color:${dotColor};'>●</span> ${level}%${recently}"

            // Health & Trend — same divergence logic as summary
            def healthRank = ["Excellent": 1, "Good": 2, "Fair": 3, "Poor": 4]
            def trendRank  = ["Stable": 1, "Moderate": 2, "Heavy Drain": 3]
            def hRank      = healthRank[h] ?: 2
            def tRank      = trendRank[trend] ?: 1
            def diverges   = tRank > hRank
            def showTrend  = hRank >= 3 || diverges
            def trendLabel = trend == "Moderate" ? "Moderate Drain" : trend
            def trendColor = trend == "Heavy Drain" ? "#ef4444" : trend == "Moderate" ? "#f97316" : "#22c55e"
            def badgeCls   = dead ? "badge-dead" : h == "Poor" ? "badge-poor" : h == "Fair" ? "badge-fair" : h == "Good" ? "badge-good" : h == "Excellent" ? "badge-excellent" : "badge-pending"
            def badgeLbl   = dead ? "🪫 Dead" : h
            def trendHtml  = showTrend ? " <span style='color:${trendColor};font-size:11px;'>${diverges ? '⚠ ' : ''}${trendLabel}</span>" : ""
            def healthCell = dead
                ? "<span class='badge ${badgeCls}'>${badgeLbl}</span>"
                : h == "Pending"
                ? "<span class='badge ${badgeCls}'>${badgeLbl}</span> ${getHealthDisplay(device) ?: ''}"
                : "<span class='badge ${badgeCls}'>${badgeLbl}</span>${trendHtml}"

            def drainStr  = (dead || h == "Pending") ? "—" : "${String.format('%.2f', drain)}%"
            def estStr    = (dead || h == "Pending" || est == null) ? "—" : "${est}d"
            def staleHtml = stale ? "<span class='stale-tag'>⚠ Stale</span>" : ""

            html.append("<tr>")
            html.append("<td><b>${device.displayName}</b>${staleHtml}</td>")
            html.append("<td>${battDisp}</td>")
            html.append("<td>${drainStr}</td>")
            html.append("<td>${estStr}</td>")
            html.append("<td>${healthCell}</td>")
            html.append("<td>${lastAct}</td>")
            html.append("<td>${catalog}</td>")
            html.append("</tr>")
        }

        html.append("</tbody></table>")
        html.append("<p style='text-align:center;font-size:10px;color:#444;margin-top:20px;'>Battery Monitor v2.5.33 &nbsp;·&nbsp; jdthomas24</p>")
        html.append("</div></body></html>")

        return render(contentType: "text/html", data: html.toString(), status: 200)

    } catch (Exception e) {
        log.error "Battery Monitor portal error: ${e}"
        return render(contentType: "text/html", data: "<h3 style='color:white;font-family:sans-serif;'>Portal Error</h3><p style='color:#ccc;'>${e}</p>", status: 500)
    }
}

// ============================================================
// ===================== SUMMARY PAGE ========================
// ============================================================
def summaryPage() {
    dynamicPage(name: "summaryPage", title: "Battery Summary & Trends", install: false) {

        if (!state.history || !autoDevices || autoDevices.size() == 0) {
            section("Setup Required") {
                paragraph "⚠ <b>Setup Not Complete</b><br><br>" +
                          "You must click <b>Done</b> after selecting your devices before viewing reports.<br><br>" +
                          "Please exit the app and reopen it, then try again."
            }
            return
        }

        def hubIp = location?.hub?.localIP ?: ""

        section("") {
            paragraph rawHtml: true, """
<link rel="stylesheet" href="https://cdn.datatables.net/1.13.6/css/jquery.dataTables.min.css">
<script src="https://cdn.datatables.net/1.13.6/js/jquery.dataTables.min.js"></script>
"""
            paragraph "<span style='display:inline-block; background:#fde8e8; color:#c0392b; font-size:11px; font-weight:600; padding:3px 10px; border-radius:20px;'>⚠ LAN only — device links will not work remotely</span>"
            href(name: "toForceScanFromSummary", page: "forceScanPage",
                 title: "🔄 Force Scan Now",
                 description: "Tap to immediately read battery levels from all monitored devices")

            def devList = (autoDevices ?: []).findAll {
                try { it?.currentValue("battery") != null && !isIgnored(it) } catch (e) {
                    log.warn "Error checking battery capability for ${it?.displayName}: ${e.message}"
                    return false
                }
            }

            devList = devList.sort { a, b ->
                def levelA = null
                def levelB = null
                try { levelA = a.currentValue("battery") != null ? a.currentValue("battery").toInteger() : 100 } catch (e) { levelA = 100 }
                try { levelB = b.currentValue("battery") != null ? b.currentValue("battery").toInteger() : 100 } catch (e) { levelB = 100 }
                levelA != levelB ? levelA <=> levelB : (a.displayName ?: "") <=> (b.displayName ?: "")
            }

            if (!devList) { paragraph "No battery devices found."; return }

            def table = "<table id='batteryTable' style='width:100%; border-collapse: collapse; border: 1px solid #ccc;'>"
            table += "<thead><tr style='font-weight:bold; background-color:#f0f0f0;'>"
            table += "<th style='padding:4px; border:1px solid #ccc;'>Device</th>"
            table += "<th style='padding:4px; border:1px solid #ccc;'>Battery</th>"
            table += "<th style='padding:4px; border:1px solid #ccc;'>Drain %/day</th>"
            table += "<th style='padding:4px; border:1px solid #ccc;'>Est Days</th>"
            table += "<th style='padding:4px; border:1px solid #ccc;'>Health &amp; Trend</th>"
            table += "<th style='padding:4px; border:1px solid #ccc;'>Last Battery</th>"
            table += "<th style='padding:4px; border:1px solid #ccc;'>Last Activity</th>"
            table += "<th style='padding:4px; border:1px solid #ccc;'>Battery Type</th>"
            table += "</tr></thead><tbody>"
            def summaryRowNum = 0

            devList.each { device ->
                def dead = isBatteryDead(device)
                def level = null
                try { level = device.currentValue("battery") != null ? device.currentValue("battery").toInteger() : 100 } catch (e) { level = 100 }

                def catalogInfo = ""
                try { catalogInfo = getCatalogBatteryInfo(device) ?: "" } catch (e) { }

                def drain = 0.3
                try { drain = getDrain(device) } catch (e) { }

                def est = null
                try { est = estDays(device) } catch (e) { }

                def lastBatteryStr = "N/A"
                def lastBatteryMs  = 0
                try {
                    def lastBatteryTime = getLastBatteryTime(device)
                    lastBatteryMs  = lastBatteryTime ?: 0
                    lastBatteryStr = formatTimeAgo(lastBatteryTime)
                } catch (e) { }

                def lastActivityStr = "N/A"
                def lastActivityMs  = 0
                def lastActivity    = null
                try { lastActivity = device.getLastActivity() } catch (e) { }
                if (lastActivity) {
                    try {
                        lastActivityMs  = safeTime(lastActivity) ?: 0
                        lastActivityStr = formatTimeAgo(safeTime(lastActivity))
                    } catch (e) { }
                }

                def stale = false
                try { stale = isStale(device) } catch (e) { }

                def color = ""
                try {
                    if (isBatteryDead(device)) {
                        color = "<span style='color:#ef4444;'>🪫 Dead</span>"
                    } else {
                        def dotColor = level >= 100 ? "#22c55e" : level > 70 ? "#22c55e" : level > 25 ? "#f97316" : "#ef4444"
                        def recently = (state.history?.containsKey(device.id) && state.history[device.id]?.justReplaced) ? " <span style='display:inline-block;background:#dbeafe;color:#1d4ed8;font-size:10px;font-weight:600;padding:1px 7px;border-radius:10px;'>✓ Replaced</span>" : ""
                        color = "<span style='color:${dotColor};'>●</span> ${level}%${recently}"
                    }
                } catch (e) { color = "${level}%" }

                def staleTag      = (stale && lastActivity) ? " ⚠️ Stale" : ""
                def healthDisplay = ""
                try { healthDisplay = getHealthDisplay(device) } catch (e) { healthDisplay = "Unknown" }

                def name         = device.displayName ?: "Unknown Device"
                def summaryRowBg = (summaryRowNum % 2 == 0) ? "#ffffff" : "#ebebeb"
                summaryRowNum++
                table += "<tr style='background-color:${summaryRowBg};'>"

                def sortName = name.toLowerCase()
                if (hubIp) {
                    table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${sortName}'><a href='http://${hubIp}/device/edit/${device.id}' target='_blank'>${name}</a></td>"
                } else {
                    table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${sortName}'>${name}</td>"
                }

                table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${level}'>${color}</td>"

                def trend      = state.trend[device.id] ?: "Stable"
                def trendIcon  = trend == "Heavy Drain" ? "🔴" : trend == "Moderate" ? "🟠" : "🟢"
                def trendOrder = dead ? 999 : (health(device) == "Pending" ? 99 : (["Heavy Drain": 3, "Moderate": 2, "Stable": 1][trend] ?: 1))

                if (dead) {
                    table += "<td style='padding:4px; border:1px solid #ccc; color:#94a3b8;' data-order='999'>—</td>"
                    table += "<td style='padding:4px; border:1px solid #ccc; color:#94a3b8;' data-order='999'>—</td>"
                    table += "<td style='padding:4px; border:1px solid #ccc; color:#94a3b8;' data-order='999'>—</td>"
                } else if (health(device) == "Pending") {
                    table += "<td style='padding:4px; border:1px solid #ccc; color:#94a3b8;' data-order='9999'>📈</td>"
                    table += "<td style='padding:4px; border:1px solid #ccc; color:#94a3b8;' data-order='9999'>📈</td>"
                    def pendingDisplay = getHealthDisplay(device) ?: "⏳ Pending"
                    table += "<td style='padding:4px; border:1px solid #ccc;' data-order='99'>${pendingDisplay}</td>"
                } else {
                    table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${String.format('%.2f', drain)}'>${String.format('%.2f', drain)}</td>"
                    def estDisplay = est != null ? est.toString() : "—"
                    def estOrder   = est != null ? est : 9999
                    table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${estOrder}'>${estDisplay}</td>"
                    def healthOrder = health(device) == "Poor" ? 4 : health(device) == "Fair" ? 3 : health(device) == "Good" ? 2 : health(device) == "Excellent" ? 1 : 99
                    def healthRank  = ["Excellent": 1, "Good": 2, "Fair": 3, "Poor": 4]
                    def trendRank   = ["Stable": 1, "Moderate": 2, "Heavy Drain": 3]
                    def hRank       = healthRank[health(device)] ?: 2
                    def tRank       = trendRank[trend] ?: 1
                    def diverges    = tRank > hRank
                    def showTrend   = hRank >= 3 || diverges  // always show trend for Fair/Poor or when worsening
                    def trendColor  = trend == "Heavy Drain" ? "#ef4444" : trend == "Moderate" ? "#f97316" : "#22c55e"
                    def trendPrefix = diverges ? "⚠ " : ""
                    def trendLabel  = trend == "Moderate" ? "Moderate Drain" : trend
                    def healthTrendDisplay = showTrend
                        ? "${healthDisplay} &nbsp;<span style='color:${trendColor};font-size:11px;'>${trendPrefix}${trendLabel}</span>"
                        : "${healthDisplay}"
                    table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${healthOrder}'>${healthTrendDisplay}</td>"
                }

                table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${lastBatteryMs}'>${lastBatteryStr}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${lastActivityMs}'>${lastActivityStr}${staleTag}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${catalogInfo}</td>"
                table += "</tr>"
            }

            table += "</tbody></table>"

            paragraph rawHtml: true, """
<div style='overflow-x:auto; -webkit-overflow-scrolling:touch;'>${table}</div>
<script>
\$(document).ready(function() {
    \$('#batteryTable').DataTable({
        paging:     false,
        info:       false,
        searching:  true,
        order:      [[1, 'asc']],
        columnDefs: [
            { type: 'num', targets: [1, 2, 3, 6, 7] }
        ]
    });
});
</script>
"""
        }

        section("<b>📖 Legend</b>", hideable: true, hidden: true) {
            paragraph "<div style='background-color:#e8f0fe; border-left:4px solid #1a73e8; border-radius:4px; padding:8px 12px; font-size:13px; color:#1a1a1a;'>" +
                      "📈 Drain and Est Days show <b>📈</b> for <b>Pending</b> devices — the app is actively learning. Data populates automatically once the Pending gate clears. 🪫 <b>Dead</b> = battery confirmed dead, replace immediately.<br><br>" +
                      "🔋 <b>Health &amp; Trend column</b>: Excellent/Good = healthy, no action needed &nbsp;·&nbsp; Fair = elevated drain, worth watching &nbsp;·&nbsp; Poor = high drain, replace soon &nbsp;·&nbsp; ⚠ = trend worsening." +
                      "</div>"
        }
    }
}

// ============================================================
// ===================== DEVICE MANAGE PAGE ==================
// ============================================================
def deviceManagePage(Map params = [:]) {
    def devList = (autoDevices ?: []).sort { a, b -> a.displayName.trim() <=> b.displayName.trim() }
    def ignoredCount = (settings?.ignoredDevices?.size() ?: 0)

    def typeOptions = ["": "— Not Set —"]
    typeOptions["_sep1"] = "──────── Standard ────────"
    ["AA", "AAA", "CR2", "CR1632", "CR2016", "CR2032", "CR2430", "CR2450", "CR2477", "CR123A", "9V", "ER14250", "LS14250"].each { typeOptions[it] = it }
    typeOptions["Integrated"] = "Integrated"
    typeOptions["_sep2"] = "──────── Rechargeable ────────"
    ["Rechargeable AA", "Rechargeable AAA", "LIR2016", "LIR2032", "LIR2430", "LIR2450", "18650", "RCR123A", "RCR2"].each { typeOptions[it] = it }
    typeOptions["_sep3"] = "──────── Other ────────"
    typeOptions["Other"] = "Other"

    dynamicPage(name: "deviceManagePage", title: "🔋 Device Battery Management", install: false) {

        section("<span style='display:inline-block; background:#e8f0fe; color:#1a73e8; font-size:12px; font-weight:600; text-transform:uppercase; letter-spacing:0.07em; padding:3px 12px; border-radius:20px;'>Actions</span>") {
            href(name: "toDeviceActions", page: "deviceActionsPage",
                 title: "<b>⚙️ Device Actions</b>",
                 description: "Log a replacement, reset drain history, ignore a device, change battery type, or view history. Last selected device is remembered.")
            href(name: "toBulkActions", page: "bulkActionsPage",
                 title: "<b>📦 Bulk Actions</b>",
                 description: "Log replacements, reset drain history, or ignore multiple devices at once.")
        }

        // Build ignored devices summary
        def ignoredIds   = (settings?.ignoredDevices?.collect { it as String }) ?: []
        def ignoredNames = autoDevices?.findAll { ignoredIds.contains(it.id as String) }?.collect { it.displayName } ?: []

        section("<span style='display:inline-block; background:#e8f0fe; color:#1a73e8; font-size:12px; font-weight:600; text-transform:uppercase; letter-spacing:0.07em; padding:3px 12px; border-radius:20px;'>Configuration</span>") {
            if (ignoredNames) {
                paragraph "<div style='background-color:#fff3cd; border-left:3px solid #ffc107; border-radius:0 4px 4px 0; padding:8px 12px;'>" +
                          "<span style='color:#856404;'><b>🚫 Ignored Devices (${ignoredNames.size()})</b> — excluded from all reports, notifications, and the portal.<br>" +
                          ignoredNames.collect { "• ${it}" }.join("<br>") + "<br><br>" +
                          "To restore a device, go to <b>Device Actions</b> and toggle its ignore setting. To restore multiple, use <b>Bulk Actions → Unignore</b>.</span></div>"
            }
            href(name: "toDetectionSettings", page: "detectionSettingsPage",
                 title: "<b>🔍 Auto-Detection Settings</b>",
                 description: "Configure the minimum battery jump % that triggers auto-detection.")
            href(name: "toBatteryTypes", page: "batteryTypesPage",
                 title: "<b>🔋 Battery Types</b>",
                 description: "Assign battery type and quantity to each monitored device.")
        }
    }
}

// ============================================================
// ===================== DETECTION SETTINGS PAGE =============
// ============================================================
def detectionSettingsPage() {
    dynamicPage(name: "detectionSettingsPage", title: "🔍 Auto-Detection Settings", install: false) {
        section("") {
            paragraph "<div style='background-color:#e8f0fe; border-left:4px solid #1a73e8; border-radius:4px; padding:10px 12px; margin-bottom:8px;'>" +
                      "Batteries only drain — any significant upward jump in level means a new battery was installed. " +
                      "Battery Monitor detects this automatically across two consecutive readings." +
                      "</div>"

            input "detectionMinJump", "number",
                  title: "<b>Minimum upward jump % to detect a replacement:</b>",
                  description: "Default: 30. Any upward jump of this size or more across two readings will be logged as a replacement.",
                  defaultValue: 30,
                  range: "15..60",
                  required: false

            paragraph "<div style='background-color:#fff3cd; border-left:3px solid #ffc107; border-radius:0 4px 4px 0; padding:8px 12px; margin-top:8px;'>" +
                      "<span style='color:#856404; font-size:12px;'>⚠️ Set too low (under 15%) may cause false positives even with two-reading confirmation. 25–30% works well for most setups.<br><br>" +
                      "Example: a jump from 40% → 75% (35%) logs as replaced. A jump from 85% → 90% (5%) does not.<br><br>" +
                      "Manual logging is still available in Device Actions for edge cases.</span>" +
                      "</div>"
        }
    }
}

// ============================================================
// ===================== BATTERY TYPES PAGE ==================
// ============================================================
def batteryTypesPage() {
    def devList = (autoDevices ?: []).sort { a, b -> a.displayName.trim() <=> b.displayName.trim() }

    def typeOptions = ["": "— Not Set —"]
    typeOptions["_sep1"] = "──────── Standard ────────"
    ["AA", "AAA", "CR2", "CR1632", "CR2016", "CR2032", "CR2430", "CR2450", "CR2477", "CR123A", "9V", "ER14250", "LS14250"].each { typeOptions[it] = it }
    typeOptions["Integrated"] = "Integrated"
    typeOptions["_sep2"] = "──────── Rechargeable ────────"
    ["Rechargeable AA", "Rechargeable AAA", "LIR2016", "LIR2032", "LIR2430", "LIR2450", "18650", "RCR123A", "RCR2"].each { typeOptions[it] = it }
    typeOptions["_sep3"] = "──────── Other ────────"
    typeOptions["Other"] = "Other"

    // v2.5.29→v2.5.30: Split into two sections — unassigned first, assigned collapsed
    // Fix: "Other" without custom text entered counts as unassigned
    def unassigned = devList.findAll { dev ->
        def t = settings["battType_${dev.id}"] ?: ""
        !t || t.startsWith("_sep") || (t == "Other" && !(settings["battCustomType_${dev.id}"]?.trim()))
    }
    def assigned = devList.findAll { dev ->
        def t = settings["battType_${dev.id}"] ?: ""
        t && !t.startsWith("_sep") && !(t == "Other" && !(settings["battCustomType_${dev.id}"]?.trim()))
    }

    dynamicPage(name: "batteryTypesPage", title: "🔋 Battery Types", install: false) {
        section("") {
            paragraph "Assign a battery type and quantity to each device so Battery Monitor can include battery type in notifications and replacement history. " +
                      "This helps you know exactly what to buy when a replacement is needed.<br><br>" +
                      "Set the type and count for as many devices as you like, then tap <b>Done</b> to save."
        }

        def unassignedTitle = unassigned.size() > 0
            ? "🔋 Not Yet Assigned — <span style='color:red;'>${unassigned.size()} device(s)</span>"
            : "🔋 Not Yet Assigned — <span style='color:#22c55e;'>✅ All assigned</span>"

        section(unassignedTitle, hideable: true, hidden: unassigned.size() == 0) {
            if (!unassigned) {
                paragraph "✅ All devices have a battery type assigned."
            } else {
                unassigned.each { dev ->
                    def currentCount = settings["battCount_${dev.id}"] ?: 1
                    def isOther = settings["battType_${dev.id}"] == "Other"
                    def levelStr = ""
                    try {
                        def lvl = dev.currentValue("battery")
                        levelStr = (lvl != null) ? " ${lvl}%" : " —"
                    } catch (e) { levelStr = " —" }
                    def deviceTitle = "<b>${dev.displayName}</b> <span style='color:#1a73e8; font-size:12px;'>${levelStr} · Not set</span>"
                    // Two-column layout: type=5, qty=1 per device, two devices per row (5+1+5+1=12)
                    // When Other is selected, drop to full-width for that device to fit the custom text field
                    if (isOther) {
                        input "battType_${dev.id}", "enum",
                              title: deviceTitle,
                              options: typeOptions,
                              required: false,
                              defaultValue: "",
                              submitOnChange: true,
                              width: 7
                        input "battCustomType_${dev.id}", "text",
                              title: "Custom type:",
                              description: "e.g. CR17450, 4SR44",
                              required: false,
                              defaultValue: settings["battCustomType_${dev.id}"] ?: "",
                              width: 3
                        input "battCount_${dev.id}", "number",
                              title: "Qty:",
                              defaultValue: currentCount,
                              required: false,
                              range: "1..99",
                              width: 2
                    } else {
                        input "battType_${dev.id}", "enum",
                              title: deviceTitle,
                              options: typeOptions,
                              required: false,
                              defaultValue: "",
                              submitOnChange: true,
                              width: 4
                        input "battCount_${dev.id}", "number",
                              title: "Qty:",
                              defaultValue: currentCount,
                              required: false,
                              range: "1..99",
                              width: 2
                    }
                }
            }
        }

        section("✅ Assigned — <span style='color:blue;'>${assigned.size()} device(s)</span>", hideable: true, hidden: true) {
            if (!assigned) {
                paragraph "No devices assigned yet."
            } else {
                assigned.each { dev ->
                    def currentType  = settings["battType_${dev.id}"] ?: ""
                    def currentCount = settings["battCount_${dev.id}"] ?: 1
                    def currentInfo  = getCatalogBatteryInfo(dev)
                    def isOther      = currentType == "Other"
                    def levelStr = ""
                    try {
                        def lvl = dev.currentValue("battery")
                        levelStr = (lvl != null) ? " ${lvl}%" : " —"
                    } catch (e) { levelStr = " —" }
                    def infoStr     = currentInfo ?: "Not set"
                    def deviceTitle = "<b>${dev.displayName}</b> <span style='color:#1a73e8; font-size:12px;'>${levelStr} · ${infoStr}</span>"
                    if (isOther) {
                        input "battType_${dev.id}", "enum",
                              title: deviceTitle,
                              options: typeOptions,
                              required: false,
                              defaultValue: currentType,
                              submitOnChange: true,
                              width: 7
                        input "battCustomType_${dev.id}", "text",
                              title: "Custom type:",
                              description: "e.g. CR17450, 4SR44",
                              required: false,
                              defaultValue: settings["battCustomType_${dev.id}"] ?: "",
                              width: 3
                        input "battCount_${dev.id}", "number",
                              title: "Qty:",
                              defaultValue: currentCount,
                              required: false,
                              range: "1..99",
                              width: 2
                    } else {
                        input "battType_${dev.id}", "enum",
                              title: deviceTitle,
                              options: typeOptions,
                              required: false,
                              defaultValue: currentType,
                              submitOnChange: true,
                              width: 4
                        input "battCount_${dev.id}", "number",
                              title: "Qty:",
                              defaultValue: currentCount,
                              required: false,
                              range: "1..99",
                              width: 2
                    }
                }
            }
        }
    }
}

// ============================================================
// ===================== IGNORED DEVICES PAGE ================
// ============================================================
def ignoredDevicesPage() {
    def devList = (autoDevices ?: []).sort { a, b -> a.displayName.trim() <=> b.displayName.trim() }

    def previouslyIgnored = state.ignoredDeviceIds ?: []
    def currentIgnored    = (settings?.ignoredDevices?.collect { it as String }) ?: []
    def restoredIds       = previouslyIgnored.findAll { !currentIgnored.contains(it) }
    def restoredNames     = []

    if (restoredIds) {
        restoredIds.each { deviceId ->
            def device = autoDevices?.find { it.id == deviceId }
            if (device) {
                def level = device.currentValue("battery") != null ? device.currentValue("battery").toInteger() : 100
                state.history[device.id] = [
                    lastLevel:     level,
                    lastDate:      now(),
                    lastScanDate:  now(),
                    firstSeenDate: now(),
                    replacedTime:  now(),
                    justReplaced:  true,
                    drain:         0.3,
                    samples:       [],
                    zeroCount:     0
                ]
                state.trend[device.id] = "Stable"
                state.history = state.history
                state.replacements = state.replacements ?: []
                state.replacements << [
                    deviceId: device.id,
                    device:   device.displayName,
                    level:    level,
                    date:     new Date().format("MM/dd/yyyy", location.timeZone),
                    type:     "restored"
                ]
                state.replacements = state.replacements.sort { a, b -> b.date <=> a.date }.take(100)
                state.replacements = state.replacements
                restoredNames << "${device.displayName} (${level}%)"
                if (debugMode) log.debug "Device restored from ignored list: ${device.displayName}"
            }
        }
    }

    state.ignoredDeviceIds = currentIgnored

    dynamicPage(name: "ignoredDevicesPage", title: "🚫 Ignored Devices", install: false) {

        section("") {
            paragraph "Select devices to ignore completely. Ignored devices are excluded from all reports, " +
                      "notifications, stale checks, health scoring, and the web portal. They remain in your " +
                      "monitored devices list and in any Hubitat rules.<br><br>" +
                      "<span style='color:#94a3b8; font-size:12px;'>ℹ️ When a device is removed from this list, its drain history resets and a <b>Restored</b> entry is logged " +
                      "in Battery Replacement History. The device starts fresh as if newly added.</span>"
        }

        section("<b>Select Devices to Ignore</b>") {
            input "ignoredDevices", "enum",
                  title: "Ignored devices:",
                  options: devList.collectEntries { dev ->
                      def lvl = ""
                      try { lvl = dev.currentValue("battery") != null ? " (${dev.currentValue("battery").toInteger()}%)" : "" } catch (e) { }
                      [(dev.id): "${dev.displayName}${lvl}"]
                  },
                  multiple: true,
                  required: false,
                  submitOnChange: true
        }

        if (restoredNames) {
            section("<b>✅ Devices Restored</b>") {
                paragraph "<div style='background-color:#d4edda; border-left:3px solid #28a745; border-radius:0 4px 4px 0; padding:10px 14px;'>" +
                          "<span style='color:#155724;'><b>${restoredNames.size()} device(s) restored</b> — drain history reset, health set to ⏳ Pending, Restored entry logged in Battery Replacement History.</span><br><br>" +
                          "<span style='color:#155724;'>" + restoredNames.collect { "• ${it}" }.join("<br>") + "</span></div>"
            }
        }

        section("") {
            paragraph "<span style='color:#94a3b8; font-size:12px;'>Changes take effect immediately when you add or remove devices.</span>"
        }
    }
}

// ============================================================
// ===================== BULK ACTIONS PAGE ===================
// ============================================================
def bulkActionsPage() {
    def devList = (autoDevices ?: []).sort { a, b -> a.displayName.trim() <=> b.displayName.trim() }

    def cooldownMs  = 60000
    def lastRun     = state.bulkActionLastRun ?: 0
    def elapsed     = now() - lastRun
    def onCooldown  = elapsed < cooldownMs
    def secondsLeft = onCooldown ? Math.ceil((cooldownMs - elapsed) / 1000).toInteger() : 0

    dynamicPage(name: "bulkActionsPage", title: "📦 Bulk Actions", install: false) {

        section("") {
            paragraph "<b>Select multiple devices to log battery replacements, reset drain history, or manage ignored devices.</b><br><br>" +
                      "<span style='color:#94a3b8; font-size:12px;'>ℹ️ Each action has a 60-second cooldown after running to prevent accidental back-to-back runs.</span>"
        }

        section("<span style='display:inline-block; background:#e8f0fe; color:#1a73e8; font-size:12px; font-weight:600; text-transform:uppercase; letter-spacing:0.07em; padding:3px 12px; border-radius:20px;'>Select Devices</span>") {
            if (!devList) {
                paragraph "No monitored devices found."
                return
            }
            input "bulkSelectedDevices", "enum",
                  title: "",
                  options: devList.collectEntries { dev ->
                      def lvl = ""
                      try { lvl = dev.currentValue("battery") != null ? " (${dev.currentValue("battery").toInteger()}%)" : "" } catch (e) { }
                      [(dev.id): "${dev.displayName}${lvl}"]
                  },
                  multiple: true,
                  required: false,
                  submitOnChange: false
        }

        if (onCooldown) {
            section("<b>Actions</b>") {
                paragraph "<div style='background-color:#fff3cd; border-left:3px solid #ffc107; border-radius:0 4px 4px 0; padding:10px 14px;'>" +
                          "<span style='color:#856404;'>⏱ Bulk actions are on cooldown — available again in <b>${secondsLeft}s</b>. " +
                          "This prevents accidental back-to-back runs.</span></div>"
            }
            return
        }

        section("<span style='display:inline-block; background:#e8f0fe; color:#1a73e8; font-size:12px; font-weight:600; text-transform:uppercase; letter-spacing:0.07em; padding:3px 12px; border-radius:20px;'>Actions</span>") {
            paragraph "<b>All actions operate on the selected devices above.</b> Toggle to confirm — each executes independently."
            input "bulkReplaceConfirm", "bool",
                  title: "✅ Log battery replacement for all selected",
                  defaultValue: false,
                  submitOnChange: true
            input "bulkResetConfirm", "bool",
                  title: "🔄 Reset drain history (no replacement logged)",
                  defaultValue: false,
                  submitOnChange: true
            input "bulkIgnoreConfirm", "bool",
                  title: "🚫 Ignore all selected devices",
                  defaultValue: false,
                  submitOnChange: true
            input "bulkUnignoreConfirm", "bool",
                  title: "✅ Restore all selected devices (remove from ignored list)",
                  defaultValue: false,
                  submitOnChange: true
        }

        def anyConfirmed = (settings?.bulkReplaceConfirm == true || settings?.bulkResetConfirm == true || settings?.bulkIgnoreConfirm == true || settings?.bulkUnignoreConfirm == true)
        def hasSelection = (settings?.bulkSelectedDevices?.size() ?: 0) > 0

        if (anyConfirmed && !hasSelection) {
            section("") {
                paragraph "<div style='background-color:#f8d7da; border-left:3px solid #f5c6cb; border-radius:0 4px 4px 0; padding:10px 14px;'>" +
                          "<span style='color:#721c24;'>⚠️ No devices selected — please select at least one device above.</span></div>"
            }
            return
        }

        if (anyConfirmed && hasSelection) {
            section("") {
                href(name: "toBulkActionsResult", page: "bulkActionsResultPage",
                     title: "▶ Apply — tap to execute and see results",
                     description: "")
            }
        }
    }
}

// ============================================================
// ===================== BULK ACTIONS RESULT PAGE ============
// ============================================================
def bulkActionsResultPage() {
    def doReplace = settings?.bulkReplaceConfirm == true
    def doReset   = settings?.bulkResetConfirm   == true
    def doIgnore   = settings?.bulkIgnoreConfirm   == true
    def doUnignore = settings?.bulkUnignoreConfirm == true
    def selectedIds = settings?.bulkSelectedDevices ?: []

    def replacedNames  = []
    def resetNames     = []
    def ignoredNames   = []
    def unignoredNames = []
    def skippedNames   = []

    if (selectedIds) {
        def selectedDevices = autoDevices?.findAll { selectedIds.contains(it.id) } ?: []
        def currentIgnored  = (settings?.ignoredDevices?.collect { it as String } ?: [])

        selectedDevices.each { device ->
            try {
                def level = device.currentValue("battery") != null ? device.currentValue("battery").toInteger() : 100

                if (doReplace) {
                    logReplacement(device, level, true)
                    replacedNames << "${device.displayName} (${level}%)"
                } else if (doReset) {
                    def existing = state.history[device.id] ?: [:]
                    state.history[device.id] = [
                        lastLevel:     existing.lastLevel     ?: level,
                        lastDate:      now(),
                        lastScanDate:  now(),
                        firstSeenDate: existing.firstSeenDate ?: existing.replacedTime ?: existing.lastDate ?: now(),
                        replacedTime:  existing.replacedTime,
                        justReplaced:  existing.justReplaced ?: false,
                        drain:         0.3,
                        samples:       [],
                        zeroCount:     0
                    ]
                    state.trend[device.id] = "Stable"
                    state.history = state.history
                    resetNames << device.displayName
                }
                if (doIgnore && !(currentIgnored.contains(device.id as String))) {
                    currentIgnored << (device.id as String)
                    ignoredNames << device.displayName
                }
                if (doUnignore && currentIgnored.contains(device.id as String)) {
                    currentIgnored.remove(device.id as String)
                    def restoreLevel = device.currentValue("battery") != null ? device.currentValue("battery").toInteger() : 100
                    state.history[device.id] = [
                        lastLevel: restoreLevel, lastDate: now(), lastScanDate: now(),
                        firstSeenDate: now(), replacedTime: now(), justReplaced: true,
                        drain: 0.3, samples: [], zeroCount: 0
                    ]
                    state.trend[device.id] = "Stable"
                    state.history = state.history
                    state.replacements = state.replacements ?: []
                    state.replacements << [deviceId: device.id, device: device.displayName, level: restoreLevel,
                        date: new Date().format("MM/dd/yyyy", location.timeZone), type: "restored"]
                    state.replacements = state.replacements.sort { a, b -> b.date <=> a.date }.take(100)
                    unignoredNames << device.displayName
                }
            } catch (e) {
                skippedNames << device.displayName
                log.warn "Bulk action failed for ${device.displayName}: ${e.message}"
            }
        }
        if (doIgnore && ignoredNames)     app.updateSetting("ignoredDevices", [value: currentIgnored, type: "enum"])
        if (doUnignore && unignoredNames) app.updateSetting("ignoredDevices", [value: currentIgnored, type: "enum"])
    }

    state.bulkActionLastRun = now()

    app.updateSetting("bulkReplaceConfirm",   [value: false, type: "bool"])
    app.updateSetting("bulkResetConfirm",     [value: false, type: "bool"])
    app.updateSetting("bulkIgnoreConfirm",    [value: false, type: "bool"])
    app.updateSetting("bulkUnignoreConfirm",  [value: false, type: "bool"])
    app.updateSetting("bulkSelectedDevices",  [value: [], type: "enum"])

    dynamicPage(name: "bulkActionsResultPage", title: "📦 Bulk Actions — Result", install: false) {

        if (!doReplace && !doReset && !doIgnore && !doUnignore) {
            section("<b>Nothing to do</b>") {
                paragraph "No actions were confirmed — nothing was changed. Tap back to return."
            }
            return
        }

        if (replacedNames) {
            section("<b>✅ Battery Replacements Logged</b>") {
                paragraph "<div style='background-color:#d4edda; border-left:3px solid #28a745; border-radius:0 4px 4px 0; padding:10px 14px;'>" +
                          "<span style='color:#155724;'><b>${replacedNames.size()} device(s) updated</b> — replacement logged, drain history reset, health set to ⏳ Pending.</span><br><br>" +
                          "<span style='color:#155724;'>" + replacedNames.collect { "• ${it}" }.join("<br>") + "</span></div>"
            }
        }

        if (resetNames) {
            section("<b>🔄 Drain History Reset</b>") {
                paragraph "<div style='background-color:#d4edda; border-left:3px solid #28a745; border-radius:0 4px 4px 0; padding:10px 14px;'>" +
                          "<span style='color:#155724;'><b>${resetNames.size()} device(s) reset</b> — drain history cleared, health set to ⏳ Pending. No replacement logged.</span><br><br>" +
                          "<span style='color:#155724;'>" + resetNames.collect { "• ${it}" }.join("<br>") + "</span></div>"
            }
        }

        if (ignoredNames) {
            section("<b>🚫 Devices Ignored</b>") {
                paragraph "<div style='background-color:#fff3cd; border-left:3px solid #ffc107; border-radius:0 4px 4px 0; padding:10px 14px;'>" +
                          "<span style='color:#856404;'><b>${ignoredNames.size()} device(s) ignored</b> — excluded from all reports, notifications, and the portal.</span><br><br>" +
                          "<span style='color:#856404;'>" + ignoredNames.collect { "• ${it}" }.join("<br>") + "</span></div>"
            }
        }

        if (unignoredNames) {
            section("<b>✅ Devices Restored</b>") {
                paragraph "<div style='background-color:#d4edda; border-left:3px solid #28a745; border-radius:0 4px 4px 0; padding:10px 14px;'>" +
                          "<span style='color:#155724;'><b>${unignoredNames.size()} device(s) restored</b> — drain history reset, health set to ⏳ Pending, Restored entry logged.</span><br><br>" +
                          "<span style='color:#155724;'>" + unignoredNames.collect { "• ${it}" }.join("<br>") + "</span></div>"
            }
        }

        if (skippedNames) {
            section("<b>⚠️ Skipped</b>") {
                paragraph "<div style='background-color:#fff3cd; border-left:3px solid #ffc107; border-radius:0 4px 4px 0; padding:10px 14px;'>" +
                          "<span style='color:#856404;'>The following devices encountered an error and were skipped:<br><br>" +
                          skippedNames.collect { "• ${it}" }.join("<br>") + "</span></div>"
            }
        }

        section("") {
            paragraph "<span style='color:#94a3b8; font-size:12px;'>⏱ Bulk actions are on a 60-second cooldown. Tap back to return to the management page.</span>"
        }
    }
}

// ============================================================
// ===================== DEVICE ACTIONS PAGE =================
// ============================================================
def deviceActionsPage() {
    def devList    = (autoDevices ?: []).sort { a, b -> a.displayName.trim() <=> b.displayName.trim() }
    def selectedId = settings?.ddDeviceId
    def device     = selectedId ? autoDevices?.find { it.id == selectedId } : null

    if (selectedId && selectedId != settings?.ddLastDeviceId) {
        app.updateSetting("ddReplaceConfirm", [value: false, type: "bool"])
        app.updateSetting("ddResetConfirm",   [value: false, type: "bool"])
        app.updateSetting("ddLastDeviceId",   [value: selectedId, type: "string"])
    }

    def typeOptions = ["": "— Not Set —"]
    typeOptions["_sep1"] = "──────── Standard ────────"
    ["AA", "AAA", "CR2", "CR1632", "CR2016", "CR2032", "CR2430", "CR2450", "CR2477", "CR123A", "9V", "ER14250", "LS14250"].each { typeOptions[it] = it }
    typeOptions["Integrated"] = "Integrated"
    typeOptions["_sep2"] = "──────── Rechargeable ────────"
    ["Rechargeable AA", "Rechargeable AAA", "LIR2016", "LIR2032", "LIR2430", "LIR2450", "18650", "RCR123A", "RCR2"].each { typeOptions[it] = it }
    typeOptions["_sep3"] = "──────── Other ────────"
    typeOptions["Other"] = "Other"

    dynamicPage(name: "deviceActionsPage", title: "⚙️ Device Actions", install: false) {

        section("") {
            paragraph "<b>Manage battery type, log replacements, reset drain history, or ignore a device.</b><br>" +
                      "<span style='color:#94a3b8; font-size:12px;'>ℹ️ Last selection remembered — change the dropdown to switch devices.</span>"
        }

        section("<span style='display:inline-block; background:#e8f0fe; color:#1a73e8; font-size:12px; font-weight:600; text-transform:uppercase; letter-spacing:0.07em; padding:3px 12px; border-radius:20px;'>Select Device</span>") {
            input "ddDeviceId", "enum",
                  title: "",
                  options: devList.collectEntries { [(it.id): it.displayName] },
                  required: false,
                  submitOnChange: true
        }

        if (!device) { return }

        def level       = null
        try { level = device.currentValue("battery") != null ? device.currentValue("battery").toInteger() : "?" } catch (e) { level = "?" }
        def h           = health(device)
        def catalogInfo = getCatalogBatteryInfo(device) ?: "Not set"
        def dead        = isBatteryDead(device)
        def healthStr   = dead ? "🪫 Dead" : getHealthDisplay(device)

        // Device info card — trimmed: Battery, Health, Type only
        section("") {
            paragraph "<div style='background:#dbeafe; border-left:4px solid #1a73e8; border-radius:4px; padding:10px 12px;'>" +
                      "<b style='font-size:15px;'>${device.displayName}</b><br>" +
                      "<span style='color:#374151;'>Battery: <b>${level}%</b> &nbsp;·&nbsp; " +
                      "Health: <b>${healthStr}</b> &nbsp;·&nbsp; " +
                      "Type: <b>${catalogInfo}</b></span></div>"
        }

        // Actions — side by side
        section("<span style='display:inline-block; background:#e8f0fe; color:#1a73e8; font-size:12px; font-weight:600; text-transform:uppercase; letter-spacing:0.07em; padding:3px 12px; border-radius:20px;'>⚡ Actions</span>") {
            input "ddReplaceConfirm", "bool",
                  title: "✅ Log Manual Replacement",
                  description: "Resets drain history and restarts the learning period.",
                  defaultValue: false,
                  submitOnChange: true,
                  width: 6
            input "ddResetConfirm", "bool",
                  title: "🔄 Reset Drain History",
                  description: "Clears samples and resets health to ⏳ Pending. No replacement logged.",
                  defaultValue: false,
                  submitOnChange: true,
                  width: 6
        }
        if (settings?.ddReplaceConfirm == true) {
            section("<b>Replacement Result</b>") {
                def currentLevel = device.currentValue("battery") != null ? device.currentValue("battery").toInteger() : 100
                logReplacement(device, currentLevel, true)
                app.updateSetting("ddReplaceConfirm", [value: false, type: "bool"])
                paragraph "✅ Replacement logged for <b>${device.displayName}</b> at ${currentLevel}%. Health set to ⏳ Pending."
            }
        }
        if (settings?.ddResetConfirm == true) {
            section("<b>Reset Result</b>") {
                def existing = state.history[device.id] ?: [:]
                state.history[device.id] = [
                    lastLevel:     existing.lastLevel     ?: (device.currentValue("battery") != null ? device.currentValue("battery").toInteger() : 100),
                    lastDate:      now(),
                    lastScanDate:  now(),
                    firstSeenDate: existing.firstSeenDate ?: existing.replacedTime ?: existing.lastDate ?: now(),
                    replacedTime:  existing.replacedTime,
                    justReplaced:  existing.justReplaced ?: false,
                    drain:         0.3,
                    samples:       [],
                    zeroCount:     0
                ]
                state.trend[device.id] = "Stable"
                state.history = state.history
                app.updateSetting("ddResetConfirm", [value: false, type: "bool"])
                paragraph "✅ Drain history reset for <b>${device.displayName}</b>. Health set to ⏳ Pending."
            }
        }

        section("") { paragraph "<hr style='border:none; border-top:1px solid #e0e0e0; margin:4px 0;'>" }

        // Ignore
        section("<span style='display:inline-block; background:#e8f0fe; color:#1a73e8; font-size:12px; font-weight:600; text-transform:uppercase; letter-spacing:0.07em; padding:3px 12px; border-radius:20px;'>🚫 Ignore This Device</span>") {
            def isCurrentlyIgnored = (settings?.ignoredDevices?.collect { it as String } ?: []).contains(device.id as String)
            paragraph "<span style='color:#94a3b8; font-size:12px;'>${isCurrentlyIgnored ? 'Currently ignored — excluded from all reports, notifications, and the portal.' : 'Excludes from all reports, notifications, and the portal. History resets when restored.'}</span>"
            input "ddIgnoreConfirm", "bool",
                  title: isCurrentlyIgnored ? "✅ Confirm — restore this device" : "🚫 Confirm — ignore this device",
                  defaultValue: false,
                  submitOnChange: true
        }
        if (settings?.ddIgnoreConfirm == true) {
            section("<b>Ignore Result</b>") {
                def currentIgnored = (settings?.ignoredDevices?.collect { it as String } ?: [])
                def deviceIdStr    = device.id as String
                def isIgnoredNow   = currentIgnored.contains(deviceIdStr)
                if (isIgnoredNow) {
                    def newIgnored = currentIgnored.findAll { it != deviceIdStr }
                    app.updateSetting("ignoredDevices", [value: newIgnored, type: "enum"])
                    def restoreLevel = device.currentValue("battery") != null ? device.currentValue("battery").toInteger() : 100
                    state.history[device.id] = [
                        lastLevel: restoreLevel, lastDate: now(), lastScanDate: now(),
                        firstSeenDate: now(), replacedTime: now(), justReplaced: true,
                        drain: 0.3, samples: [], zeroCount: 0
                    ]
                    state.trend[device.id] = "Stable"
                    state.history = state.history
                    state.replacements = state.replacements ?: []
                    state.replacements << [deviceId: device.id, device: device.displayName, level: restoreLevel,
                        date: new Date().format("MM/dd/yyyy", location.timeZone), type: "restored"]
                    state.replacements = state.replacements.sort { a, b -> b.date <=> a.date }.take(100)
                    paragraph "✅ <b>${device.displayName}</b> restored — drain history reset, health set to ⏳ Pending."
                } else {
                    def newIgnored = currentIgnored + [deviceIdStr]
                    app.updateSetting("ignoredDevices", [value: newIgnored, type: "enum"])
                    paragraph "🚫 <b>${device.displayName}</b> is now ignored."
                }
                app.updateSetting("ddIgnoreConfirm", [value: false, type: "bool"])
            }
        }

        section("") { paragraph "<hr style='border:none; border-top:1px solid #e0e0e0; margin:4px 0;'>" }

        // Battery Type — moved to bottom, "Changes save" note removed
        section("<span style='display:inline-block; background:#e8f0fe; color:#1a73e8; font-size:12px; font-weight:600; text-transform:uppercase; letter-spacing:0.07em; padding:3px 12px; border-radius:20px;'>🔋 Battery Type</span>") {
            input "battType_${device.id}", "enum",
                  title: "Type:",
                  options: typeOptions,
                  required: false,
                  defaultValue: settings["battType_${device.id}"] ?: "",
                  submitOnChange: true,
                  width: 8
            if (settings["battType_${device.id}"] == "Other") {
                input "battCustomType_${device.id}", "text",
                      title: "Custom type:",
                      description: "e.g. CR17450, 4SR44",
                      required: false,
                      defaultValue: settings["battCustomType_${device.id}"] ?: "",
                      width: 8
            }
            input "battCount_${device.id}", "number",
                  title: "Qty:",
                  defaultValue: settings["battCount_${device.id}"] ?: 1,
                  required: false,
                  range: "1..99",
                  width: 2
        }

        section("") { paragraph "<hr style='border:none; border-top:1px solid #e0e0e0; margin:4px 0;'>" }

        section("<b>📋 Replacement History</b>", hideable: true, hidden: true) {
            def deviceHistory = state.replacements?.findAll { r ->
                r.deviceId == device.id || r.device == device.displayName
            }?.sort { a, b -> b.date <=> a.date }

            if (!deviceHistory || deviceHistory.size() == 0) {
                paragraph "No replacements logged yet for this device."
            } else {
                def table = "<table style='width:100%; border-collapse:collapse; border:1px solid #ccc;'>"
                table += "<tr style='font-weight:bold; background-color:#f0f0f0;'>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>Date</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>Level</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>Type</td>"
                table += "</tr>"
                deviceHistory.eachWithIndex { r, idx ->
                    def rowBg   = (idx % 2 == 0) ? "#ffffff" : "#ebebeb"
                    def typeTag = r.type == "manual"   ? "<span style='color:blue;'>Manual</span>" :
                                  r.type == "auto"     ? "<span style='color:green;'>Auto</span>" :
                                  r.type == "restored" ? "<span style='color:#9333ea;'>Restored</span>" : "?"
                    table += "<tr style='background-color:${rowBg};'>"
                    table += "<td style='padding:4px; border:1px solid #ccc;'>${r.date}</td>"
                    table += "<td style='padding:4px; border:1px solid #ccc;'>${r.level}%</td>"
                    table += "<td style='padding:4px; border:1px solid #ccc;'>${typeTag}</td>"
                    table += "</tr>"
                }
                table += "</table>"
                paragraph "<div style='overflow-x:auto;'>${table}</div>"
            }
        }
    }
}


// ============================================================
// ===================== HISTORY PAGE ========================
// ============================================================
def historyPage() {
    def hubIp = location?.hub?.localIP ?: ""
    dynamicPage(name: "historyPage", title: "🔋 Battery Replacement History", install: false) {
        section("") {
            if (!state.replacements || state.replacements.size() == 0) {
                paragraph "No battery replacements have been logged yet."
                return
            }

            def table = "<table style='width:100%; border-collapse: collapse; border: 1px solid #ccc;'>"
            table += "<tr style='font-weight:bold; background-color:#f0f0f0;'>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Device</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Battery Type</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Level</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Date</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Type</td>"
            table += "</tr>"

            state.replacements.sort { a, b -> b.date <=> a.date }.take(100).eachWithIndex { r, idx ->
                def historyRowBg = (idx % 2 == 0) ? "#ffffff" : "#ebebeb"

                // Pill badges
                def typeTag = r.type == "manual"
                    ? "<span style='display:inline-block;background:#dbeafe;color:#1d4ed8;font-size:11px;font-weight:600;padding:2px 9px;border-radius:10px;'>Manual</span>"
                    : r.type == "auto"
                    ? "<span style='display:inline-block;background:#dcfce7;color:#15803d;font-size:11px;font-weight:600;padding:2px 9px;border-radius:10px;'>Auto</span>"
                    : r.type == "restored"
                    ? "<span style='display:inline-block;background:#f3e8ff;color:#7e22ce;font-size:11px;font-weight:600;padding:2px 9px;border-radius:10px;'>Restored</span>"
                    : "?"

                def dev = r.deviceId
                    ? autoDevices?.find { it.id == r.deviceId }
                    : autoDevices?.find { it.displayName == r.device }
                def orphaned    = (dev == null)
                def info        = dev ? getCatalogBatteryInfo(dev) : null
                def infoStr     = info ? "${info}" : ""
                def displayName = dev ? dev.displayName : r.device

                // Clickable device link
                def nameDisplay = orphaned
                    ? "<span style='color:#94a3b8;'>${displayName} <em>(device removed)</em></span>"
                    : (hubIp && dev ? "<a href='http://${hubIp}/device/edit/${dev.id}' target='_blank'>${displayName}</a>" : displayName)

                // Normalize date display — handle both yyyy-MM-dd HH:mm and MM/dd/yyyy formats
                def dateDisplay = r.date ?: ""
                try {
                    if (dateDisplay =~ /^\d{4}-\d{2}-\d{2}/) {
                        def parsed = new Date().parse("yyyy-MM-dd HH:mm", dateDisplay)
                        dateDisplay = parsed.format("MM/dd/yyyy", location.timeZone)
                    }
                } catch (e) { }

                table += "<tr style='background-color:${historyRowBg};${orphaned ? "opacity:0.6;" : ""}'>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${nameDisplay}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${infoStr}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${r.level}%</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${dateDisplay}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${typeTag}</td>"
                table += "</tr>"
            }

            table += "</table>"
            paragraph "<div style='overflow-x:auto; -webkit-overflow-scrolling:touch;'>${table}</div>"
            if (hubIp) paragraph "<span style='color:#94a3b8; font-size:11px;'>⚠ Device links accessible on local network (LAN) only.</span>"
        }

        section("<b>Delete an Entry</b>") {
            href(name: "toDeleteHistory", page: "deleteHistoryPage", title: "🗑️ Delete a History Entry")
        }
    }
}

// ============================================================
// ===================== DELETE HISTORY PAGE =================
// ============================================================
def deleteHistoryPage() {
    app.removeSetting("deleteEntrySelection")
    app.updateSetting("confirmEntryDelete", [value: false, type: "bool"])

    dynamicPage(name: "deleteHistoryPage", title: "Delete a History Entry", install: false) {
        if (!state.replacements || state.replacements.size() == 0) {
            section() { paragraph "No replacement history to delete." }
        } else {
            def options = [:]
            state.replacements.sort { a, b -> b.date <=> a.date }.take(100).eachWithIndex { r, i ->
                options["${i}"] = "🗑️ ${r.device} — ${r.date}"
            }
            section("<b>Select Entry to Delete</b>") {
                input "deleteEntrySelection", "enum",
                      title: "Choose entry",
                      options: options,
                      multiple: false,
                      required: false
            }
            section("<b>Confirm Deletion</b>") {
                input "confirmEntryDelete", "bool",
                      title: "Confirm deletion",
                      defaultValue: false
            }
            section() {
                href(name: "toDeleteHistoryConfirm", page: "deleteHistoryConfirmPage", title: "Submit")
            }
        }
    }
}

// ============================================================
// ============= DELETE HISTORY CONFIRM PAGE =================
// ============================================================
def deleteHistoryConfirmPage() {
    dynamicPage(name: "deleteHistoryConfirmPage", title: "Delete Entry", install: false) {
        section("<b>Result</b>") {
            if (!confirmEntryDelete) {
                paragraph "⚠️ Deletion cancelled — confirm checkbox was not checked."
            } else if (deleteEntrySelection == null) {
                paragraph "⚠️ No entry selected."
            } else {
                def sorted = state.replacements.sort { a, b -> b.date <=> a.date }.take(100)
                def idx    = deleteEntrySelection.toInteger()
                if (idx >= 0 && idx < sorted.size()) {
                    def entry = sorted[idx]
                    state.replacements = state.replacements.findAll {
                        !(it.device == entry.device && it.date == entry.date)
                    }
                    app.updateSetting("confirmEntryDelete", [value: false, type: "bool"])
                    paragraph "✅ Deleted entry for ${entry.device} on ${entry.date}."
                } else {
                    paragraph "⚠️ Entry not found — it may have already been deleted."
                }
            }
        }
    }
}

// ============================================================
// ============= SEND NOTIFICATION PAGE ======================
// ============================================================
def sendNotificationPage() {
    dynamicPage(name: "sendNotificationPage", title: "Send Notification", install: false) {

        def devList    = autoDevices ?: []
        def hasDevices = devList.size() > 0
        def hasTargets = (settings?.notifyDevices?.size() ?: 0) > 0 ||
                         (settings?.pushoverDevices?.size() ?: 0) > 0 ||
                         (settings?.enablePush == true)
        def notifyOn   = settings?.enablePush != false
        def snoozed    = state.notifSnoozedUntil && state.notifSnoozedUntil >= now()

        if (!hasDevices) {
            section("<b>Cannot Send</b>") {
                paragraph "⚠️ No monitored devices are selected. Please go back to the main page, select devices, and tap Done before sending a notification."
            }
            return
        }
        if (!notifyOn) {
            section("<b>Cannot Send</b>") {
                paragraph "⚠️ Notifications are turned off. Enable the Notifications toggle on the main page before sending."
            }
            return
        }
        if (!hasTargets) {
            section("<b>Cannot Send</b>") {
                paragraph "⚠️ No notification devices are configured. Add at least one notification device on the main page before sending."
            }
            return
        }
        if (snoozed) {
            def hoursLeft = Math.ceil((state.notifSnoozedUntil - now()) / 3600000).toInteger()
            section("<b>⚠️ Notifications Snoozed</b>") {
                paragraph "😴 Notifications are currently snoozed for ${hoursLeft}h. This send will bypass the snooze and send immediately."
            }
        }

        section("<b>Confirm</b>") {
            paragraph "This will send a battery summary notification to all configured notification devices right now."
            input "sendNowConfirm", "bool",
                  title: "✅ Confirm — send the notification",
                  defaultValue: false,
                  submitOnChange: true
        }
        if (settings?.sendNowConfirm) {
            section("<b>Result</b>") {
                def savedSnooze = state.notifSnoozedUntil
                state.notifSnoozedUntil = 0
                scheduledSummary()
                state.notifSnoozedUntil = savedSnooze
                app.updateSetting("sendNowConfirm", [value: false, type: "bool"])

                def sentTo = []
                if (settings?.notifyDevices)   sentTo.addAll(settings.notifyDevices.collect { it.displayName })
                if (settings?.pushoverDevices) sentTo.addAll(settings.pushoverDevices.collect { "${it.displayName} (Pushover)" })

                if (sentTo) {
                    paragraph "✅ Notification sent to:\n" + sentTo.collect { "• ${it}" }.join("\n")
                } else {
                    paragraph "✅ Notification sent via hub push."
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
    if (debugMode) log.debug "Manual battery scan triggered by user"

    dynamicPage(name: "forceScanPage", title: "Force Scan", install: false) {
        section("<b>Scan Complete</b>") {
            def devList = (autoDevices ?: []).findAll { !isIgnored(it) }
            def count   = devList.size()
            paragraph "✅ Battery scan complete — ${count} device(s) read. " +
                      "Return to Battery Summary &amp; Trends to see updated values.<br><br>" +
                      "<b>Note:</b> A new drain sample is only recorded if the battery level " +
                      "has changed since the last reading. Devices reporting the same level " +
                      "will not generate a new sample."
        }
    }
}

// ============================================================
// ===================== INFO PAGE ===========================
// ============================================================
def infoPage(Map params = [:]) {
    dynamicPage(name: "infoPage", title: "App Guide & Reference", install: false) {

        section("<b>🌐 Web Portal</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "Enable OAuth in App Code to unlock the web portal — Cloud and Local URLs appear on the main page once active. " +
                      "The portal shows all devices sorted by battery level with health, drain, est days, last activity, and battery type. Auto-refreshes every 2 minutes.<br><br>" +
                      "Add a Link tile to your Hubitat dashboard and paste in your Cloud or Local URL to access it directly.</div>"
        }

        section("<b>🔑 Battery Level Ranges</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>Battery level colors reflect current charge percentage. " +
                      "Health ratings use the same color scheme but are based on drain rate — not battery percentage. " +
                      "A device can show 🟢 Good battery level yet 🔴 Poor health if it is draining unusually fast.<br><br>" +
                      "<div style='overflow-x:auto; -webkit-overflow-scrolling:touch;'><table style='width:100%; border-collapse: collapse;'>" +
                      "<tr style='font-weight:bold;'><td>Level</td><td>Range</td><td>Meaning</td></tr>" +
                      "<tr><td>🟢 Excellent</td><td>100%</td><td>Fully charged</td></tr>" +
                      "<tr><td>🟢 Good</td><td>71–99%</td><td>Healthy — no action needed</td></tr>" +
                      "<tr><td>🟠 Fair</td><td>26–70%</td><td>Getting low — keep an eye on it</td></tr>" +
                      "<tr><td>🔴 Poor</td><td>0–25%</td><td>Replace soon</td></tr>" +
                      "<tr><td>🪫 Dead</td><td>0% (confirmed)</td><td>Battery confirmed dead — replace immediately</td></tr>" +
                      "</table></div></div>"
        }

        section("<b>🔋 Battery Health & Trend</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "<b>Health</b> is a long-term confidence-weighted average drain rate — slow to change by design. It answers: <i>how efficiently has this battery been used overall?</i><br><br>" +
                      "<b>Trend</b> reacts faster to recent readings. It answers: <i>what is this battery doing right now?</i><br><br>" +
                      "In the <b>Health &amp; Trend</b> column:<br>" +
                      "• When Health and Trend agree, only Health is shown — no noise<br>" +
                      "• When Trend is <i>worse</i> than Health, a <span style='color:#f97316;font-weight:bold;'>⚠ warning</span> appears alongside Health — this is the most actionable signal, meaning something has recently changed<br><br>" +
                      "<div style='overflow-x:auto; -webkit-overflow-scrolling:touch;'><table style='width:100%; border-collapse: collapse;'>" +
                      "<tr style='font-weight:bold;'><td>Health</td><td>Drain/day</td><td>What It Means</td></tr>" +
                      "<tr><td>⏳ Pending</td><td>—</td><td>Not enough data yet — still learning</td></tr>" +
                      "<tr><td>🟢 Excellent</td><td>&lt;= 0.3%</td><td>Very efficient, minimal drain</td></tr>" +
                      "<tr><td>🟢 Good</td><td>0.3–0.8%</td><td>Normal battery usage</td></tr>" +
                      "<tr><td>🟠 Fair</td><td>0.8–1.5%</td><td>Above average — worth monitoring</td></tr>" +
                      "<tr><td>🔴 Poor</td><td>&gt; 1.5%</td><td>High drain — notification fires</td></tr>" +
                      "</table></div><br>" +
                      "When trend is active it shows as <b>Moderate Drain</b> or <b>Heavy Drain</b> next to the health rating. " +
                      "Moderate Drain means drain is elevated but not yet at the notification threshold. Heavy Drain means drain is high enough to trigger the High Drain notification.<br><br>" +
                      "<b>Example:</b> A device showing <b>Good ⚠ Heavy Drain</b> has a solid long-term history but is draining unusually fast right now — worth watching before it becomes Poor.<br><br>" +
                      "<b>Note:</b> Door locks use higher drain thresholds. Locks showing a Moderate warning are not necessarily a concern unless drain is consistently high.<br><br>" +
                      "<b>Slow drain devices:</b> Smoke and CO detectors may show 0.00%/day drain. This is normal — they run for 1–3 years on a single set. Est Days is capped at 365; actual life may be longer.</div>"
        }

        section("<b>⏳ Pending Health & Samples</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "Health shows ⏳ Pending until enough data is collected. Progress shows inline — for example: <b>⏳ 3/5 samples · 3/5 days</b><br><br>" +
                      "Requires <b>5 samples</b> and <b>5 days</b> minimum (7 samples for locks, smoke, and CO detectors). " +
                      "Devices that report infrequently clear Pending automatically after <b>14 days</b> with 2+ samples.<br><br>" +
                      "<b>Confidence weighting:</b> Early readings carry less weight — by 10 samples the full measured drain is used.</div>"
        }

        section("<b>🔍 Drain, Estimated Days & Last Battery</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "Drain = %/day based on the last 10 readings. Est Days = current level ÷ drain, capped at 365.<br><br>" +
                      "<b>Last Battery</b> shows when the app last received a battery reading — independent of Last Activity.</div>"
        }

        section("<b>😴 Notification Snooze</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "Silences all Battery Monitor notifications for a configurable number of days. " +
                      "Scanning continues normally — only notifications are paused. " +
                      "Manual Send Notification Now bypasses the snooze. Snooze expires automatically.</div>"
        }

        section("<b>🔋 Device Battery Management</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "Assign battery types, log replacements, reset drain history, and view per-device history from the Reports menu.<br><br>" +
                      "<b>Bulk Actions:</b> Log replacements or reset drain history across multiple devices at once. 60-second cooldown prevents accidental back-to-back runs.<br><br>" +
                      "<b>Ignored Devices:</b> Excludes a device completely from all reports, notifications, stale checks, health scoring, and the portal. " +
                      "Removing from the list resets history, logs a <b>Restored (R)</b> entry, and shows <b>Recently Replaced</b> for up to 24 hours.</div>"
        }

        section("<b>🔄 Force Scan & Replacement Detection</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "<b>How it works:</b> Batteries only drain naturally — they never recharge on their own. So any significant upward jump in battery level means a new battery was installed. " +
                      "Battery Monitor watches for these jumps and logs a replacement automatically.<br><br>" +
                      "<b>Detection rules:</b><br>" +
                      "• Battery level jumps up by at least the configured minimum (default 30%)<br>" +
                      "• Jump is confirmed across two consecutive readings within 48 hours<br>" +
                      "• Device has 3+ prior drain samples and is 3+ days old<br>" +
                      "• 12-hour cooldown prevents duplicate detections<br><br>" +
                      "A single spurious spike will not log a replacement — the two-reading confirmation catches noisy devices. " +
                      "The minimum jump % is configurable under <b>🔋 Device Battery Management → Auto-Detection Settings</b>.<br><br>" +
                      "<b>Manual logging</b> is still available in Device Actions for edge cases: integrated batteries, " +
                      "unreliable reporters, or replacements you want to back-date.<br><br>" +
                      "<b>Force Scan</b> reads all battery levels immediately. A new drain sample only records if the level has changed since the last reading.</div>"
        }

        section("<b>💡 Tips for Best Results</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "• Let new batteries run at least a week before trusting health ratings<br>" +
                      "• Assign battery types in 🔋 Device Battery Management — used in notifications and the portal<br>" +
                      "• After replacing a battery, log it in 🔋 Device Battery Management or use Bulk Actions for multiple devices<br>" +
                      "• Auto-detection logs replacements for any upward battery jump ≥ your configured minimum % (default 30%) confirmed across two readings<br>• If a replacement isn't auto-detected, log it manually in Device Actions — useful for integrated batteries or unreliable reporters<br>" +
                      "• Use Ignored Devices for spare or storage devices you don't want to monitor<br>" +
                      "• Use Reset Drain History if a device shows incorrect Heavy Drain after first install<br>" +
                      "• Use Notification Snooze when traveling</div>"
        }
    }
}
