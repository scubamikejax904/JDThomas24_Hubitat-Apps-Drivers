definition(
    name: "Battery Monitor 2.0",
    namespace: "jdthomas24",
    author: "Jdthomas24",
    description: "Advanced Hubitat battery monitoring with analytics, trends and replacement tracking. Recurring scan schedule, confidence-weighted health, EWMA smoothing.",
    category: "Convenience",
    importUrl: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/refs/heads/main/Battery%20Monitor%202.0/Raw%20Code/BatteryMonitor2.0.groovy",
    iconUrl: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/refs/heads/main/Tests%20-%20Groovy%20RAW/Battery%20Monitor%202.0%20BETA%20Tests",
    iconX2Url: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/refs/heads/main/Battery%20Monitor%202.0/Raw%20Code/BatteryMonitor2.0.groovy",
    version: "2.4.20",
    doNotFocus: true
)

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

    def devList = autoDevices ?: []
    def currentIds = devList.collect { it.id as String }
    state.history?.keySet()?.findAll { !currentIds.contains(it) }?.each { removedId ->
        state.history.remove(removedId)
        state.trend?.remove(removedId)
        if (debugMode) log.debug "Cleaned up removed device: ${removedId}"
    }

    // v2.4.20: purge orphaned replacement history entries for devices no longer monitored
    // Prevents stale entries accumulating when devices are replaced and removed from BM
    if (state.replacements) {
        def before = state.replacements.size()
        state.replacements = state.replacements.findAll { r ->
            r.deviceId ? currentIds.contains(r.deviceId) : true
        }
        def pruned = before - state.replacements.size()
        if (pruned > 0 && debugMode) log.debug "Purged ${pruned} orphaned replacement history entr${pruned == 1 ? 'y' : 'ies'}"
    }

    // v2.4.19: fixed firstSeenDate migration — use explicit HashMap copy to ensure
    // nested map mutation persists correctly in Hubitat's state storage.
    // Previous versions used in-place mutation which silently failed to persist.
    def migrationDirty = false
    state.history?.each { id, data ->
        if (data && !data.firstSeenDate) {
            def newData = new HashMap(data)
            newData.firstSeenDate = newData.replacedTime ?: newData.lastDate ?: now()
            state.history[id] = newData
            migrationDirty = true
            if (debugMode) log.debug "Migrated firstSeenDate for device ${id}: ${new Date(newData.firstSeenDate as long)}"
        }
    }
    if (migrationDirty) state.history = state.history

    // v2.4.15: one-time migration — add deviceId to existing replacement history entries
    // Matches on displayName against currently selected devices. Entries for deleted devices
    // will not match and remain name-only — these will show as orphaned in the history page.
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
    scheduleReportFrequency()
    scheduleScanInterval()
    def devList = autoDevices ?: []
    if (devList) {
        subscribe(devList, "battery", batteryHandler)
    }
}

// =================== APPLY CUSTOM LABEL ===================
def applyCustomLabel() {
    if (settings?.customAppName) {
        if (app.label != settings?.customAppName) {
            app.updateLabel(settings.customAppName)
            if (debugMode) log.debug "App label updated to: ${settings.customAppName}"
        }
    }
}

preferences {
    page(name: "mainPage")
    page(name: "summaryPage")
    page(name: "trendsPage")
    page(name: "historyPage")
    page(name: "deleteHistoryPage")
    page(name: "deleteHistoryConfirmPage")
    page(name: "manualReplacementPage")
    page(name: "manualReplacementConfirmPage")
    page(name: "infoPage")
    page(name: "batteryCatalogPage")
    page(name: "forceScanPage")
    page(name: "sendNotificationPage")
    page(name: "resetDrainPage")
    page(name: "resetDrainConfirmPage")
}

