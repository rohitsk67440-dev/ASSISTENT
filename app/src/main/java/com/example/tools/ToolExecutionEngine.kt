package com.example.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import com.example.accessibility.ZoyaAccessibilityService
import com.example.data.ZoyaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class ToolExecutionEngine(private val context: Context) {

    val repository: ZoyaRepository by lazy { ZoyaRepository(context) }
    val fileManager: FileManager by lazy { FileManager(context) }
    val downloadHelper: DownloadHelper by lazy { DownloadHelper(context) }
    val apkHelper: ApkHelper by lazy { ApkHelper(context) }
    val appControlHelper: AppControlHelper by lazy { AppControlHelper(context) }
    val deviceHelper: DeviceHelper by lazy { DeviceHelper(context) }
    val productivityHelper: ProductivityHelper by lazy { ProductivityHelper(context, repository) }
    val multiStepAgent: MultiStepAgent by lazy { MultiStepAgent(context, fileManager, appControlHelper) }

    suspend fun execute(name: String, args: JsonObject): String = withContext(Dispatchers.IO) {
        try {
            when (name) {
                // ==================== EXISTING CORE TOOLS (PRESERVED) ====================
                "openApp", "app_launch" -> {
                    val appName = args["packageName"]?.jsonPrimitive?.contentOrNull
                        ?: args["appName"]?.jsonPrimitive?.contentOrNull
                        ?: return@withContext "FAILURE: Missing packageName or appName argument."
                    openAppGeneric(appName)
                }
                "searchAndCallContact" -> {
                    val contactName = args["contactName"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing contactName argument."
                    val useDialer = args["useDialer"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                    val simSlot = args["simSlot"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    callContact(contactName, useDialer, simSlot)
                }
                "sendWhatsAppMessage" -> {
                    val contactName = args["contactName"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing contactName argument."
                    val message = args["message"]?.jsonPrimitive?.contentOrNull ?: ""
                    sendWhatsApp(contactName, message)
                }
                "sendGmail" -> {
                    val recipient = args["recipientEmail"]?.jsonPrimitive?.contentOrNull ?: ""
                    val subject = args["subject"]?.jsonPrimitive?.contentOrNull ?: ""
                    val body = args["body"]?.jsonPrimitive?.contentOrNull ?: ""
                    sendEmail(recipient, subject, body)
                }
                "searchYouTube" -> {
                    val query = args["query"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing query argument."
                    searchYouTube(query)
                }
                "adjustVolume" -> {
                    val direction = args["direction"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing direction (up/down/mute/unmute/max)."
                    deviceHelper.adjustSystemVolume(direction)
                }
                "toggleTorch", "flashlight_control" -> {
                    val state = args["state"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing state (on/off)."
                    deviceHelper.toggleTorch(state)
                }
                "setBrightness", "brightness_control" -> {
                    val levelStr = args["level"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing level argument."
                    val level = levelStr.toIntOrNull() ?: 50
                    deviceHelper.setBrightness(level)
                }
                "setVolumePercent", "volume_control" -> {
                    val percentStr = args["percent"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing percent argument."
                    val percent = percentStr.toIntOrNull() ?: 50
                    deviceHelper.setVolumePercent(percent)
                }
                "openNotificationPanel", "notification_access" -> {
                    openNotificationPanel()
                }
                "openQuickSettings" -> {
                    val service = ZoyaAccessibilityService.instance
                    if (service != null && service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)) {
                        "SUCCESS: Opened quick settings panel."
                    } else "FAILURE: Accessibility service not connected or cannot perform action."
                }
                "clickTextOnScreen", "screen_tap" -> {
                    val text = args["text"]?.jsonPrimitive?.contentOrNull
                        ?: args["query"]?.jsonPrimitive?.contentOrNull
                        ?: return@withContext "FAILURE: Missing text or query argument."
                    val success = ZoyaAccessibilityService.clickTextOnScreen(text)
                    if (success) "SUCCESS: Clicked on '$text'." else "FAILURE: Could not locate clickable element matching '$text' on current screen."
                }
                "getSimCardInfo" -> {
                    getSimCardInfo()
                }
                "playMedia" -> {
                    val query = args["query"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing query argument."
                    playMedia(query)
                }

                // ==================== NEW FILE & CODE WORKSPACE TOOLS ====================
                "file_create", "createFile" -> {
                    val path = args["path"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing path."
                    val content = args["content"]?.jsonPrimitive?.contentOrNull ?: ""
                    fileManager.createFile(path, content)
                }
                "file_read", "readFile" -> {
                    val path = args["path"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing path."
                    val startLine = args["startLine"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
                    val endLine = args["endLine"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 500
                    fileManager.readFile(path, startLine, endLine)
                }
                "file_write", "writeFile" -> {
                    val path = args["path"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing path."
                    val content = args["content"]?.jsonPrimitive?.contentOrNull ?: ""
                    val append = args["append"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                    fileManager.writeFile(path, content, append)
                }
                "file_delete", "deleteFile" -> {
                    val path = args["path"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing path."
                    val permanent = args["permanent"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
                    fileManager.deleteFile(path, permanent)
                }
                "file_move", "moveFile" -> {
                    val src = args["sourcePath"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing sourcePath."
                    val dest = args["destPath"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing destPath."
                    fileManager.moveFile(src, dest)
                }
                "file_copy", "copyFile" -> {
                    val src = args["sourcePath"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing sourcePath."
                    val dest = args["destPath"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing destPath."
                    fileManager.copyFile(src, dest)
                }
                "file_rename", "renameFile" -> {
                    val path = args["path"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing path."
                    val newName = args["newName"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing newName."
                    fileManager.renameFile(path, newName)
                }
                "folder_create", "createFolder" -> {
                    val path = args["path"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing path."
                    fileManager.createFolder(path)
                }
                "folder_list", "listFolder" -> {
                    val path = args["path"]?.jsonPrimitive?.contentOrNull ?: ""
                    fileManager.listFolder(path)
                }
                "file_search", "searchFiles" -> {
                    val query = args["query"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing query."
                    val path = args["path"]?.jsonPrimitive?.contentOrNull ?: ""
                    fileManager.searchFiles(query, path)
                }
                "zip_create", "createZip" -> {
                    val sourcePath = args["sourcePath"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing sourcePath."
                    val zipName = args["zipName"]?.jsonPrimitive?.contentOrNull ?: "project.zip"
                    fileManager.createZip(sourcePath, zipName)
                }
                "zip_extract", "extractZip" -> {
                    val zipPath = args["zipPath"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing zipPath."
                    val destPath = args["destinationPath"]?.jsonPrimitive?.contentOrNull ?: ""
                    fileManager.extractZip(zipPath, destPath)
                }
                "open_file", "openFile" -> {
                    val path = args["path"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing path."
                    fileManager.openFile(path)
                }
                "share_file", "shareFile" -> {
                    val path = args["path"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing path."
                    fileManager.shareFile(path)
                }
                "scaffold_website", "create_website" -> {
                    val name = args["projectName"]?.jsonPrimitive?.contentOrNull ?: "my_website"
                    multiStepAgent.createWebsiteProject(name)
                }

                // ==================== NEW DOWNLOAD TOOLS ====================
                "download_file", "downloadFile" -> {
                    val url = args["url"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing url."
                    val fileName = args["fileName"]?.jsonPrimitive?.contentOrNull
                    downloadHelper.downloadUrl(url, fileName)
                }
                "list_downloads", "listDownloads" -> {
                    downloadHelper.listDownloads()
                }
                "open_downloaded_file" -> {
                    val fileName = args["fileName"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing fileName."
                    downloadHelper.openDownloadedFile(fileName)
                }
                "share_downloaded_file" -> {
                    val fileName = args["fileName"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing fileName."
                    downloadHelper.shareDownloadedFile(fileName)
                }

                // ==================== NEW APK & APP MANAGEMENT TOOLS ====================
                "apk_find", "findApk" -> {
                    apkHelper.findApkFiles()
                }
                "apk_info", "getApkInfo" -> {
                    val path = args["path"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing path."
                    apkHelper.getApkInfo(path)
                }
                "apk_install_flow", "installApk" -> {
                    val path = args["path"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing path."
                    apkHelper.startInstallFlow(path)
                }
                "app_uninstall_flow", "uninstallApp" -> {
                    val pkg = args["packageName"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing packageName."
                    apkHelper.startUninstallFlow(pkg)
                }
                "app_list", "listApps" -> {
                    val query = args["query"]?.jsonPrimitive?.contentOrNull ?: ""
                    appControlHelper.listInstalledApps(query)
                }
                "app_info" -> {
                    val appName = args["appName"]?.jsonPrimitive?.contentOrNull
                        ?: args["packageName"]?.jsonPrimitive?.contentOrNull
                        ?: return@withContext "FAILURE: Missing appName or packageName."
                    appControlHelper.openAppInfo(appName)
                }
                "app_settings_permissions" -> {
                    val appName = args["appName"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing appName."
                    appControlHelper.openAppPermissions(appName)
                }
                "app_settings_notifications" -> {
                    val appName = args["appName"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing appName."
                    appControlHelper.openAppNotificationSettings(appName)
                }
                "app_settings_storage" -> {
                    val appName = args["appName"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing appName."
                    appControlHelper.openAppStorageSettings(appName)
                }

                // ==================== NEW SCREEN CONTROL & VISION TOOLS ====================
                "screen_read", "readScreen" -> {
                    val inspection = ZoyaAccessibilityService.inspectScreen()
                        ?: return@withContext "FAILURE: Accessibility Service is not running or active window is inaccessible. Enable Zoya Automation in Settings."
                    val sb = StringBuilder("SUCCESS: Screen Analysis for '${inspection.foregroundPackage}':\n")
                    if (inspection.windowTitle.isNotBlank()) sb.append("Window: ${inspection.windowTitle}\n")
                    sb.append("Visible Texts:\n")
                    inspection.allTexts.take(25).forEach { sb.append("- $it\n") }
                    sb.append("Interactive Elements:\n")
                    inspection.interactiveElements.take(15).forEach {
                        sb.append("- [${it.className.substringAfterLast('.')}] text='${it.text}', desc='${it.description}', id='${it.viewId}'\n")
                    }
                    sb.toString()
                }
                "screen_long_press", "longPress" -> {
                    val query = args["query"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing query."
                    val screen = ZoyaAccessibilityService.inspectScreen()
                    val elem = screen?.interactiveElements?.firstOrNull {
                        it.text.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true)
                    }
                    if (elem != null) {
                        val success = ZoyaAccessibilityService.dispatchGestureLongPress(
                            elem.bounds.centerX().toFloat(),
                            elem.bounds.centerY().toFloat(),
                            1000
                        )
                        if (success) "SUCCESS: Long pressed on '$query'." else "FAILURE: Gesture dispatch failed."
                    } else {
                        "FAILURE: Element '$query' not found on screen."
                    }
                }
                "screen_scroll", "scroll" -> {
                    val direction = args["direction"]?.jsonPrimitive?.contentOrNull ?: "down"
                    val success = ZoyaAccessibilityService.scrollScreen(direction)
                    if (success) "SUCCESS: Scrolled $direction." else "FAILURE: Could not scroll screen."
                }
                "screen_swipe", "swipe" -> {
                    val direction = args["direction"]?.jsonPrimitive?.contentOrNull ?: "up"
                    val success = ZoyaAccessibilityService.scrollScreen(direction)
                    if (success) "SUCCESS: Swiped $direction." else "FAILURE: Could not perform swipe."
                }
                "screen_type", "typeText" -> {
                    val text = args["text"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing text."
                    val targetField = args["targetField"]?.jsonPrimitive?.contentOrNull
                    val clearFirst = args["clearFirst"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                    val success = ZoyaAccessibilityService.typeTextOnScreen(text, targetField, clearFirst)
                    if (success) "SUCCESS: Typed '$text'." else "FAILURE: No editable field found to type into."
                }
                "screen_back", "pressBack" -> {
                    val success = ZoyaAccessibilityService.pressGlobalBack()
                    if (success) "SUCCESS: Pressed Back." else "FAILURE: Accessibility service not available."
                }
                "screen_home", "pressHome" -> {
                    val success = ZoyaAccessibilityService.pressGlobalHome()
                    if (success) "SUCCESS: Pressed Home." else "FAILURE: Accessibility service not available."
                }
                "screen_recents", "pressRecents" -> {
                    val success = ZoyaAccessibilityService.pressGlobalRecents()
                    if (success) "SUCCESS: Opened Recents screen." else "FAILURE: Accessibility service not available."
                }

                // ==================== NEW DEVICE TOOLS ====================
                "device_info", "deviceDiagnostic" -> {
                    deviceHelper.getDeviceDiagnostic()
                }
                "media_control" -> {
                    val action = args["action"]?.jsonPrimitive?.contentOrNull ?: "toggle"
                    deviceHelper.controlMedia(action)
                }

                // ==================== NEW PRODUCTIVITY TOOLS ====================
                "alarm_create", "setAlarm" -> {
                    val hour = args["hour"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 8
                    val minute = args["minute"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    val message = args["message"]?.jsonPrimitive?.contentOrNull ?: "Zoya Alarm"
                    productivityHelper.setAlarm(hour, minute, message)
                }
                "timer_create", "setTimer" -> {
                    val seconds = args["seconds"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 60
                    val message = args["message"]?.jsonPrimitive?.contentOrNull ?: "Zoya Timer"
                    productivityHelper.setTimer(seconds, message)
                }
                "reminder_create", "createReminder" -> {
                    val title = args["title"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing title."
                    val desc = args["description"]?.jsonPrimitive?.contentOrNull ?: ""
                    val minutes = args["minutesFromNow"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 60
                    productivityHelper.createReminder(title, desc, minutes)
                }
                "notes_save", "saveNote" -> {
                    val title = args["title"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing title."
                    val content = args["content"]?.jsonPrimitive?.contentOrNull ?: ""
                    productivityHelper.saveNote(title, content)
                }
                "notes_list", "listNotes" -> {
                    productivityHelper.listNotes()
                }
                "notes_delete", "deleteNote" -> {
                    val noteId = args["noteId"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@withContext "FAILURE: Missing noteId."
                    productivityHelper.deleteNote(noteId)
                }

                // ==================== NEW STRUCTURED MEMORY TOOLS ====================
                "memory_save", "remember" -> {
                    val key = args["key"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing key."
                    val value = args["value"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing value."
                    val category = args["category"]?.jsonPrimitive?.contentOrNull ?: "general"
                    repository.saveMemory(key, value, category)
                    "SUCCESS: Remembered: '$key' = '$value' ($category)."
                }
                "memory_recall", "recall" -> {
                    val query = args["query"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing query."
                    val matches = repository.searchMemories(query)
                    if (matches.isEmpty()) {
                        "SUCCESS: No memory stored matching '$query'."
                    } else {
                        val sb = StringBuilder("SUCCESS: Found ${matches.size} stored memory entries:\n")
                        matches.forEach { m ->
                            sb.append("- ${m.key}: ${m.value} [${m.category}]\n")
                        }
                        sb.toString()
                    }
                }
                "memory_list" -> {
                    val matches = repository.searchMemories("")
                    if (matches.isEmpty()) {
                        "SUCCESS: Memory is currently empty."
                    } else {
                        val sb = StringBuilder("SUCCESS: Stored memories (${matches.size} items):\n")
                        matches.forEach { m ->
                            sb.append("- [#${m.id}] ${m.key}: ${m.value} (${m.category})\n")
                        }
                        sb.toString()
                    }
                }
                "memory_delete" -> {
                    val key = args["key"]?.jsonPrimitive?.contentOrNull
                    val id = args["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    if (id != null) {
                        repository.deleteMemoryById(id)
                        "SUCCESS: Deleted memory #$id."
                    } else if (!key.isNullOrBlank()) {
                        val count = repository.deleteMemoryByKey(key)
                        "SUCCESS: Deleted $count memory item(s) matching '$key'."
                    } else {
                        "FAILURE: Provide either 'id' or 'key' to delete."
                    }
                }
                "memory_clear" -> {
                    repository.clearAllMemories()
                    "SUCCESS: All user memories cleared."
                }

                // ==================== NEW AUTOMATION & ROUTINES ====================
                "routine_list" -> {
                    val routines = repository.getEnabledRoutines()
                    if (routines.isEmpty()) return@withContext "SUCCESS: No routines configured."
                    val sb = StringBuilder("SUCCESS: Configured Routines:\n")
                    routines.forEach { r ->
                        sb.append("- ${r.name} (Trigger: '${r.triggerPhrase}') - ${r.description}\n")
                    }
                    sb.toString()
                }
                "routine_execute" -> {
                    val name = args["name"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing routine name."
                    val routine = repository.getRoutine(name) ?: return@withContext "FAILURE: Routine '$name' not found."
                    try {
                        val jsonArray = Json.parseToJsonElement(routine.stepsJson).jsonArray
                        multiStepAgent.executeSequence(jsonArray) { tName, tArgs ->
                            execute(tName, tArgs)
                        }
                    } catch (e: Exception) {
                        "FAILURE: Failed parsing routine steps: ${e.message}"
                    }
                }
                "routine_create" -> {
                    val name = args["name"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing routine name."
                    val trigger = args["triggerPhrase"]?.jsonPrimitive?.contentOrNull ?: name
                    val stepsJson = args["stepsJson"]?.jsonPrimitive?.contentOrNull ?: "[]"
                    val desc = args["description"]?.jsonPrimitive?.contentOrNull ?: ""
                    repository.saveRoutine(name, trigger, stepsJson, desc)
                    "SUCCESS: Routine '$name' created successfully."
                }

                // ==================== NEW MULTI-STEP REASONING AGENT ====================
                "multi_step_search_and_select" -> {
                    val appName = args["appName"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing appName."
                    val searchQuery = args["searchQuery"]?.jsonPrimitive?.contentOrNull ?: return@withContext "FAILURE: Missing searchQuery."
                    val selectFirst = args["selectFirstResult"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
                    multiStepAgent.executeAppSearchAndSelect(appName, searchQuery, selectFirst)
                }

                else -> "FAILURE: Unknown tool '$name'."
            }
        } catch (e: Exception) {
            "FAILURE: Error executing tool '$name': ${e.message}"
        }
    }

    // ==================== PRESERVED HELPER IMPLEMENTATIONS ====================

    private fun openAppGeneric(appName: String): String {
        return appControlHelper.launchApp(appName)
    }

    private fun callContact(nameOrNumber: String, useDialer: Boolean = false, simSlot: Int? = null): String {
        if (nameOrNumber == "121" || nameOrNumber == "*121#") {
            return "ERROR: You tried to call 121 instead of using the contact name. DO NOT invent numbers. Use the contact name provided by the user."
        }

        val isNumber = nameOrNumber.count { it.isDigit() } >= 7 || nameOrNumber.matches(Regex("^[0-9+\\-*#]+$"))

        val number = if (isNumber) {
            nameOrNumber.replace(Regex("[^0-9+*#]"), "")
        } else {
            val matches = findContacts(nameOrNumber)
            if (matches.isEmpty()) return "Could not find a phone number for '$nameOrNumber'. Please ask the user for the correct name."
            matches.first().second
        }

        val action = if (useDialer) Intent.ACTION_DIAL else Intent.ACTION_CALL
        val callIntent = Intent(action).apply {
            data = Uri.parse("tel:$number")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (!useDialer && simSlot != null) {
            try {
                val slotIndex = simSlot - 1
                callIntent.putExtra("com.android.phone.force.slot", true)
                callIntent.putExtra("com.android.phone.extra.slot", slotIndex)
                callIntent.putExtra("simSlot", slotIndex)

                if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
                    val phoneAccounts = telecomManager.callCapablePhoneAccounts
                    if (slotIndex in 0 until phoneAccounts.size) {
                        callIntent.putExtra(android.telecom.TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccounts[slotIndex])
                    }
                }
            } catch (e: Exception) {}
        }

        return try {
            context.startActivity(callIntent)
            if (useDialer) "SUCCESS: Opened dialer for $nameOrNumber ($number)" else "SUCCESS: Calling $nameOrNumber ($number) via SIM $simSlot..."
        } catch (e: SecurityException) {
            "PERMISSION: Missing CALL_PHONE permission."
        }
    }

    private fun sendWhatsApp(nameOrNumber: String, message: String): String {
        if (nameOrNumber == "121" || nameOrNumber == "*121#") {
            return "ERROR: You tried to use 121 instead of the contact name. DO NOT invent numbers. Use the exact contact name provided by the user."
        }

        val isNumber = nameOrNumber.count { it.isDigit() } >= 7 || nameOrNumber.matches(Regex("^[0-9+\\-*#]+$"))

        val number = if (isNumber) {
            nameOrNumber
        } else {
            val matches = findContacts(nameOrNumber)
            if (matches.isEmpty()) return "Could not find a phone number for '$nameOrNumber'. Please ask the user for the correct name."
            matches.first().second
        }

        val cleanNumber = number.replace(Regex("[^0-9+]"), "")
        val url = "https://api.whatsapp.com/send?phone=$cleanNumber&text=${Uri.encode(message)}"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(url)
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            ZoyaAccessibilityService.shouldAutoClick = true
            context.startActivity(intent)
            "SUCCESS: Automatically sending the WhatsApp message to $nameOrNumber."
        } catch (e: Exception) {
            ZoyaAccessibilityService.shouldAutoClick = false
            "FAILURE: WhatsApp may not be installed."
        }
    }

    private fun sendEmail(recipient: String, subject: String, body: String): String {
        val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(emailIntent)
            "SUCCESS: Opened email client with draft."
        } catch (e: Exception) {
            "FAILURE: No email client found."
        }
    }

    private fun searchYouTube(query: String): String {
        val intent = Intent(Intent.ACTION_SEARCH).apply {
            setPackage("com.google.android.youtube")
            putExtra("query", query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "SUCCESS: Opened YouTube with search query: $query"
        } catch (e: Exception) {
            "FAILURE: YouTube app not found on device."
        }
    }

    private fun playMedia(query: String): String {
        return try {
            val intent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(android.app.SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "SUCCESS: Started playing media for query: $query"
        } catch (e: Exception) {
            "FAILURE: Failed to play media: ${e.message}"
        }
    }

    private fun openNotificationPanel(): String {
        return try {
            val service = ZoyaAccessibilityService.instance
            if (service != null) {
                val success = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                if (success) "SUCCESS: Opened notification panel." else "FAILURE: Accessibility service failed to open notification panel."
            } else {
                "FAILURE: Accessibility service not running. Enable Zoya Automation in Settings > Accessibility."
            }
        } catch (e: Exception) {
            "FAILURE: Error opening notification panel: ${e.message}"
        }
    }

    private fun getSimCardInfo(): String {
        if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return "Unable to determine SIM cards because READ_PHONE_STATE permission is lacking. Proceed assuming 1 SIM."
        }
        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
            val phoneAccounts = telecomManager.callCapablePhoneAccounts
            "The device has ${phoneAccounts.size} active calling SIM cards."
        } catch (e: Exception) {
            "Error determining SIM cards: ${e.message}. Proceed assuming 1 SIM."
        }
    }

    private fun findContacts(namePattern: String): List<Pair<String, String>> {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }

        try {
            val fallbackUri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val fbProjection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            context.contentResolver.query(fallbackUri, fbProjection, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                val exactMatches = mutableListOf<Pair<String, String>>()
                val startsWithMatches = mutableListOf<Pair<String, String>>()
                val containsMatches = mutableListOf<Pair<String, String>>()

                val cleanPattern = namePattern.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
                val searchWords = cleanPattern.split(" ").filter { it.isNotEmpty() }

                while (cursor.moveToNext()) {
                    val contactName = cursor.getString(nameIdx) ?: continue
                    val contactNum = cursor.getString(numIdx) ?: continue
                    val cleanContactName = contactName.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
                    if (cleanContactName.isEmpty()) continue

                    val contactNameNoSpace = cleanContactName.replace(" ", "")
                    val patternNoSpace = cleanPattern.replace(" ", "")

                    if (contactNameNoSpace == patternNoSpace || cleanContactName == cleanPattern) {
                        exactMatches.add(Pair(contactName, contactNum))
                    } else if (contactNameNoSpace.startsWith(patternNoSpace) || cleanContactName.startsWith(cleanPattern)) {
                        startsWithMatches.add(Pair(contactName, contactNum))
                    } else if (searchWords.isNotEmpty() && searchWords.all { cleanContactName.contains(it) }) {
                        containsMatches.add(Pair(contactName, contactNum))
                    }
                }

                if (exactMatches.isNotEmpty()) return exactMatches.distinctBy { it.second }
                if (startsWithMatches.isNotEmpty()) return startsWithMatches.distinctBy { it.second }
                if (containsMatches.isNotEmpty()) return containsMatches.distinctBy { it.second }
            }
        } catch (e: Exception) {
            android.util.Log.e("ZoyaTools", "Error finding contact", e)
        }
        return emptyList()
    }
}
