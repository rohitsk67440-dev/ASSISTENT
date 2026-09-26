package com.example.live

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.tools.ToolExecutionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

enum class ZoyaState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING
}

class LiveSessionManager(
    private val context: Context,
    val toolEngine: ToolExecutionEngine,
    private val onAudioOut: (ByteArray) -> Unit,
    private val onInterrupt: () -> Unit = {}
) {
    private val _zoyaState = MutableStateFlow(ZoyaState.IDLE)
    val zoyaState: StateFlow<ZoyaState> = _zoyaState.asStateFlow()

    private val _messages = MutableStateFlow<List<String>>(emptyList())
    val messages: StateFlow<List<String>> = _messages.asStateFlow()

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val json = Json { ignoreUnknownKeys = true }

    // Comprehensive Tool Definitions
    private val toolsJson = buildJsonObject {
        putJsonArray("functionDeclarations") {
            // Core App & Communications
            add(buildJsonObject {
                put("name", "openApp")
                put("description", "Open an application package or by name, like WhatsApp, YouTube, Camera, Calculator, Settings")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("packageName") {
                            put("type", "STRING")
                            put("description", "A generic name of the app to launch (e.g. 'WhatsApp', 'YouTube', 'Settings', 'Camera')")
                        }
                    }
                    putJsonArray("required") { add("packageName") }
                }
            })
            add(buildJsonObject {
                put("name", "searchAndCallContact")
                put("description", "Search for a contact name on the device and call them. Can optionally open dialer or use a specific SIM slot.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("contactName") {
                            put("type", "STRING")
                            put("description", "The EXACT name of the contact as spoken by the user.")
                        }
                        putJsonObject("useDialer") {
                            put("type", "BOOLEAN")
                            put("description", "Set to true if user wants to open dial pad / keyboard so they can see the number before calling")
                        }
                        putJsonObject("simSlot") {
                            put("type", "INTEGER")
                            put("description", "1 for SIM 1, 2 for SIM 2 if specified.")
                        }
                    }
                    putJsonArray("required") { add("contactName") }
                }
            })
            add(buildJsonObject {
                put("name", "sendWhatsAppMessage")
                put("description", "Send a WhatsApp message to a specific contact with text.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("contactName") { put("type", "STRING") }
                        putJsonObject("message") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("contactName"); add("message") }
                }
            })
            add(buildJsonObject {
                put("name", "sendGmail")
                put("description", "Draft or send an email.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("recipientEmail") { put("type", "STRING") }
                        putJsonObject("subject") { put("type", "STRING") }
                        putJsonObject("body") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("recipientEmail"); add("subject"); add("body") }
                }
            })
            add(buildJsonObject {
                put("name", "searchYouTube")
                put("description", "Search for a query on YouTube app.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("query") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("query") }
                }
            })

            // System & Controls
            add(buildJsonObject {
                put("name", "adjustVolume")
                put("description", "Adjust device volume (up, down, mute, unmute, max).")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("direction") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("direction") }
                }
            })
            add(buildJsonObject {
                put("name", "setVolumePercent")
                put("description", "Set the device volume to a specific percentage (0 to 100).")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("percent") { put("type", "INTEGER") }
                    }
                    putJsonArray("required") { add("percent") }
                }
            })
            add(buildJsonObject {
                put("name", "getSimCardInfo")
                put("description", "Check how many active SIM cards the device has.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })
            add(buildJsonObject {
                put("name", "toggleTorch")
                put("description", "Turn flashlight on or off.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("state") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("state") }
                }
            })
            add(buildJsonObject {
                put("name", "setBrightness")
                put("description", "Set screen brightness percentage (0-100).")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("level") { put("type", "INTEGER") }
                    }
                    putJsonArray("required") { add("level") }
                }
            })
            add(buildJsonObject {
                put("name", "playMedia")
                put("description", "Play media, music or video from another app.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("query") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("query") }
                }
            })
            add(buildJsonObject {
                put("name", "device_info")
                put("description", "Get full device diagnostic status: battery, storage, RAM, Android version, Wi-Fi/cellular.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })

            // Files & Coding
            add(buildJsonObject {
                put("name", "file_create")
                put("description", "Create a new file in workspace with code or content.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "STRING"); put("description", "File name or relative path e.g. index.html") }
                        putJsonObject("content") { put("type", "STRING"); put("description", "File content or source code") }
                    }
                    putJsonArray("required") { add("path"); add("content") }
                }
            })
            add(buildJsonObject {
                put("name", "file_read")
                put("description", "Read lines from a file in workspace.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("path") }
                }
            })
            add(buildJsonObject {
                put("name", "file_write")
                put("description", "Write or append code/content into a file.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "STRING") }
                        putJsonObject("content") { put("type", "STRING") }
                        putJsonObject("append") { put("type", "BOOLEAN") }
                    }
                    putJsonArray("required") { add("path"); add("content") }
                }
            })
            add(buildJsonObject {
                put("name", "file_delete")
                put("description", "Delete a file or folder.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("path") }
                }
            })
            add(buildJsonObject {
                put("name", "file_move")
                put("description", "Move or rename a file.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("sourcePath") { put("type", "STRING") }
                        putJsonObject("destPath") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("sourcePath"); add("destPath") }
                }
            })
            add(buildJsonObject {
                put("name", "folder_create")
                put("description", "Create a folder or directory hierarchy.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("path") }
                }
            })
            add(buildJsonObject {
                put("name", "folder_list")
                put("description", "List files and folders in the workspace.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "STRING") }
                    }
                }
            })
            add(buildJsonObject {
                put("name", "zip_create")
                put("description", "Compress a folder or file into a ZIP archive.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("sourcePath") { put("type", "STRING") }
                        putJsonObject("zipName") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("sourcePath") }
                }
            })
            add(buildJsonObject {
                put("name", "open_file")
                put("description", "Open a file or document in the appropriate Android viewer.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("path") }
                }
            })
            add(buildJsonObject {
                put("name", "share_file")
                put("description", "Open Android share sheet to send a file or ZIP to another app.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("path") }
                }
            })
            add(buildJsonObject {
                put("name", "scaffold_website")
                put("description", "Scaffold a complete website with index.html, style.css, script.js and ZIP package.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("projectName") { put("type", "STRING") }
                    }
                }
            })

            // Downloads
            add(buildJsonObject {
                put("name", "download_file")
                put("description", "Download a file from any HTTP/HTTPS URL.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("url") { put("type", "STRING") }
                        putJsonObject("fileName") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("url") }
                }
            })
            add(buildJsonObject {
                put("name", "list_downloads")
                put("description", "List downloaded files.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })
            add(buildJsonObject {
                put("name", "open_downloaded_file")
                put("description", "Open a downloaded file.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("fileName") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("fileName") }
                }
            })

            // APK & App Management
            add(buildJsonObject {
                put("name", "apk_find")
                put("description", "Search for APK files on device.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })
            add(buildJsonObject {
                put("name", "apk_info")
                put("description", "Inspect APK package metadata, version, and installation status.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("path") }
                }
            })
            add(buildJsonObject {
                put("name", "apk_install_flow")
                put("description", "Open official Android installation dialog for an APK.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("path") }
                }
            })
            add(buildJsonObject {
                put("name", "app_uninstall_flow")
                put("description", "Open official Android uninstall confirmation dialog.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("packageName") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("packageName") }
                }
            })
            add(buildJsonObject {
                put("name", "app_list")
                put("description", "List installed applications on this phone.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("query") { put("type", "STRING") }
                    }
                }
            })
            add(buildJsonObject {
                put("name", "app_info")
                put("description", "Open App Info screen in Settings for an app.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("appName") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("appName") }
                }
            })

            // Screen Control & Vision
            add(buildJsonObject {
                put("name", "screen_read")
                put("description", "Read visible text and interactive UI elements on the current screen.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })
            add(buildJsonObject {
                put("name", "clickTextOnScreen")
                put("description", "Click on any text, button, or semantic element visible on the screen.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("text") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("text") }
                }
            })
            add(buildJsonObject {
                put("name", "screen_scroll")
                put("description", "Scroll the screen (up, down, forward, backward).")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("direction") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("direction") }
                }
            })
            add(buildJsonObject {
                put("name", "screen_type")
                put("description", "Type text into a focused or targeted input field on the screen.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("text") { put("type", "STRING") }
                        putJsonObject("targetField") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("text") }
                }
            })
            add(buildJsonObject {
                put("name", "screen_back")
                put("description", "Press the system Back button.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })
            add(buildJsonObject {
                put("name", "openNotificationPanel")
                put("description", "Pull down the notification panel.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })
            add(buildJsonObject {
                put("name", "openQuickSettings")
                put("description", "Pull down quick settings.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })

            // Multi-step Execution
            add(buildJsonObject {
                put("name", "multi_step_search_and_select")
                put("description", "Autonomously open an app, find search, enter query, and inspect/select the first result.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("appName") { put("type", "STRING") }
                        putJsonObject("searchQuery") { put("type", "STRING") }
                        putJsonObject("selectFirstResult") { put("type", "BOOLEAN") }
                    }
                    putJsonArray("required") { add("appName"); add("searchQuery") }
                }
            })

            // Productivity & Notes
            add(buildJsonObject {
                put("name", "alarm_create")
                put("description", "Set an alarm for a specific hour and minute.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("hour") { put("type", "INTEGER") }
                        putJsonObject("minute") { put("type", "INTEGER") }
                        putJsonObject("message") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("hour"); add("minute") }
                }
            })
            add(buildJsonObject {
                put("name", "timer_create")
                put("description", "Set a timer for seconds.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("seconds") { put("type", "INTEGER") }
                        putJsonObject("message") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("seconds") }
                }
            })
            add(buildJsonObject {
                put("name", "reminder_create")
                put("description", "Create a reminder / calendar event.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("title") { put("type", "STRING") }
                        putJsonObject("description") { put("type", "STRING") }
                        putJsonObject("minutesFromNow") { put("type", "INTEGER") }
                    }
                    putJsonArray("required") { add("title") }
                }
            })
            add(buildJsonObject {
                put("name", "notes_save")
                put("description", "Save a note or idea to memory.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("title") { put("type", "STRING") }
                        putJsonObject("content") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("title"); add("content") }
                }
            })
            add(buildJsonObject {
                put("name", "notes_list")
                put("description", "List saved notes.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })

            // Memory & Routines
            add(buildJsonObject {
                put("name", "memory_save")
                put("description", "Save user preferences or facts into long-term structured memory.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("key") { put("type", "STRING") }
                        putJsonObject("value") { put("type", "STRING") }
                        putJsonObject("category") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("key"); add("value") }
                }
            })
            add(buildJsonObject {
                put("name", "memory_recall")
                put("description", "Search long-term memory for remembered facts.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("query") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("query") }
                }
            })
            add(buildJsonObject {
                put("name", "routine_list")
                put("description", "List available automation routines (Gaming Mode, Work Mode, etc).")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {}
                }
            })
            add(buildJsonObject {
                put("name", "routine_execute")
                put("description", "Execute an automation routine by name.")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("name") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("name") }
                }
            })
        }
    }

    fun startSession() {
        if (webSocket != null) return

        val prefs = context.getSharedPreferences("ZoyaPrefs", Context.MODE_PRIVATE)
        var apiKey = prefs.getString("api_key", "") ?: ""
        if (apiKey.isBlank() || apiKey == "YOUR_API_KEY") {
            apiKey = BuildConfig.GEMINI_API_KEY
        }

        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            Log.e("ZoyaDiagnostic", "No API Key found")
            addMessage("Error: Gemini API Key is missing. Please set it in Settings.")
            _zoyaState.value = ZoyaState.IDLE
            return
        }

        Log.i("ZoyaDiagnostic", "Connecting to Gemini Live API...")
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i("ZoyaDiagnostic", "WebSocket connection OPENED successfully.")
                addMessage("WebSocket Opened")
                isSetupComplete = false
                sendSetupMessage(webSocket)
                _zoyaState.value = ZoyaState.LISTENING
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("ZoyaDiagnostic", "WebSocket Text Msg Received (length: ${text.length})")
                handleServerMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                val text = bytes.utf8()
                Log.d("ZoyaDiagnostic", "WebSocket Binary Msg Received (utf8 length: ${text.length})")
                handleServerMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val errorBody = response?.body?.string() ?: "No body"
                Log.e("ZoyaDiagnostic", "WebSocket ERROR: ${t.message}, Response: $errorBody", t)
                addMessage("WebSocket Error: ${t.message}. Details: $errorBody")
                _zoyaState.value = ZoyaState.IDLE
                this@LiveSessionManager.webSocket = null
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i("ZoyaDiagnostic", "WebSocket CLOSED. Code: $code, Reason: $reason")
                addMessage("WebSocket Closed: $reason")
                _zoyaState.value = ZoyaState.IDLE
                this@LiveSessionManager.webSocket = null
            }
        })
    }

    private fun sendInitialPrompt(ws: WebSocket) {
        val msg = buildJsonObject {
            putJsonObject("clientContent") {
                putJsonArray("turns") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(buildJsonObject {
                                put("text", "Hi Zoya! Ready to assist.")
                            })
                        }
                    })
                }
                put("turnComplete", true)
            }
        }
        ws.send(msg.toString())
    }

    fun addMessage(msg: String) {
        _messages.value = _messages.value + msg
    }

    fun stopSession() {
        webSocket?.close(1000, "User stopped")
        webSocket = null
        _zoyaState.value = ZoyaState.IDLE
        addMessage("Session stopped.")
    }

    fun sendTextMessage(text: String) {
        if (webSocket == null || !isSetupComplete || _zoyaState.value == ZoyaState.IDLE) return
        addMessage("You: $text")
        val msg = buildJsonObject {
            putJsonObject("clientContent") {
                putJsonArray("turns") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", text) })
                        }
                    })
                }
                put("turnComplete", true)
            }
        }
        webSocket?.send(msg.toString())
    }

    fun sendAudioData(pcmData: ShortArray, length: Int) {
        if (webSocket == null || !isSetupComplete || _zoyaState.value == ZoyaState.IDLE) {
            return
        }

        val byteArray = ByteArray(length * 2)
        for (i in 0 until length) {
            val s = pcmData[i]
            byteArray[i * 2] = (s.toInt() and 0x00FF).toByte()
            byteArray[i * 2 + 1] = (s.toInt() shr 8).toByte()
        }

        val base64Data = Base64.encodeToString(byteArray, Base64.NO_WRAP)
        val inputMsg = buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonArray("mediaChunks") {
                    add(buildJsonObject {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", base64Data)
                    })
                }
            }
        }
        webSocket?.send(inputMsg.toString())
    }

    private fun sendSetupMessage(ws: WebSocket) {
        val setupMsg = buildJsonObject {
            putJsonObject("setup") {
                put("model", "models/gemini-2.5-flash-native-audio-preview-12-2025")
                putJsonObject("generationConfig") {
                    putJsonArray("responseModalities") { add("AUDIO") }
                    putJsonObject("speechConfig") {
                        putJsonObject("voiceConfig") {
                            putJsonObject("prebuiltVoiceConfig") {
                                put("voiceName", "Aoede")
                            }
                        }
                    }
                }
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        add(buildJsonObject {
                            put("text", """You are Zoya, a high-level autonomous AI agent on the user's Android device.
CRITICAL RULES:
1. DO NOT output any internal thinking or planning out loud. NEVER narrate your steps before doing them. JUST CALL THE TOOL IN SILENCE.
2. Keep spoken responses EXTREMELY short and helpful. Never repeat yourself.
3. If asked to do complex tasks:
- Coding / files: call file_create, file_write, scaffold_website, or zip_create.
- Downloading: call download_file or list_downloads.
- APKs: call apk_find, apk_info, apk_install_flow, or app_uninstall_flow.
- Screen interaction: call screen_read, clickTextOnScreen, screen_scroll, or screen_type.
- Multi-step: e.g. 'Open YouTube and search GTA 5' -> call multi_step_search_and_select.
- Memory: call memory_save, memory_recall, or routine_execute.
4. Calling contacts: Never guess numbers. Always pass exact contactName to searchAndCallContact.
5. All high-impact operations (install, delete, call) launch official Android system verification.""")
                        })
                    }
                }
                putJsonArray("tools") {
                    add(toolsJson)
                }
            }
        }
        ws.send(setupMsg.toString())
    }

    private var isSetupComplete = false

    private fun handleServerMessage(text: String) {
        Log.d("LiveSessionManager", "Server msg: $text")
        try {
            val jsonMsg = json.parseToJsonElement(text).jsonObject

            if (jsonMsg.containsKey("setupComplete")) {
                isSetupComplete = true
                addMessage("Server says: Setup Complete")
                sendInitialPrompt(webSocket!!)
            }
            if (jsonMsg.containsKey("serverContent")) {
                val serverContent = jsonMsg["serverContent"]?.jsonObject
                val modelTurn = serverContent?.get("modelTurn")?.jsonObject

                if (serverContent?.get("interrupted")?.jsonPrimitive?.content == "true" ||
                    serverContent?.get("interrupted")?.jsonPrimitive?.booleanOrNull == true) {
                    onInterrupt()
                }

                modelTurn?.get("parts")?.jsonArray?.forEach { partElement ->
                    val part = partElement.jsonObject

                    if (part.containsKey("inlineData")) {
                        val dataBase64 = part["inlineData"]?.jsonObject?.get("data")?.jsonPrimitive?.content
                        if (dataBase64 != null) {
                            _zoyaState.value = ZoyaState.SPEAKING
                            val rawBytes = Base64.decode(dataBase64, Base64.NO_WRAP)
                            onAudioOut(rawBytes)
                        }
                    }

                    if (part.containsKey("text")) {
                        val textContent = part["text"]?.jsonPrimitive?.content
                        if (!textContent.isNullOrBlank()) {
                            addMessage("Zoya: $textContent")
                        }
                    }
                }

                if (serverContent?.containsKey("turnComplete") == true &&
                    serverContent["turnComplete"]?.jsonPrimitive?.content == "true") {
                    _zoyaState.value = ZoyaState.LISTENING
                }
            }

            if (jsonMsg.containsKey("toolCall")) {
                val toolCallObj = jsonMsg["toolCall"]?.jsonObject
                val functionCalls = toolCallObj?.get("functionCalls")?.jsonArray

                functionCalls?.forEach { callElement ->
                    val callObj = callElement.jsonObject
                    val id = callObj["id"]?.jsonPrimitive?.content ?: ""
                    val name = callObj["name"]?.jsonPrimitive?.content ?: ""
                    val args = callObj["args"]?.jsonObject ?: buildJsonObject {}

                    executeToolAndRespond(id, name, args)
                }
            }
        } catch (e: Exception) {
            Log.e("LiveSessionManager", "Error parsing server message", e)
            addMessage("Parsing error: ${e.message}")
        }
    }

    private fun executeToolAndRespond(id: String, name: String, args: JsonObject) {
        _zoyaState.value = ZoyaState.THINKING
        addMessage("Action: $name")
        scope.launch {
            val resultStr = toolEngine.execute(name, args)
            addMessage("Result: $resultStr")

            val responseMsg = buildJsonObject {
                putJsonObject("toolResponse") {
                    putJsonArray("functionResponses") {
                        add(buildJsonObject {
                            put("id", id)
                            put("name", name)
                            putJsonObject("response") {
                                put("result", resultStr)
                            }
                        })
                    }
                }
            }
            webSocket?.send(responseMsg.toString())
        }
    }
}
