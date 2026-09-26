package com.example.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.BuildConfig
import com.example.ZoyaForegroundService
import com.example.accessibility.ZoyaAccessibilityService
import com.example.data.MemoryEntity
import com.example.data.RoutineEntity
import com.example.data.ZoyaRepository
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import com.example.live.ZoyaState
import com.example.tools.FileManager
import com.example.tools.ToolExecutionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ScreenTab(val title: String, val icon: String) {
    ASSISTANT("Agent", "✦"),
    WORKSPACE("Files", "📁"),
    MEMORY("Memory", "🧠"),
    CONSOLE("Console", "⚡")
}

@Composable
fun ZoyaScreen() {
    var selectedTab by remember { mutableStateOf(ScreenTab.ASSISTANT) }

    BackHandler(enabled = selectedTab != ScreenTab.ASSISTANT) {
        selectedTab = ScreenTab.ASSISTANT
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF14172B), Color(0xFF090A10)),
                    radius = 1600f
                )
            )
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                ZoyaBottomNavBar(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it }
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                when (selectedTab) {
                    ScreenTab.ASSISTANT -> AssistantTabScreen()
                    ScreenTab.WORKSPACE -> WorkspaceTabScreen()
                    ScreenTab.MEMORY -> MemoryTabScreen()
                    ScreenTab.CONSOLE -> ConsoleTabScreen()
                }
            }
        }
    }
}