// ============================================================
// ===================== MAIN PAGE ===========================
// ============================================================
def mainPage() {
    applyCustomLabel()

    dynamicPage(name: "mainPage",
                title: "",
                install: true, uninstall: true) {

        // v2.4.15: App Display Name always shows current label in header
        def currentLabel  = app.label ?: "Battery Monitor 2.0"
        def appNameTitle  = "<b>App Display Name</b> — <span style='color:blue;'>${currentLabel}</span>"
        section(appNameTitle, hideable: true, hidden: true) {
            paragraph "Enter a name to rename this app in your Hubitat app list."
            input "customAppName", "text",
                  title: "Custom App Name",
                  description: "Rename how this app appears in your Hubitat app list",
                  required: false
        }

        // v2.4.14: device count in section header — eliminates separate summary paragraph
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
                    if (!state.history[device.id]) {
                        def currentLevel = device.currentValue("battery")
                        state.history[device.id] = [
                            lastLevel:    currentLevel != null ? currentLevel.toInteger() : 100,
                            lastDate:     now(),
                            lastScanDate: now(),
                            firstSeenDate: now(),
                            drain:        0.3,
                            samples:      [],
                            justReplaced: false
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

        // ── Notification Snooze ──────────────────────────────
        def snoozed = state.notifSnoozedUntil && state.notifSnoozedUntil >= now()
        def snoozeHoursLeft = snoozed ? Math.ceil((state.notifSnoozedUntil - now()) / 3600000).toInteger() : 0
        // v2.4.15: snooze status in section header — snoozed in orange, off in red
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

        // ── Notifications ────────────────────────────────────
        def notifOn = settings?.enablePush != false
        def notificationSettings = (notificationSettings != false)
        def notifSectionTitle = "<b>Notifications</b> — <span style='color:${notifOn ? "blue" : "red"};'>${notifOn ? "ON" : "OFF"}</span>"
        section(notifSectionTitle, hideable: true, hidden: notificationSettings) {
            paragraph "ℹ️ Enable the toggle below to reveal notification settings including frequency, timing, device targets, and which battery groups to include in reports."
            input "enablePush", "bool", title: "Enable notifications", defaultValue: true, submitOnChange: true

            if (settings?.enablePush != false) {
                input "reportFrequency", "enum",
                      title: "Notification Frequency:",
                      options: [
                          "daily":  "Daily",
                          "every2": "Every 2 Days",
                          "every3": "Every 3 Days",
                          "weekly": "Weekly"
                      ],
                      defaultValue: "daily"

                input "summaryTime", "time",
                      title: "Notification Time:",
                      required: false

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
                input "notifyPoor",      "bool", title: "🔴 Include Poor (≤25%)",                              defaultValue: true
                input "notifyFair",      "bool", title: "🟠 Include Fair (26–70%)",                            defaultValue: true
                input "notifyGood",      "bool", title: "🟢 Include Good (71–99%)",                            defaultValue: false
                input "notifyExcellent", "bool", title: "🟢 Include Excellent (100%)",                         defaultValue: false
                input "notifyHighDrain", "bool", title: "⚠️ Include Health (Fair, Poor, & High Drain Only)",   defaultValue: true
                input "notifyStale",     "bool", title: "⚠️ Include Stale Devices",                            defaultValue: true
                input "staleThresholdHours", "number",
                      title: "<b>Mark device as stale if no activity for X hours</b>",
                      defaultValue: 24
                input "suppressEmptyReport", "bool", title: "🔕 Don't send notification if nothing to report <b>(Skips Notification entirely when all enabled toggles are Empty)</b>", defaultValue: false
                input "notifyIncludeAppLink", "bool", title: "🔗 Include link to Battery Monitor app <b>(Local Only)</b>", defaultValue: false

                paragraph "<b>Send notification now:</b>"
                href(name: "toSendNotification", page: "sendNotificationPage",
                     title: "📤 Send Notification Now")
            }
        }

        section("<b>Reports:</b>") {
            href(name: "toSummary",   page: "summaryPage",           title: "<b>Battery Summary</b>",             description: "Battery levels and health ratings")
            href(name: "toTrends",    page: "trendsPage",            title: "<b>Battery Trends</b>",              description: "Drain rates and trend history")
            href(name: "toHistory",   page: "historyPage",           title: "<b>Battery Replacement History</b>", description: "Auto and manual replacement log")
            href(name: "toManualRep", page: "manualReplacementPage", title: "<b>Manual Battery Replacement</b>",  description: "Log a replacement now")
            href(name: "toCatalog",   page: "batteryCatalogPage",    title: "<b>🔋 Battery Catalog</b>",          description: "Battery types per device")
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
            paragraph "<span style='color:#94a3b8; font-size:11px;'>Battery Monitor v2.4.20</span>"
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

// ============================================================
// ===================== SCAN INTERVAL SCHEDULING ============
// ============================================================
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
    def devList = autoDevices ?: []
    if (!devList) return
    if (debugMode) log.debug "Running scheduled battery scan for ${devList.size()} device(s)"
    devList.each { device ->
        try {
            def level = device.currentValue("battery")?.toInteger()
            if (level != null) {
                updateBattery(device, level)
                if (debugMode) log.debug "Scanned ${device.displayName}: ${level}%"
            }
        } catch (e) {
            log.warn "Scan failed for ${device.displayName}: ${e.message}"
        }
    }
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
    // ── Global notification snooze check ──────────────────
    if (state.notifSnoozedUntil && state.notifSnoozedUntil >= now()) {
        if (debugMode) log.debug "Notifications snoozed until ${new Date(state.notifSnoozedUntil)} — skipping summary"
        return
    }

    def devList = (autoDevices ?: []).findAll { it?.currentValue("battery") != null }
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
        def tags = settings.pushoverPrefix.trim()
        def priorityMatch = tags =~ /^(\[[EHLNS]\])(.*)/
        if (priorityMatch) {
            prefix  = priorityMatch[0][1]
            postfix = priorityMatch[0][2].trim()
        } else {
            postfix = tags
        }
    }

    // v2.4.10: timestamp on header line
    def timestamp = new Date().format("MM/dd HH:mm", location.timeZone)
    def body = "${prefix}🔋 Battery Summary — ${timestamp}\n"

    // v2.4.10: build stale list with clean "inactive Xd/Xh" format (no redundant "ago")
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
            highDrainList.each { dev ->
                body += "• ${dev.health} (${dev.drain}%) ${dev.name} (${dev.level}%)\n"
            }
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
            // v2.4.15: look up by deviceId if available
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
}

// ============================================================
// ===================== BATTERY HANDLER =====================
// ============================================================
def batteryHandler(evt) {
    def device = evt.device
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
            firstSeenDate: now(),  // v2.4.13: stable anchor for days gate — never overwritten except on replacement
            drain:         0.3,
            samples:       [],
            justReplaced:  false,
            zeroCount:     0
        ]
        state.trend[device.id] = "Stable"
        // v2.4.9: force persist on seed
        state.history = state.history
        data = state.history[device.id]
    }

    // v2.4.16: treat 1% same as 0% for dead battery detection
    if (level <= 1) {
        data.zeroCount = (data.zeroCount ?: 0) + 1
    } else {
        data.zeroCount = 0
    }

    if (isBatteryDead(device)) {
        data.lastLevel    = level
        data.lastScanDate = now()
        // v2.4.9: force persist
        state.history[device.id] = data
        state.history = state.history
        if (debugMode) log.debug "${device.displayName}: battery confirmed dead (${data.zeroCount} consecutive 0% readings)"
        return
    }

    if (level <= 1 && (data.zeroCount ?: 0) < 3) {
        data.justReplaced = false
        data.replacedTime = null
        data.drain        = 1.0
        data.samples      = []
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
            def alpha      = 0.3
            def prevSmooth = (data.samples && data.samples.size() > 0) ? data.samples[-1] : clampedDrain
            def smoothed   = alpha * clampedDrain + (1 - alpha) * prevSmooth
            data.samples << smoothed
            if (data.samples.size() > 10) data.samples.remove(0)
            data.lastDate = now()
        }

        if (data.samples && data.samples.size() > 0) {
            def avg = data.samples.sum() / data.samples.size()
            data.drain = Math.min(avg, 3.0)
            updateTrend(device, data.drain)
        }
    }

    data.lastLevel    = level
    data.lastScanDate = now()

    // v2.4.9: force persist after every mutation
    state.history[device.id] = data
    state.history = state.history
}

