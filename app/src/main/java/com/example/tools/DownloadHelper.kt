package com.example.tools

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class DownloadHelper(private val context: Context) {

    val downloadsDir: File by lazy {
        val dir = File(context.filesDir, "downloads")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun downloadUrl(url: String, requestedFileName: String? = null): String = withContext(Dispatchers.IO) {
        try {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return@withContext "FAILURE: Invalid URL scheme. URL must begin with http:// or https://"
            }

            val guessedName = requestedFileName?.takeIf { it.isNotBlank() } ?: Uri.parse(url).lastPathSegment?.takeIf { it.isNotBlank() } ?: "download_${System.currentTimeMillis()}.bin"
            val cleanName = guessedName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val targetFile = File(downloadsDir, cleanName)

            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext "FAILURE: Server responded with HTTP code ${response.code}."
            }

            val body = response.body ?: return@withContext "FAILURE: Download response body was empty."
            val totalBytes = body.contentLength()

            targetFile.parentFile?.mkdirs()
            FileOutputStream(targetFile).use { output ->
                body.byteStream().use { input ->
                    input.copyTo(output)
                }
            }

            if (targetFile.exists() && targetFile.length() > 0) {
                "SUCCESS: Downloaded '${targetFile.name}' (${targetFile.length()} bytes) successfully. Path: ${targetFile.absolutePath}"
            } else {
                "FAILURE: Download completed but file verification failed."
            }
        } catch (e: Exception) {
            "FAILURE: Error downloading file: ${e.message}"
        }
    }

    fun listDownloads(): String {
        return try {
            val files = downloadsDir.listFiles() ?: emptyArray()
            if (files.isEmpty()) return "SUCCESS: No files found in downloads folder."

            val sb = StringBuilder("SUCCESS: Found ${files.size} downloaded files:\n")
            files.sortedByDescending { it.lastModified() }.forEach { f ->
                val sizeKb = f.length() / 1024
                sb.append("- ${f.name} ($sizeKb KB)\n")
            }
            sb.toString()
        } catch (e: Exception) {
            "FAILURE: Error listing downloads: ${e.message}"
        }
    }

    fun openDownloadedFile(nameOrPath: String): String {
        return try {
            val file = if (nameOrPath.startsWith("/")) File(nameOrPath) else File(downloadsDir, nameOrPath)
            if (!file.exists()) return "FAILURE: Downloaded file '${file.name}' not found."

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val extension = file.extension.lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            "SUCCESS: Opened '${file.name}'."
        } catch (e: Exception) {
            "FAILURE: Could not open downloaded file: ${e.message}"
        }
    }

    fun shareDownloadedFile(nameOrPath: String): String {
        return try {
            val file = if (nameOrPath.startsWith("/")) File(nameOrPath) else File(downloadsDir, nameOrPath)
            if (!file.exists()) return "FAILURE: Downloaded file '${file.name}' not found."

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val extension = file.extension.lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(shareIntent, "Share ${file.name}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            "SUCCESS: Opened share sheet for '${file.name}'."
        } catch (e: Exception) {
            "FAILURE: Could not share downloaded file: ${e.message}"
        }
    }
}