@Composable
fun ZoyaBottomNavBar(
    selectedTab: ScreenTab,
    onTabSelected: (ScreenTab) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        color = Color(0xFF121422).copy(alpha = 0.92f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ScreenTab.values().forEach { tab ->
                val isSelected = selectedTab == tab
                val bgBrush = if (isSelected) {
                    Brush.horizontalGradient(listOf(Color(0xFF00B0FF).copy(alpha = 0.25f), Color(0xFF651FFF).copy(alpha = 0.25f)))
                } else {
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                }

                Box(
                    modifier = Modifier
                        .testTag("nav_tab_${tab.name.lowercase()}")
                        .clip(RoundedCornerShape(16.dp))
                        .background(bgBrush)
                        .clickable { onTabSelected(tab) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = tab.icon,
                            fontSize = 16.sp,
                            color = if (isSelected) Color(0xFF80D8FF) else Color.White.copy(alpha = 0.5f)
                        )
                        if (isSelected) {
                            Text(
                                text = tab.title,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 1. ASSISTANT TAB SCREEN (ORB & INTERACTION)
// ==========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantTabScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("ZoyaPrefs", Context.MODE_PRIVATE) }
    var apiKey by remember {
        val stored = prefs.getString("api_key", "") ?: ""
        mutableStateOf(if (stored.isNotBlank() && stored != "YOUR_API_KEY") stored else BuildConfig.GEMINI_API_KEY)
    }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var zoyaState by remember { mutableStateOf(ZoyaForegroundService.currentState) }
    var serviceStarted by remember { mutableStateOf(ZoyaForegroundService.activeService != null) }
    var showMenu by remember { mutableStateOf(false) }

    var textInput by remember { mutableStateOf("") }
    var statusFeedback by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[android.Manifest.permission.RECORD_AUDIO] == true) {
            val intent = Intent(context, ZoyaForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
            serviceStarted = true
        } else {
            Toast.makeText(context, "Microphone permission is required!", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        ZoyaForegroundService.onStateChange = { state ->
            zoyaState = state
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Z.O.Y.A.",
                    color = Color.White,
                    fontWeight = FontWeight.Light,
                    fontSize = 24.sp,
                    letterSpacing = 3.sp
                )
                Text(
                    text = "Autonomous Android Agent",
                    color = Color(0xFF00E5FF).copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    letterSpacing = 1.sp
                )
            }

            Box {
                IconButton(
                    onClick = { showMenu = !showMenu },
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                ) {
                    Text("⚙", color = Color.White, fontSize = 20.sp)
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier
                        .background(Color(0xFF1E1E2E).copy(alpha = 0.95f))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                ) {
                    DropdownMenuItem(
                        text = { Text("API Key Settings", color = Color.White) },
                        onClick = {
                            showMenu = false
                            showApiKeyDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Accessibility (Auto-Click)", color = Color.White) },
                        onClick = {
                            showMenu = false
                            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Write Settings (Brightness)", color = Color.White) },
                        onClick = {
                            showMenu = false
                            val intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                                data = Uri.parse("package:${context.packageName}")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Center Orb Pod
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(32.dp))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(32.dp))
                .padding(vertical = 24.dp, horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ZoyaOrb(state = zoyaState)

                Spacer(modifier = Modifier.height(16.dp))

                // State badge
                Text(
                    text = when (zoyaState) {
                        ZoyaState.IDLE -> if (serviceStarted) "Standing By (Connected)" else "Session Inactive"
                        ZoyaState.LISTENING -> "● Listening to Voice"
                        ZoyaState.THINKING -> "✦ Reasoning & Calling Tools"
                        ZoyaState.SPEAKING -> "▶ Transmitting Speech"
                    },
                    color = when (zoyaState) {
                        ZoyaState.IDLE -> Color.White.copy(alpha = 0.7f)
                        ZoyaState.LISTENING -> Color(0xFFB388FF)
                        ZoyaState.THINKING -> Color(0xFFFFD180)
                        ZoyaState.SPEAKING -> Color(0xFF69F0AE)
                    },
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Action Controls
                if (!serviceStarted) {
                    Button(
                        modifier = Modifier.testTag("start_zoya_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00B0FF).copy(alpha = 0.25f),
                            contentColor = Color(0xFF80D8FF)
                        ),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, Color(0xFF00B0FF).copy(alpha = 0.4f)),
                        onClick = {
                            val hasMic = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            val hasContacts = ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            val hasPhone = ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED

                            if (hasMic && hasContacts && hasPhone) {
                                val intent = Intent(context, ZoyaForegroundService::class.java)
                                ContextCompat.startForegroundService(context, intent)
                                serviceStarted = true
                            } else {
                                permissionLauncher.launch(
                                    arrayOf(
                                        android.Manifest.permission.RECORD_AUDIO,
                                        android.Manifest.permission.READ_CONTACTS,
                                        android.Manifest.permission.CALL_PHONE
                                    )
                                )
                            }
                        }
                    ) {
                        Text("Initialize Z.O.Y.A. Agent", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White.copy(alpha = 0.1f),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                            onClick = {
                                val service = ZoyaForegroundService.activeService
                                service?.reconnectSession()
                            }
                        ) {
                            Text("Reconnect")
                        }

                        Button(
                            onClick = {
                                val intent = Intent(context, ZoyaForegroundService::class.java)
                                context.stopService(intent)
                                serviceStarted = false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFE53935).copy(alpha = 0.2f),
                                contentColor = Color(0xFFEF9A9A)
                            ),
                            border = BorderStroke(1.dp, Color(0xFFE53935).copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Text("Disconnect")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Quick Capabilities Chips
        Text(
            text = "AGENT CAPABILITIES & WORKFLOWS",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier
                .align(Alignment.Start)
                .padding(vertical = 4.dp)
        )

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val quickChips = listOf(
                "Scaffold Website" to {
                    coroutineScope.launch {
                        statusFeedback = "Scaffolding website project..."
                        val res = ToolExecutionEngine(context).multiStepAgent.createWebsiteProject("my_cool_site")
                        statusFeedback = res
                    }
                },
                "Device Diagnostic" to {
                    coroutineScope.launch {
                        statusFeedback = "Running diagnostics..."
                        val res = ToolExecutionEngine(context).deviceHelper.getDeviceDiagnostic()
                        statusFeedback = res
                    }
                },
                "Inspect Screen" to {
                    coroutineScope.launch {
                        val res = ToolExecutionEngine(context).execute("screen_read", kotlinx.serialization.json.JsonObject(emptyMap()))
                        statusFeedback = res
                    }
                },
                "Find APKs" to {
                    coroutineScope.launch {
                        val res = ToolExecutionEngine(context).apkHelper.findApkFiles()
                        statusFeedback = res
                    }
                },
                "Toggle Flashlight" to {
                    coroutineScope.launch {
                        val res = ToolExecutionEngine(context).execute("toggleTorch", kotlinx.serialization.json.buildJsonObject {
                            put("state", "on")
                        })
                        statusFeedback = res
                    }
                }
            )

            items(quickChips) { (title, action) ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                    modifier = Modifier.clickable { action() }
                ) {
                    Text(
                        text = title,
                        color = Color(0xFF80D8FF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Direct Text Command Console
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF161928),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "COMMAND DISPATCHER",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        placeholder = { Text("Command Zoya or ask a question...", color = Color.Gray, fontSize = 14.sp) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("agent_input_field"),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        singleLine = true
                    )
                    IconButton(
                        modifier = Modifier.testTag("agent_send_button"),
                        onClick = {
                            if (textInput.isNotBlank()) {
                                val cmd = textInput
                                textInput = ""
                                val service = ZoyaForegroundService.activeService
                                if (service != null && service.liveSessionManager.zoyaState.value != ZoyaState.IDLE) {
                                    service.sendTextMessage(cmd)
                                    statusFeedback = "Dispatched '$cmd' to live agent."
                                } else {
                                    coroutineScope.launch {
                                        statusFeedback = "Executing: $cmd"
                                        val engine = ToolExecutionEngine(context)
                                        val lower = cmd.lowercase()
                                        val result = when {
                                            lower.contains("website") -> engine.multiStepAgent.createWebsiteProject()
                                            lower.contains("device") || lower.contains("battery") -> engine.deviceHelper.getDeviceDiagnostic()
                                            lower.contains("screen") -> engine.execute("screen_read", kotlinx.serialization.json.JsonObject(emptyMap()))
                                            lower.contains("apk") -> engine.apkHelper.findApkFiles()
                                            lower.contains("routine") -> engine.execute("routine_list", kotlinx.serialization.json.JsonObject(emptyMap()))
                                            else -> "Dispatched command. Ensure Zoya service is active for direct conversational voice."
                                        }
                                        statusFeedback = result
                                    }
                                }
                            }
                        }
                    ) {
                        Text("➔", color = Color(0xFF00B0FF), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (statusFeedback != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF0B1B2B),
                border = BorderStroke(1.dp, Color(0xFF00B0FF).copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = statusFeedback ?: "",
                        color = Color(0xFFE0F7FA),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { statusFeedback = null },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Text("✕", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    if (showApiKeyDialog) {
        var tempKey by remember { mutableStateOf(apiKey) }
        AlertDialog(
            onDismissRequest = { showApiKeyDialog = false },
            title = { Text("Gemini API Key") },
            text = {
                Column {
                    Text("Enter your Gemini API key for real-time Live sessions.")
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = tempKey,
                        onValueChange = { tempKey = it },
                        placeholder = { Text("AIza...") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = {
                            if (tempKey.isNotEmpty()) {
                                IconButton(onClick = { tempKey = "" }) {
                                    Icon(Icons.Filled.Clear, contentDescription = "Clear")
                                }
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Get key from Google AI Studio",
                        color = Color(0xFF00B0FF),
                        modifier = Modifier.clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/app/apikey"))
                            context.startActivity(intent)
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        prefs.edit().putString("api_key", tempKey).apply()
                        apiKey = tempKey
                        showApiKeyDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showApiKeyDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// ==========================================
// 2. WORKSPACE & FILES TAB SCREEN
// ==========================================

@Composable
fun WorkspaceTabScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val fileManager = remember { FileManager(context) }
    var refreshTrigger by remember { mutableStateOf(0) }
    var currentSubdir by remember { mutableStateOf("") }
    var fileViewDialogContent by remember { mutableStateOf<Pair<String, String>?>(null) }

    val files = remember(refreshTrigger, currentSubdir) {
        val target = if (currentSubdir.isBlank()) fileManager.workspaceRoot else File(fileManager.workspaceRoot, currentSubdir)
        target.listFiles()?.toList() ?: emptyList()
    }

    val downloads = remember(refreshTrigger) {
        fileManager.downloadsDir.listFiles()?.toList() ?: emptyList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("WORKSPACE & FILES", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "Path: workspace/${currentSubdir}",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (currentSubdir.isNotBlank()) {
                    IconButton(onClick = { currentSubdir = "" }) {
                        Text("⬆", color = Color(0xFF80D8FF), fontSize = 18.sp)
                    }
                }
                IconButton(onClick = { refreshTrigger++ }) {
                    Text("↻", color = Color.White, fontSize = 18.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Quick scaffold bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = Color.White.copy(alpha = 0.05f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Generate Web Project", color = Color.White, fontSize = 13.sp)
                Button(
                    onClick = {
                        coroutineScope.launch {
                            ToolExecutionEngine(context).multiStepAgent.createWebsiteProject("web_app_${System.currentTimeMillis() % 1000}")
                            refreshTrigger++
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B0FF).copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Scaffold & ZIP", color = Color(0xFF80D8FF), fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text("WORKSPACE FILES (${files.size})", color = Color(0xFF00E5FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            if (files.isEmpty()) {
                item {
                    Text("No files in this workspace directory. Ask Zoya to create one!", color = Color.Gray, fontSize = 13.sp)
                }
            } else {
                items(files) { file ->
                    FileItemRow(
                        file = file,
                        onOpen = {
                            if (file.isDirectory) {
                                currentSubdir = file.name
                            } else {
                                val content = fileManager.readFile(file.absolutePath, 1, 100)
                                fileViewDialogContent = Pair(file.name, content)
                            }
                        },
                        onShare = { fileManager.shareFile(file.absolutePath) },
                        onDelete = {
                            fileManager.deleteFile(file.absolutePath)
                            refreshTrigger++
                        }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text("DOWNLOADS (${downloads.size})", color = Color(0xFF69F0AE), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            if (downloads.isEmpty()) {
                item {
                    Text("No downloaded files found.", color = Color.Gray, fontSize = 13.sp)
                }
            } else {
                items(downloads) { file ->
                    FileItemRow(
                        file = file,
                        onOpen = { fileManager.openFile(file.absolutePath) },
                        onShare = { fileManager.shareFile(file.absolutePath) },
                        onDelete = {
                            fileManager.deleteFile(file.absolutePath)
                            refreshTrigger++
                        }
                    )
                }
            }
        }
    }

    if (fileViewDialogContent != null) {
        val (title, content) = fileViewDialogContent!!
        AlertDialog(
            onDismissRequest = { fileViewDialogContent = null },
            title = { Text(title, fontFamily = FontFamily.Monospace, fontSize = 16.sp) },
            text = {
                Box(modifier = Modifier.heightIn(max = 400.dp)) {
                    Text(
                        text = content,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color.LightGray,
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { fileViewDialogContent = null }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun FileItemRow(
    file: File,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() },
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF161828),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (file.isDirectory) "📁" else if (file.name.endsWith(".zip")) "📦" else if (file.name.endsWith(".apk")) "🤖" else "📄",
                fontSize = 20.sp,
                modifier = Modifier.padding(end = 12.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (file.isDirectory) "Folder" else "${file.length() / 1024} KB",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            }
            IconButton(onClick = onShare, modifier = Modifier.size(32.dp)) {
                Text("↗", color = Color(0xFF80D8FF), fontSize = 16.sp)
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Text("✕", color = Color(0xFFEF9A9A), fontSize = 14.sp)
            }
        }
    }
}

// ==========================================
// 3. MEMORY & ROUTINES TAB SCREEN
// ==========================================

@Composable
fun MemoryTabScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val repository = remember { ZoyaRepository(context) }

    val memories by repository.allMemories.collectAsState(initial = emptyList())
    val routines by repository.allRoutines.collectAsState(initial = emptyList())

    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }
    var routineStatus by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("STRUCTURED MEMORY & ROUTINES", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Persistent preferences, facts, and automated workflows", color = Color.Gray, fontSize = 12.sp)

        Spacer(modifier = Modifier.height(16.dp))

        // Quick Routine Actions
        Text("AUTOMATION ROUTINES", color = Color(0xFFFFD180), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(routines) { routine ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF2A2218),
                    border = BorderStroke(1.dp, Color(0xFFFF9100).copy(alpha = 0.3f)),
                    modifier = Modifier.clickable {
                        coroutineScope.launch {
                            routineStatus = "Executing '${routine.name}'..."
                            val res = ToolExecutionEngine(context).execute("routine_execute", kotlinx.serialization.json.buildJsonObject {
                                put("name", routine.name)
                            })
                            routineStatus = res
                        }
                    }
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(routine.name, color = Color(0xFFFFD180), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(routine.triggerPhrase, color = Color.LightGray, fontSize = 11.sp)
                    }
                }
            }
        }

        if (routineStatus != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = routineStatus ?: "",
                color = Color(0xFFFFE082),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Add Memory Entry
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White.copy(alpha = 0.05f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("STORE NEW FACT", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        value = newKey,
                        onValueChange = { newKey = it },
                        placeholder = { Text("Key (e.g. favorite_food)", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        )
                    )
                    TextField(
                        value = newValue,
                        onValueChange = { newValue = it },
                        placeholder = { Text("Value (e.g. Pizza)", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        )
                    )
                    Button(
                        onClick = {
                            if (newKey.isNotBlank() && newValue.isNotBlank()) {
                                coroutineScope.launch {
                                    repository.saveMemory(newKey, newValue, "preference")
                                    newKey = ""
                                    newValue = ""
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B0FF).copy(alpha = 0.4f))
                    ) {
                        Text("+")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("STORED MEMORIES (${memories.size})", color = Color(0xFFB388FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            if (memories.isNotEmpty()) {
                TextButton(onClick = { coroutineScope.launch { repository.clearAllMemories() } }) {
                    Text("Clear All", color = Color(0xFFEF9A9A), fontSize = 11.sp)
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (memories.isEmpty()) {
                item {
                    Text("No memories stored yet. Tell Zoya 'Remember my favorite song is ...'", color = Color.Gray, fontSize = 13.sp)
                }
            } else {
                items(memories) { mem ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF19182C),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(mem.key, color = Color(0xFFB388FF), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(mem.value, color = Color.White, fontSize = 13.sp)
                            }
                            IconButton(
                                onClick = { coroutineScope.launch { repository.deleteMemoryById(mem.id) } },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Text("✕", color = Color.Gray, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 4. CONSOLE & DIAGNOSTICS TAB SCREEN
// ==========================================

@Composable
fun ConsoleTabScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val liveSessionManager = ZoyaForegroundService.activeService?.liveSessionManager
    val messages = liveSessionManager?.messages?.collectAsState(initial = emptyList())?.value ?: emptyList()

    var isAccessibilityActive by remember { mutableStateOf(ZoyaAccessibilityService.isServiceRunning()) }
    var diagnosticText by remember { mutableStateOf("Tap Refresh to load device telemetry.") }

    fun refreshTelemetry() {
        val diag = ToolExecutionEngine(context).deviceHelper.getDeviceDiagnostic()
        diagnosticText = diag
        isAccessibilityActive = ZoyaAccessibilityService.isServiceRunning()
    }

    LaunchedEffect(Unit) {
        refreshTelemetry()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("AGENT CONSOLE & TELEMETRY", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Live logs, accessibility status & hardware metrics", color = Color.Gray, fontSize = 12.sp)
            }
            IconButton(onClick = { refreshTelemetry() }) {
                Text("↻", color = Color(0xFF80D8FF), fontSize = 18.sp)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Telemetry Card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF0F1524),
            border = BorderStroke(1.dp, Color(0xFF00B0FF).copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("SYSTEM TELEMETRY", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isAccessibilityActive) Color(0xFF1B5E20) else Color(0xFFB71C1C)
                    ) {
                        Text(
                            text = if (isAccessibilityActive) "Accessibility: ACTIVE" else "Accessibility: DISABLED",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable {
                                    val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = diagnosticText,
                    color = Color.White.copy(alpha = 0.9f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text("ACTIVITY LOGS (${messages.size})", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF0A0C14),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            modifier = Modifier.weight(1f)
        ) {
            if (messages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No activity logged yet. Session events will stream here.", color = Color.Gray, fontSize = 12.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(messages) { msg ->
                        val isUser = msg.startsWith("You:")
                        val isAction = msg.startsWith("Action:")
                        val isResult = msg.startsWith("Result:")
                        val isError = msg.contains("Error", ignoreCase = true) || msg.contains("FAILURE")

                        val textColor = when {
                            isUser -> Color(0xFF80D8FF)
                            isAction -> Color(0xFFFFD180)
                            isResult -> Color(0xFF69F0AE)
                            isError -> Color(0xFFEF9A9A)
                            else -> Color.White.copy(alpha = 0.85f)
                        }

                        Text(
                            text = msg,
                            color = textColor,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// 5. 3D NEON ORBITAL ORB COMPOSABLE
// ==========================================

@Composable
fun ZoyaOrb(state: ZoyaState) {
    val radiusScale = remember { Animatable(1f) }
    val glowAlpha = remember { Animatable(0.5f) }

    val ring1Angle = remember { Animatable(0f) }
    val ring2Angle = remember { Animatable(120f) }
    val ring3Angle = remember { Animatable(240f) }
    val ring4Angle = remember { Animatable(45f) }

    LaunchedEffect(state) {
        when (state) {
            ZoyaState.IDLE -> {
                radiusScale.animateTo(1f, animationSpec = tween(1000))
                glowAlpha.animateTo(
                    targetValue = 0.4f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2500, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                )
            }
            ZoyaState.LISTENING -> {
                radiusScale.animateTo(1.1f, animationSpec = tween(500))
                glowAlpha.animateTo(
                    targetValue = 0.8f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                )
            }
            ZoyaState.THINKING -> {
                radiusScale.animateTo(1.05f, animationSpec = tween(400))
                glowAlpha.animateTo(
                    targetValue = 0.6f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1200, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                )
            }
            ZoyaState.SPEAKING -> {
                radiusScale.animateTo(1.2f, animationSpec = tween(200))
                glowAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(300, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        launch {
            ring1Angle.animateTo(
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(6000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                )
            )
        }
        launch {
            ring2Angle.animateTo(
                targetValue = 360f + 120f,
                animationSpec = infiniteRepeatable(
                    animation = tween(7000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                )
            )
        }
        launch {
            ring3Angle.animateTo(
                targetValue = 360f + 240f,
                animationSpec = infiniteRepeatable(
                    animation = tween(5500, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                )
            )
        }
        launch {
            ring4Angle.animateTo(
                targetValue = -360f + 45f,
                animationSpec = infiniteRepeatable(
                    animation = tween(8000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                )
            )
        }
    }

    Box(
        modifier = Modifier.size(240.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val baseRadius = size.minDimension / 4f
            val currentRadius = baseRadius * radiusScale.value

            val coreInnerColor = when (state) {
                ZoyaState.IDLE -> Color(0xFF80D8FF)
                ZoyaState.LISTENING -> Color(0xFFB388FF)
                ZoyaState.THINKING -> Color(0xFFFFD180)
                ZoyaState.SPEAKING -> Color(0xFF69F0AE)
            }

            val coreOuterColor = when (state) {
                ZoyaState.IDLE -> Color(0xFF00B0FF)
                ZoyaState.LISTENING -> Color(0xFF651FFF)
                ZoyaState.THINKING -> Color(0xFFFF9100)
                ZoyaState.SPEAKING -> Color(0xFF00E676)
            }

            // Ambient background glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(coreOuterColor.copy(alpha = glowAlpha.value * 0.5f), Color.Transparent),
                    center = center,
                    radius = currentRadius * 2.5f
                ),
                radius = currentRadius * 2.5f
            )

            // Glass sphere (Core)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.9f),
                        coreInnerColor.copy(alpha = 0.8f),
                        coreOuterColor.copy(alpha = 0.9f),
                        Color.Black.copy(alpha = 0.5f)
                    ),
                    center = androidx.compose.ui.geometry.Offset(center.x - currentRadius * 0.3f, center.y - currentRadius * 0.3f),
                    radius = currentRadius * 1.2f
                ),
                radius = currentRadius
            )

            // Inner core highlight for 3D effect
            drawCircle(
                color = Color.White.copy(alpha = 0.4f),
                center = androidx.compose.ui.geometry.Offset(center.x - currentRadius * 0.4f, center.y - currentRadius * 0.4f),
                radius = currentRadius * 0.3f
            )

            // Neon Orbital Rings
            val ringRadiusX = currentRadius * 1.8f
            val ringRadiusY = currentRadius * 0.6f

            fun drawNeonRing(angle: Float, startColor: Color, endColor: Color, strokeWidth: Float) {
                rotate(angle, center) {
                    drawOval(
                        brush = Brush.sweepGradient(
                            colors = listOf(startColor, endColor, startColor, Color.Transparent, startColor),
                            center = center
                        ),
                        topLeft = androidx.compose.ui.geometry.Offset(center.x - ringRadiusX, center.y - ringRadiusY),
                        size = androidx.compose.ui.geometry.Size(ringRadiusX * 2, ringRadiusY * 2),
                        style = Stroke(width = strokeWidth)
                    )
                    drawOval(
                        color = startColor.copy(alpha = 0.3f),
                        topLeft = androidx.compose.ui.geometry.Offset(center.x - ringRadiusX, center.y - ringRadiusY),
                        size = androidx.compose.ui.geometry.Size(ringRadiusX * 2, ringRadiusY * 2),
                        style = Stroke(width = strokeWidth * 3)
                    )
                }
            }

            val speedMultiplier = if (state == ZoyaState.THINKING || state == ZoyaState.SPEAKING) 2f else 1f
            drawNeonRing(ring1Angle.value * speedMultiplier, Color(0xFFFF1744), Color(0xFFD50000), 4f)
            drawNeonRing(ring2Angle.value * speedMultiplier, Color(0xFF00E676), Color(0xFF76FF03), 4f)
            drawNeonRing(ring3Angle.value * speedMultiplier, Color(0xFF00E5FF), Color(0xFF2979FF), 4f)
            drawNeonRing(ring4Angle.value * speedMultiplier, Color.White.copy(alpha = 0.5f), Color.White.copy(alpha = 0.1f), 2f)

            // Outer glass dome reflection
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.15f), Color.White.copy(alpha = 0.3f)),
                    center = center,
                    radius = currentRadius * 2.2f
                ),
                radius = currentRadius * 2.2f,
                style = Stroke(width = 2f)
            )
        }
    }
}