// ============================================================
// ===================== DEAD BATTERY DETECTION ==============
// ============================================================
def isBatteryDead(device) {
    def data = state.history?.get(device.id)
    if (!data) return false
    def level     = device.currentValue("battery")
    def zeroCount = data.zeroCount ?: 0
    // v2.4.16: treat 1% same as 0% — 3 consecutive readings at 0% or 1% confirms dead
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
    // v2.4.12: split sensor types — contact/motion report frequently (standard gate)
    //          smoke/CO report rarely (slow reporter gate, higher sample requirement)
    def isSensor     = devType.contains("contact") || devType.contains("motion")
    def isSlowSensor = devType.contains("smoke")   || devType.contains("carbonmonoxide")

    def adjustedDrain = isLock       ? drain * 0.4 :
                        isSensor     ? drain * 0.5 :
                        isSlowSensor ? drain * 0.5 : drain

    if (adjustedDrain > 5) adjustedDrain = 0.3

    def hist = state.history[device.id]
    if (hist?.samples && hist.samples.size() >= 3) {
        def avg = hist.samples.sum() / hist.samples.size()
        if (avg > 3) adjustedDrain = Math.min(adjustedDrain, 1.0)
    }

    def stableThreshold   = isLock                       ? 0.9 : (isSensor || isSlowSensor) ? 0.6 : 0.3
    def moderateThreshold = isLock                       ? 2.0 : (isSensor || isSlowSensor) ? 1.5 : 0.8

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
    def conf = getConfidence(device)
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
    // v2.4.12: split sensor types
    def isSensor     = devType.contains("contact") || devType.contains("motion")
    def isSlowSensor = devType.contains("smoke")   || devType.contains("carbonmonoxide")

    // v2.4.12: only locks and slow sensors (smoke/CO) require 7 samples
    //          contact/motion sensors use standard 5-sample gate
    def minSamples = (isLock || isSlowSensor) ? 7 : 5

    def daysSinceReplaced = 999
    if (hist?.replacedTime) {
        daysSinceReplaced = (now() - (hist.replacedTime as Long)) / (1000 * 60 * 60 * 24)
    } else if (hist?.firstSeenDate) {
        // v2.4.19: explicit Long cast to ensure correct arithmetic on deserialized state values
        daysSinceReplaced = (now() - (hist.firstSeenDate as Long)) / (1000 * 60 * 60 * 24)
    } else if (hist?.lastDate) {
        // legacy fallback for devices seeded before 2.4.13
        daysSinceReplaced = (now() - (hist.lastDate as Long)) / (1000 * 60 * 60 * 24)
    }

    def slowReporter = (daysSinceReplaced >= 14 && samples >= 2)
    if (!slowReporter && (samples < minSamples || daysSinceReplaced < 5)) return "Pending"

    def rawDrain      = getDrain(device)
    def adjustedDrain = isLock       ? rawDrain * 0.4 :
                        isSensor     ? rawDrain * 0.5 :
                        isSlowSensor ? rawDrain * 0.5 : rawDrain

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
    // v2.4.12: split sensor types
    def isSensor     = devType.contains("contact") || devType.contains("motion")
    def isSlowSensor = devType.contains("smoke")   || devType.contains("carbonmonoxide")

    def minSamples = (isLock || isSlowSensor) ? 7 : 5

    if (h == "Pending") {
        // v2.4.19: use explicit Long cast to ensure correct arithmetic on deserialized state values
        def daysSinceReplaced = 0
        if (hist?.replacedTime) {
            daysSinceReplaced = ((now() - (hist.replacedTime as Long)) / (1000 * 60 * 60 * 24)).toInteger()
        } else if (hist?.firstSeenDate) {
            daysSinceReplaced = ((now() - (hist.firstSeenDate as Long)) / (1000 * 60 * 60 * 24)).toInteger()
        } else if (hist?.lastDate) {
            daysSinceReplaced = ((now() - (hist.lastDate as Long)) / (1000 * 60 * 60 * 24)).toInteger()
        }

        def minDays   = 5
        def samplePct = Math.min(100, (samples / minSamples * 100).toInteger())
        def daysPct   = Math.min(100, (daysSinceReplaced / minDays * 100).toInteger())

        // v2.4.12: HTML progress bar — clean render in Hubitat UI
        def bar = { int pct ->
            "<div style='background:#e2e8f0; border-radius:3px; width:80px; display:inline-block; height:8px; vertical-align:middle;'>" +
            "<div style='background:#1a73e8; width:${pct}%; height:8px; border-radius:3px;'></div></div>"
        }

        return "<span style='color:#94a3b8; font-size:11px;'>⏳ Pending<br>" +
               "Samples: ${bar(samplePct)} ${samples}/${minSamples}<br>" +
               "Days:&nbsp;&nbsp;&nbsp;&nbsp;${bar(daysPct)} ${daysSinceReplaced}/${minDays}d</span>"
    }

    def colorMap = [
        "Excellent": "#22c55e",
        "Good":      "#22c55e",
        "Fair":      "#f97316",
        "Poor":      "#ef4444"
    ]
    def color = colorMap[h] ?: "#94a3b8"
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
        def count = (battCount != null && battCount.toString().trim() != "") ? battCount.toString().trim() : "1"
        // v2.4.12: resolve "Other" to the custom free text entry
        def resolvedType = (battType == "Other")
            ? (settings["battCustomType_${device.id}"]?.trim() ?: "Other")
            : battType
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
    if (device && isBatteryDead(device)) {
        return "<span style='color:#ef4444;'>🪫 Dead</span>"
    }

    level = (level instanceof Number ? level : null) != null ? level : 100

    def cat = level >= 100 ? "🟢 Excellent" :
              level > 70   ? "🟢 Good" :
              level > 25   ? "🟠 Fair" :
                             "🔴 Poor"

    def label = "${cat} (${level}%)"

    def data         = (device && state.history?.containsKey(device.id)) ? safeHistory(device) : null
    def showTag      = data?.justReplaced == true
    def replacedTime = data?.replacedTime

    if (showTag) {
        replacedTime = safeTime(replacedTime)
        def hoursSinceReplacement = (now() - replacedTime) / (1000 * 60 * 60)
        if (hoursSinceReplacement >= 24) {
            if (data) data.justReplaced = false
            showTag = false
        }
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

    data.drain        = 0.3
    data.samples      = []
    data.lastLevel    = newLevel
    data.lastDate     = now()
    data.lastScanDate = now()
    data.firstSeenDate = now()  // v2.4.13: reset anchor on replacement — new battery = fresh gate
    data.justReplaced = true
    data.replacedTime = now()
    data.zeroCount    = 0
    state.trend[device.id]     = "Stable"
    data.lastReplacementLogged = now()

    state.replacements = state.replacements?.findAll { it.device != device.displayName } ?: []
    state.replacements << [
        deviceId: device.id,          // v2.4.15: store ID as primary key
        device:   device.displayName,
        level:    newLevel,
        date:     new Date().format("yyyy-MM-dd HH:mm", location.timeZone),
        type:     manual ? "manual" : "auto"
    ]
    state.replacements = state.replacements.sort { a, b -> b.date <=> a.date }

    state.history[device.id] = data
    state.history = state.history
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
                try { it?.currentValue("battery") != null } catch (e) {
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

                def staleTag = (stale && lastActivity) ? " ⚠️ Stale" : ""

                def healthDisplay = ""
                try { healthDisplay = getHealthDisplay(device) } catch (e) { healthDisplay = "Unknown" }

                def name = device.displayName ?: "Unknown Device"
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
                    def estDisplay  = est != null ? est.toString() : "—"
                    def estOrder    = est != null ? est : 9999
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
            def devList = (autoDevices ?: []).findAll { it?.currentValue("battery") != null }

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
                def dead    = isBatteryDead(device)
                def hist    = safeHistory(device)
                def level   = device.currentValue("battery") != null ? device.currentValue("battery").toInteger() : 100
                def drain   = getDrain(device)
                def trend   = state.trend[device.id] ?: "Unknown"
                def h       = health(device)
                def samples = hist?.samples?.size() ?: 0

                def trendColor    = trend == "Heavy Drain" ? "🔴" : trend == "Moderate" ? "🟠" : "🟢"
                def color         = getBatteryLevelDisplay(level, device)
                def healthDisplay = dead ? "<span style='color:#94a3b8;'>—</span>" : getHealthDisplay(device)

                def trendOrder  = dead ? 1 : (h == "Pending" ? 999 : ((trendPriority[trend] ?: 4) + 1))
                def healthOrder = dead ? 999 : (h == "Poor" ? 4 : h == "Fair" ? 3 : h == "Good" ? 2 : h == "Excellent" ? 1 : 99)
                def trendsRowBg = (trendsRowNum % 2 == 0) ? "#ffffff" : "#ebebeb"
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

        section("<b>🔄 Reset Drain History</b>", hideable: true, hidden: true) {
            paragraph "Use this to reset drain history for specific devices. " +
                      "This clears accumulated samples and resets drain to default — health returns to ⏳ Pending. " +
                      "Use when a device shows incorrect Heavy Drain due to stale or inaccurate samples. " +
                      "<b>This does not log a battery replacement.</b>"
            href(name: "toResetDrain", page: "resetDrainPage",
                 title: "🔄 Reset Drain History",
                 description: "Select devices to reset")
        }
    }
}

// ============================================================
// ===================== RESET DRAIN PAGE ====================
// ============================================================
def resetDrainPage() {
    app.removeSetting("resetDrainDevices")
    app.updateSetting("resetDrainConfirm", [value: false, type: "bool"])

    def devList = (autoDevices ?: []).sort { a, b -> a.displayName.trim() <=> b.displayName.trim() }

    dynamicPage(name: "resetDrainPage", title: "Reset Drain History", install: false) {
        section("<b>Select Devices to Reset</b>") {
            if (!devList || devList.size() == 0) {
                paragraph "No battery devices available. Please select devices on the main page first."
            } else {
                paragraph "Select one or more devices to reset. Their drain history, samples, and trend will be cleared. " +
                          "Health will return to Pending until new samples are collected. " +
                          "<b>Battery replacement history is not affected.</b>"
                input "resetDrainDevices", "enum",
                      title: "Select devices to reset",
                      options: devList.collectEntries { [(it.id): "${it.displayName} (${it.currentValue('battery') ?: '?'}% - ${state.trend[it.id] ?: 'Unknown'})"] }
                                      .sort { a, b -> a.value <=> b.value },
                      multiple: true,
                      required: false
            }
        }
        section("<b>Confirm Reset</b>") {
            input "resetDrainConfirm", "bool",
                  title: "Confirm - clear drain history for selected devices",
                  defaultValue: false
        }
        section() {
            href(name: "toResetDrainConfirm", page: "resetDrainConfirmPage",
                 title: "Submit Reset")
        }
    }
}

// ============================================================
// ================ RESET DRAIN CONFIRM PAGE =================
// ============================================================
def resetDrainConfirmPage() {
    def devList = autoDevices ?: []

    dynamicPage(name: "resetDrainConfirmPage", title: "Reset Drain History", install: false) {
        section("<b>Result</b>") {
            if (!resetDrainConfirm) {
                paragraph "Reset cancelled - confirm checkbox was not checked."
            } else if (!resetDrainDevices) {
                paragraph "No devices selected."
            } else {
                def successCount = 0
                def resetNames = []

                resetDrainDevices.each { deviceId ->
                    def device = devList.find { it.id == deviceId }
                    if (device) {
                        if (!state.history[device.id]) {
                            state.history[device.id] = [
                                lastLevel:    device.currentValue("battery") != null ? device.currentValue("battery").toInteger() : 100,
                                lastDate:     now(),
                                lastScanDate: now(),
                                drain:        0.3,
                                samples:      [],
                                justReplaced: false,
                                zeroCount:    0
                            ]
                        } else {
                            state.history[device.id].drain        = 0.3
                            state.history[device.id].samples      = []
                            state.history[device.id].lastDate     = now()
                            state.history[device.id].lastScanDate = now()
                            state.history[device.id].zeroCount    = 0
                        }
                        state.trend[device.id] = "Stable"
                        state.history = state.history
                        resetNames << device.displayName
                        successCount++
                        if (debugMode) log.debug "Reset drain history for ${device.displayName}"
                    }
                }

                if (successCount > 0) {
                    paragraph "Drain history reset for ${successCount} device(s): " +
                              resetNames.join(", ") +
                              ". Health will show Pending while fresh samples are collected. " +
                              "Return to Battery Trends to confirm."
                } else {
                    paragraph "No valid devices found."
                }
            }
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
                def typeTag = r.type == "manual" ? "<span style='color:blue;'>M</span>" :
                              r.type == "auto"   ? "<span style='color:green;'>A</span>" : "?"

                // v2.4.15: look up by deviceId first, fall back to name for legacy entries
                def dev = r.deviceId
                    ? autoDevices?.find { it.id == r.deviceId }
                    : autoDevices?.find { it.displayName == r.device }

                def orphaned = (dev == null)
                def info     = dev ? getCatalogBatteryInfo(dev) : null
                def infoStr  = info ? "${info}" : ""

                // v2.4.15: use current device name if found (handles renames), else use stored name
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
            paragraph "<b>Legend:</b> <span style='color:green;'>A</span> = Automatic, <span style='color:blue;'>M</span> = Manual"
        }

        section("<b>Delete an Entry</b>") {
            href(name: "toDeleteHistory", page: "deleteHistoryPage",
                 title: "🗑️ Delete a History Entry")
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
                href(name: "toDeleteHistoryConfirm", page: "deleteHistoryConfirmPage",
                     title: "Submit")
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
// ===================== MANUAL REPLACEMENT PAGE =============
// ============================================================
def manualReplacementPage() {
    if (state.replacements == null) state.replacements = []
    def devList = autoDevices ?: []

    app.removeSetting("replaceDevicesManual")
    app.updateSetting("replaceConfirmManual", [value: false, type: "bool"])

    dynamicPage(name: "manualReplacementPage", title: "Manual Battery Replacement", install: false) {
        section("<b>Select Devices</b>") {
            if (!devList || devList.size() == 0) {
                paragraph "⚠ No battery devices available. Please select devices on the main page first."
            } else {
                input "replaceDevicesManual", "enum",
                      title: "Select Devices (can choose multiple)",
                      options: devList.collectEntries { [(it.id): it.displayName] }
                                      .sort { a, b -> a.value <=> b.value },
                      multiple: true,
                      required: false
            }
        }
        section("<b>Confirm Replacement</b>") {
            input "replaceConfirmManual", "bool",
                  title: "Confirm Battery Replaced",
                  required: false
        }
        section() {
            href(name: "toManualRepConfirm", page: "manualReplacementConfirmPage",
                 title: "Submit Replacement")
        }
    }
}

// ============================================================
// ================ MANUAL REPLACEMENT CONFIRM PAGE ==========
// ============================================================
def manualReplacementConfirmPage() {
    if (state.replacements == null) state.replacements = []
    def devList = autoDevices ?: []

    dynamicPage(name: "manualReplacementConfirmPage", title: "Confirm Replacement", install: false) {
        section("<b>Replacement Registered</b>") {
            if (replaceDevicesManual && replaceConfirmManual) {
                def successCount = 0

                replaceDevicesManual.each { deviceId ->
                    def device = devList.find { it.id == deviceId }
                    if (device) {
                        def level = device.currentValue("battery") != null ? device.currentValue("battery").toInteger() : 100

                        if (!state.history[device.id]) {
                            state.history[device.id] = [
                                lastLevel: level,
                                lastDate:  now(),
                                drain:     0.3,
                                samples:   [],
                                zeroCount: 0
                            ]
                        }

                        state.replacements = state.replacements?.findAll { it.device != device.displayName } ?: []
                        state.replacements << [
                            deviceId: device.id,          // v2.4.15: store ID as primary key
                            device:   device.displayName,
                            level:    level,
                            date:     new Date().format("yyyy-MM-dd HH:mm", location.timeZone),
                            type:     "manual"
                        ]
                        state.replacements = state.replacements.sort { a, b -> b.date <=> a.date }

                        state.history[device.id].drain         = 0.3
                        state.history[device.id].samples       = []
                        state.history[device.id].zeroCount     = 0
                        state.trend[device.id]                 = "Stable"
                        state.history[device.id].lastLevel     = level
                        state.history[device.id].lastDate      = now()
                        state.history[device.id].lastScanDate  = now()
                        state.history[device.id].firstSeenDate = now()  // v2.4.13: reset anchor on manual replacement
                        state.history[device.id].justReplaced  = true
                        state.history[device.id].replacedTime  = now()

                        state.history = state.history
                        successCount++
                    }
                }

                if (successCount > 0) {
                    paragraph "✅ Battery replacement for ${successCount} device(s) has been recorded. Health will show ⏳ Pending while fresh data is collected."
                } else {
                    paragraph "❌ No valid devices found. Please select devices on the main page first."
                }
            } else {
                paragraph "⚠ Please select device(s) and confirm replacement or use the Back button to cancel."
            }
        }
    }
}

// ============================================================
// ============= SEND NOTIFICATION PAGE ======================
// ============================================================
def sendNotificationPage() {
    dynamicPage(name: "sendNotificationPage", title: "Send Notification", install: false) {

        def devList      = autoDevices ?: []
        def hasDevices   = devList.size() > 0
        def hasTargets   = (settings?.notifyDevices?.size() ?: 0) > 0 ||
                           (settings?.pushoverDevices?.size() ?: 0) > 0 ||
                           (settings?.enablePush == true)
        def notifyOn     = settings?.enablePush != false
        def snoozed      = state.notifSnoozedUntil && state.notifSnoozedUntil >= now()

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
            def devList = autoDevices ?: []
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
// ===================== BATTERY CATALOG PAGE ================
// ============================================================
def batteryCatalogPage() {
    dynamicPage(name: "batteryCatalogPage", title: "🔋 Battery Catalog", install: false) {

        def devList = (autoDevices ?: []).sort { a, b -> a.displayName.trim() <=> b.displayName.trim() }

        if (!devList) {
            section() { paragraph "No devices found. Please select devices on the main page first." }
            return
        }

        def typeOptions = ["": "— Not Set —"]
        typeOptions["_sep1"] = "──────── Standard ────────"
        ["AA", "AAA", "CR2", "CR1632", "CR2016", "CR2032", "CR2430", "CR2450", "CR2477", "CR123A", "9V", "ER14250", "LS14250"].each {
            typeOptions[it] = it
        }
        typeOptions["_sep2"] = "──────── Rechargeable ────────"
        ["Rechargeable AA", "Rechargeable AAA", "LIR2016", "LIR2032", "LIR2430", "LIR2450", "18650"].each {
            typeOptions[it] = it
        }
        typeOptions["_sep3"] = "──────── Other ────────"
        typeOptions["Other"] = "Other"

        devList.each { device ->
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
            }
        }

        section("") {
            paragraph "<span style='color:red; font-weight:bold;'>⚠ Select a battery type and enter the count for each device below. Tap Done to save.</span>"
            paragraph "ℹ️ Existing entries from previous versions are automatically migrated to the new format when you open this page."
        }

        section("<b>Battery Catalog</b>") {
            devList.each { device ->
                section() {
                    paragraph "<b>${device.displayName.trim()}</b>"
                    input "battType_${device.id}",
                          "enum",
                          title: "Battery Type:",
                          options: typeOptions,
                          required: false,
                          defaultValue: settings["battType_${device.id}"] ?: "",
                          submitOnChange: true
                    // v2.4.12: show free text input when "Other" is selected
                    if (settings["battType_${device.id}"] == "Other") {
                        input "battCustomType_${device.id}",
                              "text",
                              title: "Custom Battery Type:",
                              description: "Enter battery type (e.g. CR17450, 4SR44, 28A)",
                              required: false,
                              defaultValue: settings["battCustomType_${device.id}"] ?: ""
                    }
                    input "battCount_${device.id}",
                          "number",
                          title: "Battery Count:",
                          defaultValue: settings["battCount_${device.id}"] ?: 1,
                          required: false,
                          range: "1..99"
                }
            }
        }
    }
}

// ============================================================
// ===================== INFO PAGE ===========================
// ============================================================
def infoPage(Map params = [:]) {
    dynamicPage(name: "infoPage", title: "App Guide & Reference", install: false) {

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
                      "<b>Note:</b> Door locks use higher drain thresholds than other devices. Because locks drive a motor to operate, a higher baseline drain is normal and expected. Locks showing 🟠 Moderate trend are not necessarily a concern unless drain is consistently very high.<br><br>" +
                      "<b>Status Icons:</b><br>" +
                      "<div style='overflow-x:auto; -webkit-overflow-scrolling:touch;'><table style='width:100%; border-collapse: collapse;'>" +
                      "<tr style='font-weight:bold;'><td>Icon</td><td>Meaning</td></tr>" +
                      "<tr><td>⏳</td><td>Pending — not enough data yet to assign a health verdict</td></tr>" +
                      "<tr><td>⚠️</td><td>Warning — high drain device or stale activity</td></tr>" +
                      "<tr><td>⏱</td><td>Stale — device has not reported within the configured threshold</td></tr>" +
                      "<tr><td>🪫</td><td>Dead — battery confirmed at 0% for 3 or more consecutive scans</td></tr>" +
                      "</table></div></div>"
        }

        section("<b>⏳ Pending Health & Samples</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>The app withholds a health verdict and shows <b>⏳ Pending</b> until enough data is collected. " +
                      "This prevents false Poor ratings from sparse early readings. While Pending, Trend and Drain show 📈 — the app is actively learning.<br><br>" +
                      "The Battery Health column shows a progress bar for Pending devices so you can see exactly where each device stands:<br>" +
                      "<code style='background:#f0f0f0; padding:2px 6px; border-radius:3px;'>Samples: ▓▓▓░░░░░░░ 3/5</code><br>" +
                      "<code style='background:#f0f0f0; padding:2px 6px; border-radius:3px;'>Days:&nbsp;&nbsp;&nbsp;&nbsp;▓▓▓▓▓▓░░░░ 3/5d</code><br><br>" +
                      "<b>Standard gate</b> — both must be met:<br>" +
                      "• At least <b>5 samples</b> collected (7 for locks, smoke, and CO detectors)<br>" +
                      "• At least <b>5 days</b> since the battery was replaced or first seen<br><br>" +
                      "<b>Slow reporter gate</b> — for devices like smoke detectors that rarely report:<br>" +
                      "• After <b>14 days</b> with at least <b>2 samples</b>, Pending clears automatically<br><br>" +
                      "<b>v2.4.12 change:</b> Contact and motion sensors now use the standard 5-sample gate. " +
                      "Previously they shared the 7-sample gate with locks and smoke detectors, but contact and motion sensors report frequently enough that the extended gate was unnecessary.<br><br>" +
                      "<b>Note:</b> Updating or reinstalling the app resets the sample timing gates. All devices will return to Pending temporarily while fresh data is collected.<br><br>" +
                      "<b>Confidence weighting:</b> Early readings carry less weight than established ones. " +
                      "With 5 samples the blend is partial — by 10 samples the full measured drain is used.</div>"
        }

        section("<b>🔍 Drain, Estimated Days & Last Battery</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>Drain shows how fast a device uses battery (% per day). " +
                      "Calculated using EWMA (exponential weighted moving average) across the last 10 readings.<br><br>" +
                      "<b>While health is ⏳ Pending</b> — Drain shows 📈 and Estimated Days shows 📈 — the app is actively learning. " +
                      "Both will populate automatically once the device clears the Pending gate.<br><br>" +
                      "<b>Estimated days remaining</b> = current level ÷ average daily drain. " +
                      "Capped at 365 days — estimates beyond a year are not considered meaningful.<br><br>" +
                      "<b>Last Battery</b> shows when the app last received a battery reading — from a scheduled scan or device event. " +
                      "It is independent of Last Activity.</div>"
        }

        section("<b>😴 Notification Snooze</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "The <b>Notification Snooze</b> feature on the main page silences all Battery Monitor notifications for a configurable number of days.<br><br>" +
                      "This is useful when you are traveling, on vacation, or otherwise unavailable to act on battery alerts. " +
                      "Snoozing does not affect scanning — the app continues to collect battery data in the background.<br><br>" +
                      "<b>While snoozed:</b><br>" +
                      "• All scheduled notifications are suppressed<br>" +
                      "• Battery data continues to be collected normally<br>" +
                      "• The main page shows a banner with time remaining<br>" +
                      "• Manual Send Notification Now bypasses the snooze and sends immediately<br><br>" +
                      "Snooze expires automatically when the duration passes. You can also clear it early at any time from the main page.</div>"
        }

        section("") {
            paragraph rawHtml: true, "<div style='background-color:#e8f0fe; padding:6px 10px; border-radius:4px; font-weight:bold;'>⚙️ Features & Actions</div>"
        }

        section("<b>🔄 Force Scan & 🔁 Replacement Detection</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'><b>Force Scan Now</b> on the Summary and Trends pages immediately reads battery levels from all devices " +
                      "instead of waiting for the next scheduled scan.<br><br>" +
                      "<b>Force Scan does NOT instantly generate drain samples.</b> A sample requires the battery level to have " +
                      "changed since the last reading, or 24+ hours to have passed at the same level.<br><br>" +
                      "<b>Replacement detection</b> fires automatically when a device jumps from ≤40% up to ≥90–95%. " +
                      "If replaced before dropping to 40%, log it manually to keep trends accurate.</div>"
        }

        section("") {
            paragraph rawHtml: true, "<div style='background-color:#e8f0fe; padding:6px 10px; border-radius:4px; font-weight:bold;'>🔔 Alerts & Monitoring</div>"
        }

        section("<b>⚠️ High Drain Alerts & ⏱ Stale Devices</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'><b>High Drain alert</b> fires when a device crosses into 🔴 Poor territory — drain exceeds 1.5%/day.<br>" +
                      "🟠 Fair devices (0.8–1.5%/day) are worth monitoring but do not trigger an alert.<br><br>" +
                      "<b>Stale</b> means a device has not reported any activity within the configured threshold (default 24h). " +
                      "Stale is based on Last Activity — not Last Battery.</div>"
        }

        section("<b>🔔 Notification Schedule (2, 3, Weekly)</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "<b>2 / 3 (interval-based):</b> Notifications sent on a rolling interval as long as conditions persist.<br><br>" +
                      "<b>Weekly:</b> Notifications anchored to Monday. First notification sent the day after detection, then weekly on Mondays.<br><br>" +
                      "Notifications are not duplicated within the same interval window.</div>"
        }

        section("") {
            paragraph rawHtml: true, "<div style='background-color:#e8f0fe; padding:6px 10px; border-radius:4px; font-weight:bold;'>🛠 Help & Troubleshooting</div>"
        }

        section("<b>⚙️ Device Adjustments & Troubleshooting</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>Some device types have adjusted drain calculations:<br><br>" +
                      "• <b>Locks</b> — drain adjusted down by 60%, higher thresholds, requires 7 samples<br>" +
                      "• <b>Contact &amp; motion sensors</b> — drain adjusted down by 50%, standard 5-sample gate<br>" +
                      "• <b>Smoke &amp; CO detectors</b> — drain adjusted down by 50%, requires 7 samples, slow reporter gate<br>" +
                      "• Other types use the standard calculation (5 samples)<br><br>" +
                      "<b>Battery Catalog:</b><br>" +
                      "Select a battery type from the dropdown for each device. If your battery type is not listed, select <b>Other</b> — a free text field will appear so you can enter any custom type (e.g. CR17450, 4SR44, 28A). " +
                      "Custom types appear in notifications and replacement history just like standard types.<br><br>" +
                      "<b>If a device shows High Drain or Poor health after Pending:</b><br>" +
                      "• Check signal strength and mesh routing<br>" +
                      "• Verify reporting frequency in the device driver<br>" +
                      "• Confirm correct battery type in the Battery Catalog<br>" +
                      "• Consider environmental factors (temperature, distance)</div>"
        }

        section("<b>🔄 Reset Drain History</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'><b>Reset Drain History</b> is available at the bottom of the Battery Trends page. " +
                      "It clears accumulated drain samples and resets a device back to ⏳ Pending without logging a battery replacement.<br><br>" +
                      "<b>What it resets:</b> drain samples, drain rate, trend, health.<br>" +
                      "<b>What it does NOT change:</b> battery replacement history, last battery level, last seen date.</div>"
        }

        section("<b>💡 Tips for Best Results</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>• Let new batteries run for at least a week before trusting health ratings<br>" +
                      "• Use the Battery Catalog to log battery types — helps with replacement planning<br>" +
                      "• Set Scan Interval to Hourly to build health ratings faster<br>" +
                      "• After replacing a battery use Manual Replacement to reset history immediately<br>" +
                      "• Use Reset Drain History on locks and sensors if they show Heavy Drain after first install<br>" +
                      "• Use Notification Snooze when traveling so you are not notified about batteries you cannot replace<br>" +
                      "• After updating the app, switch to Hourly scan temporarily to rebuild sample data faster</div>"
        }
    }
}
