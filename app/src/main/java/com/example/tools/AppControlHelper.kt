package com.example.tools

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

class AppControlHelper(private val context: Context) {

    fun listInstalledApps(query: String = ""): String {
        return try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val nonSystemApps = apps.filter {
                (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                        pm.getLaunchIntentForPackage(it.packageName) != null
            }

            val filtered = if (query.isNotBlank()) {
                val clean = query.lowercase().trim()
                nonSystemApps.filter {
                    val label = pm.getApplicationLabel(it).toString().lowercase()
                    label.contains(clean) || it.packageName.lowercase().contains(clean)
                }
            } else {
                nonSystemApps
            }

            if (filtered.isEmpty()) {
                return "SUCCESS: No applications matching '$query' were found."
            }

            val sb = StringBuilder("SUCCESS: Found ${filtered.size} applications:\n")
            filtered.take(40).forEach { app ->
                val label = pm.getApplicationLabel(app).toString()
                sb.append("- $label (${app.packageName})\n")
            }
            if (filtered.size > 40) {
                sb.append("...and ${filtered.size - 40} more.")
            }
            sb.toString()
        } catch (e: Exception) {
            "FAILURE: Error listing applications: ${e.message}"
        }
    }

    fun launchApp(appNameOrPackage: String): String {
        val lower = appNameOrPackage.lowercase().trim()

        if (lower in listOf("camera", "kamera", "photo")) {
            val intent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                return "SUCCESS: Camera launched."
            } catch (e: Exception) {}
        }

        val pm = context.packageManager
        // 1. Direct package match
        val directIntent = pm.getLaunchIntentForPackage(appNameOrPackage)
        if (directIntent != null) {
            directIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(directIntent)
            return "SUCCESS: Launched app '$appNameOrPackage'."
        }

        // 2. Search installed applications by label
        val apps = pm.getInstalledApplications(0)
        var targetPkg: String? = null
        for (app in apps) {
            val label = pm.getApplicationLabel(app).toString().lowercase()
            if (label == lower || label.contains(lower)) {
                targetPkg = app.packageName
                break
            }
        }

        if (targetPkg != null) {
            val intent = pm.getLaunchIntentForPackage(targetPkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return "SUCCESS: Launched app '$appNameOrPackage' ($targetPkg)."
            }
        }

        return "FAILURE: Could not find an installed app matching '$appNameOrPackage'."
    }

    fun openAppInfo(appNameOrPackage: String): String {
        val pkg = resolvePackageName(appNameOrPackage) ?: return "FAILURE: App '$appNameOrPackage' not found."
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$pkg")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "SUCCESS: Opened App Info settings for '$pkg'."
        } catch (e: Exception) {
            "FAILURE: Could not open App Info: ${e.message}"
        }
    }

    fun openAppPermissions(appNameOrPackage: String): String {
        return openAppInfo(appNameOrPackage)
    }

    fun openAppNotificationSettings(appNameOrPackage: String): String {
        val pkg = resolvePackageName(appNameOrPackage) ?: return "FAILURE: App '$appNameOrPackage' not found."
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "SUCCESS: Opened notification settings for '$pkg'."
            } else {
                openAppInfo(pkg)
            }
        } catch (e: Exception) {
            openAppInfo(pkg)
        }
    }

    fun openAppStorageSettings(appNameOrPackage: String): String {
        return openAppInfo(appNameOrPackage)
    }

    private fun resolvePackageName(nameOrPkg: String): String? {
        val pm = context.packageManager
        try {
            pm.getPackageInfo(nameOrPkg, 0)
            return nameOrPkg
        } catch (e: Exception) {}

        val lower = nameOrPkg.lowercase().trim()
        val apps = pm.getInstalledApplications(0)
        for (app in apps) {
            val label = pm.getApplicationLabel(app).toString().lowercase()
            if (label == lower || label.contains(lower)) {
                return app.packageName
            }
        }
        return null
    }
}
