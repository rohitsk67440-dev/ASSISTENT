package com.example.tools

import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.view.KeyEvent

class DeviceHelper(private val context: Context) {

    fun getDeviceDiagnostic(): String {
        val battery = getBatteryInfo()
        val storage = getStorageInfo()
        val ram = getRamInfo()
        val network = getNetworkInfo()
        val os = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), Model: ${Build.MANUFACTURER} ${Build.MODEL}"

        return """SUCCESS: Device Status:
- Hardware: $os
- Battery: $battery
- Storage: $storage
- Memory (RAM): $ram
- Connectivity: $network"""
    }

    fun getBatteryInfo(): String {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val intent = context.registerReceiver(null, filter)
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

            val pct = if (level != -1 && scale != -1) (level * 100) / scale else -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

            "$pct% ${if (isCharging) "(Charging)" else "(Discharging)"}"
        } catch (e: Exception) {
            "Battery info unavailable"
        }
    }

    fun getStorageInfo(): String {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val bytesAvailable = stat.availableBytes
            val bytesTotal = stat.totalBytes
            val gbAvailable = bytesAvailable / (1024.0 * 1024.0 * 1024.0)
            val gbTotal = bytesTotal / (1024.0 * 1024.0 * 1024.0)
            String.format("%.1f GB free of %.1f GB", gbAvailable, gbTotal)
        } catch (e: Exception) {
            "Storage info unavailable"
        }
    }

    fun getRamInfo(): String {
        return try {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memInfo)
            val mbAvailable = memInfo.availMem / (1024 * 1024)
            val mbTotal = memInfo.totalMem / (1024 * 1024)
            "$mbAvailable MB available of $mbTotal MB"
        } catch (e: Exception) {
            "RAM info unavailable"
        }
    }

    fun getNetworkInfo(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork ?: return "Offline"
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return "Offline"

            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Connected (Wi-Fi)"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Connected (Cellular Data)"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Connected (Ethernet)"
                else -> "Connected (Other)"
            }
        } catch (e: Exception) {
            "Network info unavailable"
        }
    }

    fun toggleTorch(state: String): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return "FAILURE: No camera flashlight found."
            val isOn = state.lowercase() == "on" || state.lowercase() == "true"
            cameraManager.setTorchMode(cameraId, isOn)
            "SUCCESS: Torch turned ${if (isOn) "ON" else "OFF"}."
        } catch (e: Exception) {
            "FAILURE: Failed to toggle torch: ${e.message}"
        }
    }

    fun adjustSystemVolume(direction: String): String {
        val ctx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.createAttributionContext("zoya_audio") else context
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val streamType = AudioManager.STREAM_MUSIC
        return try {
            when (direction.lowercase()) {
                "up" -> {
                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    "SUCCESS: Volume increased"
                }
                "down" -> {
                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    "SUCCESS: Volume decreased"
                }
                "mute" -> {
                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    "SUCCESS: Volume muted"
                }
                "unmute" -> {
                    audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                    "SUCCESS: Volume unmuted"
                }
                "max" -> {
                    val maxVol = audioManager.getStreamMaxVolume(streamType)
                    audioManager.setStreamVolume(streamType, maxVol, AudioManager.FLAG_SHOW_UI)
                    "SUCCESS: Volume set to maximum"
                }
                else -> "FAILURE: Unknown volume direction. Use up, down, mute, or max."
            }
        } catch (e: Exception) {
            "FAILURE: Failed to adjust volume: ${e.message}"
        }
    }

    fun setVolumePercent(percent: Int): String {
        return try {
            val ctx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.createAttributionContext("zoya_audio") else context
            val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val streamType = AudioManager.STREAM_MUSIC
            val maxVol = audioManager.getStreamMaxVolume(streamType)
            val targetVol = (maxVol * percent.coerceIn(0, 100)) / 100
            audioManager.setStreamVolume(streamType, targetVol, AudioManager.FLAG_SHOW_UI)
            "SUCCESS: Volume set to $percent%"
        } catch (e: Exception) {
            "FAILURE: Failed to set volume: ${e.message}"
        }
    }

    fun setBrightness(level: Int): String {
        return try {
            if (!Settings.System.canWrite(context)) {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return "PERMISSION: Android requires 'Modify System Settings' permission to change screen brightness. I opened the settings screen. Please grant it and retry."
            }

            val brightness = (level.coerceIn(0, 100) * 255) / 100
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightness
            )
            "SUCCESS: Screen brightness set to $level%"
        } catch (e: Exception) {
            "FAILURE: Failed to set brightness: ${e.message}"
        }
    }

    fun controlMedia(action: String): String {
        return try {
            val ctx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.createAttributionContext("zoya_audio") else context
            val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val keyCode = when (action.lowercase()) {
                "play", "pause", "toggle" -> KeyEvent.KEYCODE_HEADSETHOOK
                "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
                else -> KeyEvent.KEYCODE_HEADSETHOOK
            }
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            "SUCCESS: Sent media control '$action'."
        } catch (e: Exception) {
            "FAILURE: Failed media control: ${e.message}"
        }
    }
}
