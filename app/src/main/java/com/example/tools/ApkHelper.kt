package com.example.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

class ApkHelper(private val context: Context) {

    fun findApkFiles(): String {
        val searchDirs = listOf(
            File(context.filesDir, "downloads"),
            File(context.filesDir, "workspace"),
            context.getExternalFilesDir(null)
        ).filterNotNull()

        val foundApks = mutableListOf<File>()
        searchDirs.forEach { dir ->
            if (dir.exists()) {
                dir.walkTopDown().forEach { file ->
                    if (file.isFile && file.extension.lowercase() == "apk") {
                        foundApks.add(file)
                    }
                }
            }
        }

        if (foundApks.isEmpty()) {
            return "SUCCESS: No APK files found in app workspace or downloads. Download or place an APK first."
        }

        val sb = StringBuilder("SUCCESS: Found ${foundApks.size} APK file(s):\n")
        val pm = context.packageManager

        foundApks.forEach { apk ->
            val info = pm.getPackageArchiveInfo(apk.absolutePath, 0)
            val pkg = info?.packageName ?: "Unknown"
            val ver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                "${info?.versionName ?: "N/A"} (${info?.longVersionCode ?: 0})"
            } else {
                @Suppress("DEPRECATION")
                "${info?.versionName ?: "N/A"} (${info?.versionCode ?: 0})"
            }
            sb.append("- ${apk.name} [Package: $pkg, Version: $ver, Size: ${apk.length() / (1024 * 1024)} MB]\n")
        }

        return sb.toString()
    }

    fun getApkInfo(pathOrName: String): String {
        val file = resolveApkFile(pathOrName) ?: return "FAILURE: APK file '$pathOrName' not found."

        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES
        ) ?: return "FAILURE: Unable to parse APK metadata from '${file.name}'. The file may be corrupt or incomplete."

        val packageName = info.packageName
        val versionName = info.versionName ?: "N/A"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        val permissions = info.requestedPermissions?.take(10)?.joinToString(", ") ?: "None specified"

        var isInstalled = false
        try {
            pm.getPackageInfo(packageName, 0)
            isInstalled = true
        } catch (e: Exception) {
            isInstalled = false
        }

        return """SUCCESS: APK Metadata for '${file.name}':
- Package Name: $packageName
- Version: $versionName (Build $versionCode)
- File Size: ${file.length() / 1024} KB
- Currently Installed: ${if (isInstalled) "Yes" else "No"}
- Permissions sample: $permissions"""
    }

    fun startInstallFlow(pathOrName: String): String {
        val file = resolveApkFile(pathOrName) ?: return "FAILURE: APK file '$pathOrName' not found."

        return try {
            // Check if can request install packages (Android 8.0+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val settingsIntent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(settingsIntent)
                    return "PERMISSION: Android requires 'Install Unknown Apps' permission for Zoya. I opened the permission settings screen. Please toggle it ON and retry."
                }
            }

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
            "SYSTEM CONFIRMATION: Opened Android's official Package Installer for '${file.name}'. Please confirm the installation prompt on your screen."
        } catch (e: Exception) {
            "FAILURE: Could not initiate APK installation: ${e.message}"
        }
    }

    fun startUninstallFlow(packageName: String): String {
        return try {
            val pm = context.packageManager
            try {
                pm.getPackageInfo(packageName, 0)
            } catch (e: Exception) {
                return "FAILURE: Application '$packageName' is not currently installed on this device."
            }

            val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(uninstallIntent)
            "SYSTEM CONFIRMATION: Opened official Android uninstall confirmation dialog for '$packageName'. Please tap OK to uninstall."
        } catch (e: Exception) {
            "FAILURE: Error launching uninstall flow: ${e.message}"
        }
    }

    private fun resolveApkFile(pathOrName: String): File? {
        val f = File(pathOrName)
        if (f.exists() && f.isFile) return f

        val searchDirs = listOf(
            File(context.filesDir, "downloads"),
            File(context.filesDir, "workspace"),
            context.getExternalFilesDir(null)
        ).filterNotNull()

        for (dir in searchDirs) {
            val direct = File(dir, pathOrName)
            if (direct.exists()) return direct
            val withApk = File(dir, if (pathOrName.endsWith(".apk")) pathOrName else "$pathOrName.apk")
            if (withApk.exists()) return withApk

            dir.walkTopDown().forEach { candidate ->
                if (candidate.name.equals(pathOrName, ignoreCase = true) ||
                    candidate.name.equals("$pathOrName.apk", ignoreCase = true)
                ) {
                    return candidate
                }
            }
        }
        return null
    }
}
