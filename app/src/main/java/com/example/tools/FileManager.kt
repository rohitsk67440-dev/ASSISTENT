package com.example.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class FileManager(private val context: Context) {

    val workspaceRoot: File by lazy {
        val dir = File(context.filesDir, "workspace")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    val downloadsDir: File by lazy {
        val dir = File(context.filesDir, "downloads")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    private fun resolveFile(pathStr: String): File {
        val clean = pathStr.trim().removePrefix("/")
        return if (clean.startsWith("workspace/")) {
            File(context.filesDir, clean)
        } else if (clean.startsWith("downloads/")) {
            File(context.filesDir, clean)
        } else if (pathStr.startsWith("/")) {
            // Check if absolute path or starts with context filesDir
            val f = File(pathStr)
            if (f.isAbsolute && f.exists()) f else File(workspaceRoot, clean)
        } else {
            File(workspaceRoot, clean)
        }
    }

    fun createFile(path: String, content: String): String {
        return try {
            val file = resolveFile(path)
            file.parentFile?.mkdirs()
            file.writeText(content)
            if (file.exists() && file.isFile) {
                "SUCCESS: File created at '${file.name}' (${file.length()} bytes). Full path: ${file.absolutePath}"
            } else {
                "FAILURE: File write completed but verification failed."
            }
        } catch (e: Exception) {
            "FAILURE: Error creating file: ${e.message}"
        }
    }

    fun readFile(path: String, startLine: Int = 1, endLine: Int = 500): String {
        return try {
            val file = resolveFile(path)
            if (!file.exists()) return "FAILURE: File does not exist at '${file.name}'"
            if (!file.isFile) return "FAILURE: Target '${file.name}' is a directory, not a file."

            val lines = file.readLines()
            val total = lines.size
            val start = (startLine - 1).coerceIn(0, total)
            val end = endLine.coerceIn(start, total)

            val slice = lines.subList(start, end)
            val sb = StringBuilder("SUCCESS: Read ${slice.size} lines from '${file.name}' (Total: $total lines):\n")
            slice.forEachIndexed { idx, line ->
                sb.append("${start + idx + 1}: $line\n")
            }
            sb.toString()
        } catch (e: Exception) {
            "FAILURE: Error reading file: ${e.message}"
        }
    }

    fun writeFile(path: String, content: String, append: Boolean = false): String {
        return try {
            val file = resolveFile(path)
            file.parentFile?.mkdirs()
            if (append) {
                file.appendText(content)
            } else {
                file.writeText(content)
            }
            if (file.exists()) {
                "SUCCESS: Wrote content to '${file.name}' (${file.length()} bytes)."
            } else {
                "FAILURE: Verification failed after writing."
            }
        } catch (e: Exception) {
            "FAILURE: Error writing file: ${e.message}"
        }
    }

    fun deleteFile(path: String, permanent: Boolean = true): String {
        return try {
            val file = resolveFile(path)
            if (!file.exists()) return "FAILURE: Target '${file.name}' does not exist."

            val isDir = file.isDirectory
            val deleted = file.deleteRecursively()
            if (deleted && !file.exists()) {
                "SUCCESS: ${if (isDir) "Folder" else "File"} '${file.name}' deleted successfully."
            } else {
                "FAILURE: Could not delete '${file.name}'."
            }
        } catch (e: Exception) {
            "FAILURE: Error deleting file: ${e.message}"
        }
    }

    fun moveFile(sourcePath: String, destPath: String): String {
        return try {
            val src = resolveFile(sourcePath)
            val dest = resolveFile(destPath)
            if (!src.exists()) return "FAILURE: Source '${src.name}' does not exist."
            dest.parentFile?.mkdirs()

            val success = src.renameTo(dest)
            if (success && dest.exists()) {
                "SUCCESS: Moved '${src.name}' to '${dest.name}'."
            } else {
                // Fallback copy then delete
                src.copyRecursively(dest, overwrite = true)
                src.deleteRecursively()
                if (dest.exists()) {
                    "SUCCESS: Moved '${src.name}' to '${dest.name}'."
                } else {
                    "FAILURE: Failed to move '${src.name}'."
                }
            }
        } catch (e: Exception) {
            "FAILURE: Error moving file: ${e.message}"
        }
    }

    fun copyFile(sourcePath: String, destPath: String): String {
        return try {
            val src = resolveFile(sourcePath)
            val dest = resolveFile(destPath)
            if (!src.exists()) return "FAILURE: Source '${src.name}' does not exist."
            dest.parentFile?.mkdirs()
            src.copyRecursively(dest, overwrite = true)
            if (dest.exists()) {
                "SUCCESS: Copied '${src.name}' to '${dest.name}' (${dest.length()} bytes)."
            } else {
                "FAILURE: Failed to verify copy."
            }
        } catch (e: Exception) {
            "FAILURE: Error copying file: ${e.message}"
        }
    }

    fun renameFile(path: String, newName: String): String {
        return try {
            val file = resolveFile(path)
            if (!file.exists()) return "FAILURE: Target '${file.name}' does not exist."
            val target = File(file.parentFile, newName.trim())
            if (file.renameTo(target)) {
                "SUCCESS: Renamed to '${target.name}'."
            } else {
                "FAILURE: Could not rename '${file.name}' to '$newName'."
            }
        } catch (e: Exception) {
            "FAILURE: Error renaming file: ${e.message}"
        }
    }

    fun createFolder(path: String): String {
        return try {
            val folder = resolveFile(path)
            val created = folder.mkdirs()
            if (folder.exists() && folder.isDirectory) {
                "SUCCESS: Directory created at '${folder.name}'."
            } else {
                "FAILURE: Could not create folder '${folder.name}'."
            }
        } catch (e: Exception) {
            "FAILURE: Error creating folder: ${e.message}"
        }
    }

    fun listFolder(path: String = ""): String {
        return try {
            val folder = if (path.isBlank()) workspaceRoot else resolveFile(path)
            if (!folder.exists()) return "FAILURE: Folder '${folder.name}' does not exist."
            if (!folder.isDirectory) return "FAILURE: '${folder.name}' is not a directory."

            val files = folder.listFiles() ?: emptyArray()
            if (files.isEmpty()) return "SUCCESS: Directory '${folder.name}' is empty."

            val sb = StringBuilder("SUCCESS: Found ${files.size} items in '${folder.name}':\n")
            files.sortedBy { !it.isDirectory }.forEach { f ->
                val type = if (f.isDirectory) "[DIR]" else "[FILE]"
                val size = if (f.isDirectory) "" else "${f.length()} B"
                sb.append("- $type ${f.name} $size\n")
            }
            sb.toString()
        } catch (e: Exception) {
            "FAILURE: Error listing folder: ${e.message}"
        }
    }

    fun searchFiles(query: String, path: String = ""): String {
        return try {
            val folder = if (path.isBlank()) workspaceRoot else resolveFile(path)
            if (!folder.exists()) return "FAILURE: Search folder does not exist."

            val matches = mutableListOf<File>()
            val cleanQuery = query.lowercase().trim()

            folder.walkTopDown().forEach { f ->
                if (f.name.lowercase().contains(cleanQuery)) {
                    matches.add(f)
                }
            }

            if (matches.isEmpty()) return "SUCCESS: No files or folders matched '$query'."

            val sb = StringBuilder("SUCCESS: Found ${matches.size} matches for '$query':\n")
            matches.take(30).forEach { f ->
                val type = if (f.isDirectory) "[DIR]" else "[FILE]"
                sb.append("- $type ${f.relativeTo(workspaceRoot).path} (${f.length()} B)\n")
            }
            sb.toString()
        } catch (e: Exception) {
            "FAILURE: Error searching files: ${e.message}"
        }
    }

    fun createZip(sourcePath: String, zipName: String = "project.zip"): String {
        return try {
            val source = resolveFile(sourcePath)
            if (!source.exists()) return "FAILURE: Source '${source.name}' does not exist."

            val finalZipName = if (zipName.endsWith(".zip")) zipName else "$zipName.zip"
            val zipFile = File(workspaceRoot, finalZipName)

            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                if (source.isDirectory) {
                    source.walkTopDown().forEach { file ->
                        if (file != source && file.isFile) {
                            val relPath = file.relativeTo(source).path
                            zos.putNextEntry(ZipEntry(relPath))
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                } else {
                    zos.putNextEntry(ZipEntry(source.name))
                    source.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }

            if (zipFile.exists() && zipFile.length() > 0) {
                "SUCCESS: Created ZIP archive '${zipFile.name}' (${zipFile.length()} bytes) at ${zipFile.absolutePath}."
            } else {
                "FAILURE: ZIP file creation verification failed."
            }
        } catch (e: Exception) {
            "FAILURE: Error creating ZIP: ${e.message}"
        }
    }

    fun extractZip(zipPath: String, destinationPath: String = ""): String {
        return try {
            val zipFile = resolveFile(zipPath)
            if (!zipFile.exists()) return "FAILURE: ZIP file '${zipFile.name}' does not exist."

            val targetDir = if (destinationPath.isBlank()) {
                File(workspaceRoot, zipFile.nameWithoutExtension)
            } else {
                resolveFile(destinationPath)
            }
            targetDir.mkdirs()

            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val newFile = File(targetDir, entry.name)
                    // Security check against Zip Slip
                    if (!newFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                        throw SecurityException("Zip entry is outside of the target dir: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        newFile.mkdirs()
                    } else {
                        newFile.parentFile?.mkdirs()
                        FileOutputStream(newFile).use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            "SUCCESS: Extracted '${zipFile.name}' into '${targetDir.name}'."
        } catch (e: Exception) {
            "FAILURE: Error extracting ZIP: ${e.message}"
        }
    }

    fun openFile(path: String): String {
        return try {
            val file = resolveFile(path)
            if (!file.exists()) return "FAILURE: File '${file.name}' not found."

            val uri: Uri = FileProvider.getUriForFile(
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
            "SUCCESS: Opened '${file.name}' with system viewer."
        } catch (e: Exception) {
            "FAILURE: Could not open file: ${e.message}"
        }
    }

    fun shareFile(path: String): String {
        return try {
            val file = resolveFile(path)
            if (!file.exists()) return "FAILURE: File '${file.name}' not found."

            val uri: Uri = FileProvider.getUriForFile(
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
            "FAILURE: Could not share file: ${e.message}"
        }
    }
}
