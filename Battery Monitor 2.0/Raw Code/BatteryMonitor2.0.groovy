definition(
    name: "Battery Monitor 2.0",
    namespace: "jdthomas24",
    author: "Jdthomas24",
    description: "Advanced Hubitat battery monitoring with analytics, trends and replacement tracking. Recurring scan schedule, confidence-weighted health, EWMA smoothing.",
    category: "Convenience",
    importUrl: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/refs/heads/main/Battery%20Monitor%202.0/Raw%20Code/BatteryMonitor2.0.groovy",
    iconUrl: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/refs/heads/main/Tests%20-%20Groovy%20RAW/Battery%20Monitor%202.0%20BETA%20Tests",
    iconX2Url: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/refs/heads/main/Battery%20Monitor%202.0/Raw%20Code/BatteryMonitor2.0.groovy",
    version: "2.5.27",
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

    if (debugMode) {
        runIn(1800, disableDebugLogging)
    }

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
    if (state.replacements      == null) state.replacements      = []
    if (state.history           == null) state.history           = [:]
    if (state.trend             == null) state.trend             = [:]
    if (state.notifSnoozedUntil == null) state.notifSnoozedUntil = 0
    if (state.ignoredDeviceIds  == null) state.ignoredDeviceIds  = []

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
    page(name: "trendsPage")
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
        def portalStatus     = portalEnabled ? "<span style='color:blue; font-weight:bold;'>Enabled</span>" : "<span style='color:red; font-weight:bold;'>Not Enabled</span>"
        def portalSectionTitle = "🌐 Battery Web Portal — ${portalStatus}"

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
        def notificationSettings = (notificationSettings != false)
        def notifSectionTitle    = "<b>Notifications</b> — <span style='color:${notifOn ? "blue" : "red"};'>${notifOn ? "On" : "Off"}</span>"
        section(notifSectionTitle, hideable: true, hidden: notificationSettings) {
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
            href(name: "toSummary",   page: "summaryPage",      title: "<b>Battery Summary</b>",              description: "Battery levels and health ratings")
            href(name: "toTrends",    page: "trendsPage",       title: "<b>Battery Trends</b>",               description: "Drain rates and trend history")
            href(name: "toHistory",   page: "historyPage",      title: "<b>Battery Replacement History</b>",  description: "Auto and manual replacement log")
            href(name: "toDevManage", page: "deviceManagePage", title: "<b>🔋 Device Battery Management</b>", description: "Assign battery types, log replacements, reset drain history, view history")
        }

        section("<b>Help & Support</b>") {
            href(name: "toInfo", page: "infoPage",
                 title: "📖 App Guide & Reference",
                 description: "Colors, drain rates, trends, confidence, and replacement detection explained")
            href url: "https://community.hubitat.com/t/release-battery-monitor-2-0/162329/288",
                 style: "external",
                 title: "💬 Hubitat Community Thread",
                 description: "Questions, feedback, bug reports, and release notes"
            href url: "https://paypal.me/jdthomas24?locale.x=en_US&country.x=US",
                 style: "external",
                 title: "☕ Buy Me a Coffee",
                 description: "Enjoying the app? Any amount is appreciated — thank you!"
        }

        section("<b>Diagnostics</b>") {
            input "debugMode", "bool", title: "Debug Logging (auto-disables after 30 min)", defaultValue: false, submitOnChange: true
            paragraph "<span style='color:#94a3b8; font-size:11px;'>Battery Monitor v2.5.27</span>"
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
    if (data.justReplaced && level < 95) {
        data.justReplaced = false
    } else if (data.justReplaced && (now() - safeTime(replacedAt)) > 1000 * 60 * 60 * 24) {
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
    oldLevel = oldLevel != null ? oldLevel : (state.history[device.id]?.lastLevel != null ? state.history[device.id].lastLevel : 0)

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

    def data            = state.history[device.id]
    def largeJump       = (newLevel - oldLevel)
    def hadDrainHistory = data?.samples && data.samples.size() >= 2
    def detected        = false

    if (!hadDrainHistory) return

    if (newLevel >= 95 && oldLevel <= 40)                                            detected = true
    else if (newLevel >= 90 && oldLevel <= 40 && largeJump >= 25)                    detected = true
    else if (newLevel >= 90 && hadDrainHistory && largeJump >= 15 && oldLevel <= 40) detected = true
    else if (newLevel >= 95 && hadDrainHistory && oldLevel <= 40)                    detected = true

    if (detected) {
        data.zeroCount = 0
        logReplacement(device, newLevel, false)
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
        date:     new Date().format("yyyy-MM-dd HH:mm", location.timeZone),
        type:     manual ? "manual" : "auto"
    ]
    state.replacements = state.replacements.sort { a, b -> b.date <=> a.date }

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
        html.append("<th>Device</th><th>Battery</th><th>Health</th><th>Drain</th><th>Est Days</th><th>Last Activity</th><th>Type</th>")
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

            def badgeCls  = dead ? "badge-dead" : h == "Poor" ? "badge-poor" : h == "Fair" ? "badge-fair" : h == "Good" ? "badge-good" : h == "Excellent" ? "badge-excellent" : "badge-pending"
            def badgeLbl  = dead ? "🪫 Dead" : h
            def barColor  = level > 50 ? "#22c55e" : level > 25 ? "#f97316" : "#ef4444"
            def drainStr  = (dead || h == "Pending") ? "—" : "${String.format('%.2f', drain)}%"
            def estStr    = (dead || h == "Pending" || est == null) ? "—" : "${est}d"
            def staleHtml = stale ? "<span class='stale-tag'>⚠ Stale</span>" : ""

            html.append("<tr>")
            html.append("<td><b>${device.displayName}</b>${staleHtml}</td>")
            html.append("<td>${level}% <div class='batt-bg'><div class='batt-fg' style='width:${level}%;background:${barColor};'></div></div></td>")
            html.append("<td><span class='badge ${badgeCls}'>${badgeLbl}</span></td>")
            html.append("<td>${drainStr}</td>")
            html.append("<td>${estStr}</td>")
            html.append("<td>${lastAct}</td>")
            html.append("<td>${catalog}</td>")
            html.append("</tr>")
        }

        html.append("</tbody></table>")
        html.append("<p style='text-align:center;font-size:10px;color:#444;margin-top:20px;'>Battery Monitor v2.5.27 &nbsp;·&nbsp; jdthomas24</p>")
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
    dynamicPage(name: "summaryPage", title: "Battery Summary", install: false) {

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
            paragraph "<span style='color:red; font-weight:bold;'>⚠ Device links below are accessible only on your local network (LAN). They will not work remotely.</span>"
            paragraph "<span style='color:red; font-weight:bold;'>¹ Last Battery</span> shows when this app last received a battery reading — from a scheduled scan or device event. It is independent of Last Activity. A device can be active recently but still show an old Last Battery timestamp if its battery level has not changed or reported."
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
            table += "<th style='padding:4px; border:1px solid #ccc;'>Battery Health</th>"
            table += "<th style='padding:4px; border:1px solid #ccc;'>Last Battery¹</th>"
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
                try { color = getBatteryLevelDisplay(level, device) } catch (e) { color = "${level}%" }

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

                if (dead) {
                    table += "<td style='padding:4px; border:1px solid #ccc; color:#94a3b8;' data-order='999'>—</td>"
                    table += "<td style='padding:4px; border:1px solid #ccc; color:#94a3b8;' data-order='999'>—</td>"
                    table += "<td style='padding:4px; border:1px solid #ccc; color:#94a3b8;' data-order='999'>—</td>"
                } else if (health(device) == "Pending") {
                    table += "<td style='padding:4px; border:1px solid #ccc; color:#94a3b8;' data-order='9999'>📈</td>"
                    table += "<td style='padding:4px; border:1px solid #ccc; color:#94a3b8;' data-order='9999'>📈</td>"
                    def healthOrder = 99
                    table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${healthOrder}'>${healthDisplay}</td>"
                } else {
                    table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${String.format('%.2f', drain)}'>${String.format('%.2f', drain)}</td>"
                    def estDisplay = est != null ? est.toString() : "—"
                    def estOrder   = est != null ? est : 9999
                    table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${estOrder}'>${estDisplay}</td>"
                    def healthOrder = health(device) == "Poor" ? 4 : health(device) == "Fair" ? 3 : health(device) == "Good" ? 2 : health(device) == "Excellent" ? 1 : 99
                    table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${healthOrder}'>${healthDisplay}</td>"
                }

                table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${lastBatteryMs}'>${lastBatteryStr}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${lastActivityMs}'>${lastActivityStr}${staleTag}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${catalogInfo}</td>"
                table += "</tr>"
            }

            table += "</tbody></table>"

            paragraph rawHtml: true, """
<div style='background-color:#e8f0fe; border-left:4px solid #1a73e8; border-radius:4px; padding:8px 12px; margin-bottom:8px; font-size:13px; color:#1a1a1a;'>ℹ️ Drain and Est Days show <code style='background:#d2e3fc; padding:1px 5px; border-radius:3px; font-weight:bold;'>📈</code> for <b>Pending</b> devices — the app is actively learning. Both show <code style='background:#d2e3fc; padding:1px 5px; border-radius:3px; font-weight:bold;'>—</code> for 🪫 <b>Dead</b> batteries. Data populates automatically once the Pending gate clears.</div>
<div style='overflow-x:auto; -webkit-overflow-scrolling:touch;'>${table}</div>
<script>
\$(document).ready(function() {
    \$('#batteryTable').DataTable({
        paging:     false,
        info:       false,
        searching:  true,
        order:      [[1, 'asc']],
        columnDefs: [
            { type: 'num', targets: [1, 2, 3, 5, 6] }
        ]
    });
});
</script>
"""
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
    typeOptions["_sep2"] = "──────── Rechargeable ────────"
    ["Rechargeable AA", "Rechargeable AAA", "LIR2016", "LIR2032", "LIR2430", "LIR2450", "18650"].each { typeOptions[it] = it }
    typeOptions["_sep3"] = "──────── Other ────────"
    typeOptions["Other"] = "Other"

    dynamicPage(name: "deviceManagePage", title: "🔋 Device Battery Management", install: false) {

        section("<b>⚙️ Device Actions</b>") {
            href(name: "toDeviceActions", page: "deviceActionsPage",
                 title: "Open Device Actions",
                 description: "Log a replacement, reset drain history, change battery type, or view history for a single device. Last selected device is remembered.")
        }

        section("<b>📦 Bulk Actions</b>") {
            href(name: "toBulkActions", page: "bulkActionsPage",
                 title: "Open Bulk Actions",
                 description: "Log replacements or reset drain history across multiple devices at once. Useful when swapping batteries in several devices at the same time.")
        }

        def ignoredSectionTitle = ignoredCount > 0
            ? "<b>🚫 Ignored Devices</b> — <span style='color:blue;'>${ignoredCount} ignored</span>"
            : "<b>🚫 Ignored Devices</b>"
        section(ignoredSectionTitle) {
            href(name: "toIgnoredDevices", page: "ignoredDevicesPage",
                 title: "Manage Ignored Devices",
                 description: ignoredCount > 0
                     ? "${ignoredCount} device(s) ignored — excluded from all reports, notifications, and the web portal. Removing a device resets its history and logs a Restored entry."
                     : "No devices ignored — add devices to exclude them from all reports, notifications, stale checks, health scoring, and the web portal.")
        }

        section("") {
            paragraph "Assign a battery type and quantity to each device so Battery Monitor can include battery type in notifications and replacement history. " +
                      "This helps you know exactly what to buy when a replacement is needed.<br><br>" +
                      "Set the type and count for as many devices as you like, then tap <b>Done</b> to save."
        }
        section("<b>Battery Types</b>", hideable: true, hidden: true) {
            devList.each { dev ->
                def currentType  = settings["battType_${dev.id}"] ?: ""
                def currentCount = settings["battCount_${dev.id}"] ?: 1
                def currentInfo  = getCatalogBatteryInfo(dev)
                def levelStr     = ""
                try {
                    def lvl  = dev.currentValue("battery")
                    levelStr = (lvl != null) ? " ${lvl}%" : " —"
                } catch (e) { levelStr = " —" }
                def infoStr     = currentInfo ?: "Not set"
                def deviceTitle = "<b>${dev.displayName}</b> <span style='color:#1a73e8; font-size:12px;'>${levelStr} · ${infoStr}</span>"
                input "battType_${dev.id}", "enum",
                      title: deviceTitle,
                      options: typeOptions,
                      required: false,
                      defaultValue: currentType,
                      width: 8
                if (settings["battType_${dev.id}"] == "Other") {
                    input "battCustomType_${dev.id}", "text",
                          title: "Custom type:",
                          description: "e.g. CR17450, 4SR44",
                          required: false,
                          defaultValue: settings["battCustomType_${dev.id}"] ?: "",
                          width: 8
                }
                input "battCount_${dev.id}", "number",
                      title: "Qty:",
                      defaultValue: currentCount,
                      required: false,
                      range: "1..99",
                      width: 4
            }
        }
    }
}

// ============================================================
// ===================== IGNORED DEVICES PAGE ================
// ============================================================
def ignoredDevicesPage() {
    def devList = (autoDevices ?: []).sort { a, b -> a.displayName.trim() <=> b.displayName.trim() }

    // Detect and process restores directly here — same pattern as bulk actions
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
                    date:     new Date().format("yyyy-MM-dd HH:mm", location.timeZone),
                    type:     "restored"
                ]
                state.replacements = state.replacements.sort { a, b -> b.date <=> a.date }
                state.replacements = state.replacements
                restoredNames << "${device.displayName} (${level}%)"
                if (debugMode) log.debug "Device restored from ignored list: ${device.displayName}"
            }
        }
    }

    // Always sync ignoredDeviceIds to current selection on every page render
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
            paragraph "Select multiple devices to log battery replacements or reset drain history in one shot. " +
                      "Useful when swapping batteries across several devices at once.<br><br>" +
                      "<span style='color:#94a3b8; font-size:12px;'>ℹ️ Each action has a 60-second cooldown after running to prevent accidental back-to-back runs.</span>"
        }

        section("<b>Select Devices</b>") {
            if (!devList) {
                paragraph "No monitored devices found."
                return
            }
            input "bulkSelectedDevices", "enum",
                  title: "Devices:",
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

        section("<b>Actions</b>") {
            paragraph "Both actions below operate on the selected devices above. " +
                      "You may confirm one or both — each executes independently."
            input "bulkReplaceConfirm", "bool",
                  title: "✅ Confirm — log battery replacement for all selected devices",
                  defaultValue: false,
                  submitOnChange: true
            input "bulkResetConfirm", "bool",
                  title: "🔄 Confirm — reset drain history for all selected devices (no replacement logged)",
                  defaultValue: false,
                  submitOnChange: true
        }

        def anyConfirmed = (settings?.bulkReplaceConfirm == true || settings?.bulkResetConfirm == true)
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
    def doReplace   = settings?.bulkReplaceConfirm == true
    def doReset     = settings?.bulkResetConfirm   == true
    def selectedIds = settings?.bulkSelectedDevices ?: []

    def replacedNames = []
    def resetNames    = []
    def skippedNames  = []

    if (selectedIds) {
        def selectedDevices = autoDevices?.findAll { selectedIds.contains(it.id) } ?: []

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
            } catch (e) {
                skippedNames << device.displayName
                log.warn "Bulk action failed for ${device.displayName}: ${e.message}"
            }
        }
    }

    state.bulkActionLastRun = now()

    app.updateSetting("bulkReplaceConfirm",  [value: false, type: "bool"])
    app.updateSetting("bulkResetConfirm",    [value: false, type: "bool"])
    app.updateSetting("bulkSelectedDevices", [value: [], type: "enum"])

    dynamicPage(name: "bulkActionsResultPage", title: "📦 Bulk Actions — Result", install: false) {

        if (!doReplace && !doReset) {
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
    typeOptions["_sep2"] = "──────── Rechargeable ────────"
    ["Rechargeable AA", "Rechargeable AAA", "LIR2016", "LIR2032", "LIR2430", "LIR2450", "18650"].each { typeOptions[it] = it }
    typeOptions["_sep3"] = "──────── Other ────────"
    typeOptions["Other"] = "Other"

    dynamicPage(name: "deviceActionsPage", title: "⚙️ Device Actions", install: false) {

        section("<b>Select Device</b>") {
            paragraph "Select a device to manage its battery type, log a replacement, reset drain history, or view replacement history.<br>" +
                      "<span style='color:#94a3b8; font-size:12px;'>ℹ️ The last selected device is remembered. Change the dropdown to switch devices.</span>"
            input "ddDeviceId", "enum",
                  title: "Device:",
                  options: devList.collectEntries { [(it.id): it.displayName] },
                  required: false,
                  submitOnChange: true
        }

        if (!device) { return }

        def level       = null
        try { level = device.currentValue("battery") != null ? device.currentValue("battery").toInteger() : "?" } catch (e) { level = "?" }
        def h           = health(device)
        def drain       = displayDrain(device)
        def est         = estDays(device)
        def catalogInfo = getCatalogBatteryInfo(device) ?: "Not set"
        def dead        = isBatteryDead(device)
        def estStr      = (est != null) ? "${est}d" : "—"
        def drainStr    = (h == "Pending" || dead) ? "—" : "${drain}%/day"
        def healthStr   = dead ? "🪫 Dead" : getHealthDisplay(device)

        section("") {
            paragraph "<div style='background:#f0f4ff; border-left:4px solid #1a73e8; border-radius:4px; padding:10px 12px;'>" +
                      "<b style='font-size:15px;'>${device.displayName}</b><br>" +
                      "<span style='color:#374151;'>Battery: <b>${level}%</b> &nbsp;·&nbsp; " +
                      "Health: <b>${healthStr}</b> &nbsp;·&nbsp; " +
                      "Drain: <b>${drainStr}</b> &nbsp;·&nbsp; " +
                      "Est Days: <b>${estStr}</b> &nbsp;·&nbsp; " +
                      "Type: <b>${catalogInfo}</b></span></div>"
        }

        section("<b>🔋 Battery Type</b>") {
            paragraph "Changes save when you tap Done."
            input "battType_${device.id}", "enum",
                  title: "Battery Type:",
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
                  width: 4
        }

        section("<b>✅ Log Manual Replacement</b>") {
            paragraph "Logs a replacement entry, resets drain history, and restarts the learning period."
            input "ddReplaceConfirm", "bool",
                  title: "Confirm — log battery replacement now",
                  defaultValue: false,
                  submitOnChange: true
        }
        if (settings?.ddReplaceConfirm == true) {
            section("<b>Replacement Result</b>") {
                def currentLevel = device.currentValue("battery") != null ? device.currentValue("battery").toInteger() : 100
                logReplacement(device, currentLevel, true)
                app.updateSetting("ddReplaceConfirm", [value: false, type: "bool"])
                paragraph "✅ Battery replacement logged for <b>${device.displayName}</b> at ${currentLevel}%. Drain history reset — health will show ⏳ Pending while fresh data is collected."
            }
        }

        section("<b>🔄 Reset Drain History</b>") {
            paragraph "Clears drain samples and resets health to ⏳ Pending without logging a replacement. Use when a device shows incorrect Heavy Drain."
            input "ddResetConfirm", "bool",
                  title: "Confirm — reset drain history without logging a replacement",
                  defaultValue: false,
                  submitOnChange: true
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
                paragraph "✅ Drain history reset for <b>${device.displayName}</b>. Health will show ⏳ Pending while fresh samples are collected."
            }
        }

        section("<b>📋 Replacement History</b>") {
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
                    def typeTag = r.type == "manual" ? "<span style='color:blue;'>Manual</span>" :
                                  r.type == "auto"   ? "<span style='color:green;'>Auto</span>" : "?"
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
// ===================== TRENDS PAGE =========================
// ============================================================
def trendsPage() {
    dynamicPage(name: "trendsPage", title: "Battery Trends", install: false) {

        section("") {
            paragraph rawHtml: true, """
<link rel="stylesheet" href="https://cdn.datatables.net/1.13.6/css/jquery.dataTables.min.css">
<script src="https://cdn.datatables.net/1.13.6/js/jquery.dataTables.min.js"></script>
"""
            href(name: "toForceScanFromTrends", page: "forceScanPage",
                 title: "🔄 Force Scan Now",
                 description: "Tap to immediately read battery levels from all monitored devices")

            def devList = (autoDevices ?: []).findAll { it?.currentValue("battery") != null && !isIgnored(it) }
            if (!devList) { paragraph "No battery devices found for trends."; return }

            def trendPriority = ["Heavy Drain": 1, "Moderate": 2, "Stable": 3]

            devList = devList.sort { a, b ->
                def deadA = isBatteryDead(a)
                def deadB = isBatteryDead(b)
                if (deadA != deadB) return deadA ? -1 : 1
                def hA = health(a)
                def hB = health(b)
                def pendingA = hA == "Pending"
                def pendingB = hB == "Pending"
                if (pendingA != pendingB) return pendingA ? 1 : -1
                def trendA = state.trend[a.id] ?: "Stable"
                def trendB = state.trend[b.id] ?: "Stable"
                def prioA  = trendPriority[trendA] ?: 3
                def prioB  = trendPriority[trendB] ?: 3
                if (prioA != prioB) return prioA <=> prioB
                def levelA = a.currentValue("battery") != null ? a.currentValue("battery").toInteger() : 100
                def levelB = b.currentValue("battery") != null ? b.currentValue("battery").toInteger() : 100
                if (levelA != levelB) return levelA <=> levelB
                return a.displayName.trim() <=> b.displayName.trim()
            }

            def hubIp = location?.hub?.localIP ?: ""

            def table = "<table id='trendsTable' style='width:100%; border-collapse: collapse; border: 1px solid #ccc;'>"
            table += "<thead><tr style='font-weight:bold; background-color:#f0f0f0;'>"
            table += "<th style='padding:4px; border:1px solid #ccc;'>Device</th>"
            table += "<th style='padding:4px; border:1px solid #ccc;'>Battery</th>"
            table += "<th style='padding:4px; border:1px solid #ccc;'>Trend</th>"
            table += "<th style='padding:4px; border:1px solid #ccc;'>Drain %/day</th>"
            table += "<th style='padding:4px; border:1px solid #ccc;'>Battery Health</th>"
            table += "</tr></thead><tbody>"
            def trendsRowNum = 0

            devList.each { device ->
                def dead          = isBatteryDead(device)
                def hist          = safeHistory(device)
                def level         = device.currentValue("battery") != null ? device.currentValue("battery").toInteger() : 100
                def drain         = getDrain(device)
                def trend         = state.trend[device.id] ?: "Unknown"
                def h             = health(device)
                def trendColor    = trend == "Heavy Drain" ? "🔴" : trend == "Moderate" ? "🟠" : "🟢"
                def color         = getBatteryLevelDisplay(level, device)
                def healthDisplay = dead ? "<span style='color:#94a3b8;'>—</span>" : getHealthDisplay(device)
                def trendOrder    = dead ? 1 : (h == "Pending" ? 999 : ((trendPriority[trend] ?: 4) + 1))
                def healthOrder   = dead ? 999 : (h == "Poor" ? 4 : h == "Fair" ? 3 : h == "Good" ? 2 : h == "Excellent" ? 1 : 99)
                def trendsRowBg   = (trendsRowNum % 2 == 0) ? "#ffffff" : "#ebebeb"
                trendsRowNum++
                table += "<tr style='background-color:${trendsRowBg};'>"

                if (hubIp) {
                    table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${device.displayName.toLowerCase().trim()}'><a href='http://${hubIp}/device/edit/${device.id}' target='_blank'>${device.displayName}</a></td>"
                } else {
                    table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${device.displayName.toLowerCase().trim()}'>${device.displayName}</td>"
                }
                table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${level}'>${color}</td>"

                if (dead) {
                    table += "<td style='padding:4px; border:1px solid #ccc; color:#94a3b8;' data-order='1'>—</td>"
                    table += "<td style='padding:4px; border:1px solid #ccc; color:#94a3b8;' data-order='1'>—</td>"
                } else if (h == "Pending") {
                    table += "<td style='padding:4px; border:1px solid #ccc; color:#94a3b8;' data-order='999'>📈</td>"
                    table += "<td style='padding:4px; border:1px solid #ccc; color:#94a3b8;' data-order='999'>📈</td>"
                } else {
                    table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${trendOrder}'>${trendColor} ${trend}</td>"
                    table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${String.format('%.2f', drain)}'>${String.format('%.2f', drain)}</td>"
                }

                table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${healthOrder}'>${healthDisplay}</td>"
                table += "</tr>"
            }

            table += "</tbody></table>"

            paragraph rawHtml: true, """
${hubIp ? "<span style='color:red; font-weight:bold;'>⚠ Device links below are accessible only on your local network (LAN). They will not work remotely.</span><br><br>" : ""}
<div style='background-color:#e8f0fe; border-left:4px solid #1a73e8; border-radius:4px; padding:8px 12px; margin-bottom:8px; font-size:13px; color:#1a1a1a;'>ℹ️ Trend and Drain show <code style='background:#d2e3fc; padding:1px 5px; border-radius:3px; font-weight:bold;'>📈</code> for <b>Pending</b> devices — the app is actively learning. Both show <code style='background:#d2e3fc; padding:1px 5px; border-radius:3px; font-weight:bold;'>—</code> for 🪫 <b>Dead</b> batteries. Data populates automatically once the Pending gate clears.</div>
<div style='overflow-x:auto; -webkit-overflow-scrolling:touch;'>${table}</div>
<script>
\$(document).ready(function() {
    \$('#trendsTable').DataTable({
        paging:     false,
        info:       false,
        searching:  true,
        order:      [[2, 'asc'], [1, 'asc']],
        columnDefs: [
            { type: 'num', targets: [1, 3, 4] }
        ]
    });
});
</script>
"""
        }
    }
}

// ============================================================
// ===================== HISTORY PAGE ========================
// ============================================================
def historyPage() {
    dynamicPage(name: "historyPage", title: "Battery Replacement History", install: false) {
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
            table += "<td style='padding:4px; border:1px solid #ccc;'>Type?</td>"
            table += "</tr>"

            state.replacements.sort { a, b -> b.date <=> a.date }.eachWithIndex { r, idx ->
                def historyRowBg = (idx % 2 == 0) ? "#ffffff" : "#ebebeb"
                def typeTag      = r.type == "manual"   ? "<span style='color:blue;'>M</span>" :
                                   r.type == "auto"     ? "<span style='color:green;'>A</span>" :
                                   r.type == "restored" ? "<span style='color:#9333ea;'>R</span>" : "?"
                def dev          = r.deviceId
                    ? autoDevices?.find { it.id == r.deviceId }
                    : autoDevices?.find { it.displayName == r.device }
                def orphaned    = (dev == null)
                def info        = dev ? getCatalogBatteryInfo(dev) : null
                def infoStr     = info ? "${info}" : ""
                def displayName = dev ? dev.displayName : r.device
                def nameDisplay = orphaned
                    ? "<span style='color:#94a3b8;'>${displayName} <em>(device removed)</em></span>"
                    : displayName

                table += "<tr style='background-color:${historyRowBg};${orphaned ? "opacity:0.6;" : ""}'>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${nameDisplay}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${infoStr}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${r.level}%</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${r.date}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${typeTag}</td>"
                table += "</tr>"
            }

            table += "</table>"
            paragraph "<div style='overflow-x:auto; -webkit-overflow-scrolling:touch;'>${table}</div>"
            paragraph "<b>Legend:</b> <span style='color:green;'>A</span> = Automatic, <span style='color:blue;'>M</span> = Manual, <span style='color:#9333ea;'>R</span> = Restored from ignored"
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
            state.replacements.sort { a, b -> b.date <=> a.date }.eachWithIndex { r, i ->
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
                def sorted = state.replacements.sort { a, b -> b.date <=> a.date }
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
                      "Return to Battery Summary or Trends to see updated values.<br><br>" +
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

        section("<b>🔋 Battery Health & Trends</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>Health is a long-term confidence-weighted average drain rate. " +
                      "Trend uses the same thresholds but reacts faster to recent readings — a short spike may push Trend to 🔴 Heavy Drain " +
                      "while Health stays 🟢 Good until enough samples confirm the pattern.<br><br>" +
                      "<div style='overflow-x:auto; -webkit-overflow-scrolling:touch;'><table style='width:100%; border-collapse: collapse;'>" +
                      "<tr style='font-weight:bold;'><td>Health</td><td>Trend</td><td>Drain/day</td><td>What It Means</td></tr>" +
                      "<tr><td>⏳ Pending</td><td>—</td><td>—</td><td>Not enough data yet</td></tr>" +
                      "<tr><td>🟢 Excellent</td><td>🟢 Stable</td><td><= 0.3%</td><td>Very efficient, minimal drain</td></tr>" +
                      "<tr><td>🟢 Good</td><td>🟠 Moderate</td><td>0.3–0.8%</td><td>Normal battery usage</td></tr>" +
                      "<tr><td>🟠 Fair</td><td>🔴 Heavy Drain</td><td>0.8–1.5%</td><td>Above average — worth monitoring, no alert</td></tr>" +
                      "<tr><td>🔴 Poor</td><td>🔴 Heavy Drain</td><td>&gt; 1.5%</td><td>High drain — High Drain alert fires</td></tr>" +
                      "</table></div><br>" +
                      "<b>Note:</b> Door locks use higher drain thresholds than other devices. Locks showing 🟠 Moderate trend are not necessarily a concern unless drain is consistently very high.<br><br>" +
                      "<b>Slow drain devices:</b> Smoke detectors, CO detectors, and other always-on low-power devices may show drain rates at or near 0.00%/day. This is normal — these devices are designed to run for 1–3 years on a single set of batteries. Est Days shows 365 as a maximum; actual battery life may be significantly longer.</div>"
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
                      "Replacement detection fires automatically when a device jumps from ≤40% up to ≥90–95% — requires 2+ prior drain samples.<br><br>" +
                      "<b>Force Scan</b> reads all battery levels immediately. A new drain sample only records if the level has changed since the last reading.</div>"
        }

        section("<b>💡 Tips for Best Results</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "• Let new batteries run at least a week before trusting health ratings<br>" +
                      "• Assign battery types in 🔋 Device Battery Management — used in notifications and the portal<br>" +
                      "• After replacing a battery, log it in 🔋 Device Battery Management or use Bulk Actions for multiple devices<br>" +
                      "• If a replacement isn't auto-detected, log it manually — auto-detection requires a jump from ≤40% to ≥90–95% with 2+ prior samples<br>" +
                      "• Use Ignored Devices for spare or storage devices you don't want to monitor<br>" +
                      "• Use Reset Drain History if a device shows incorrect Heavy Drain after first install<br>" +
                      "• Use Notification Snooze when traveling</div>"
        }
    }
}
