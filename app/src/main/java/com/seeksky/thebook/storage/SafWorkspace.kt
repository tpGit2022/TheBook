package com.seeksky.thebook.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

data class WorkspaceDirectories(
    val root: DocumentFile,
    val originalFiles: DocumentFile,
    val encryptedFiles: DocumentFile,
    val decryptedFiles: DocumentFile
)

class SafWorkspace(context: Context) {

    private val applicationContext = context.applicationContext
    private val contentResolver = applicationContext.contentResolver
    private val preferences = applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun persist(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val previousUri = storedUri()
        contentResolver.takePersistableUriPermission(uri, flags)
        if (!preferences.edit().putString(KEY_TREE_URI, uri.toString()).commit()) {
            if (previousUri != uri) {
                runCatching { contentResolver.releasePersistableUriPermission(uri, flags) }
            }
            throw IOException("无法保存工作目录授权")
        }
        if (previousUri != null && previousUri != uri) {
            runCatching {
                contentResolver.releasePersistableUriPermission(previousUri, flags)
            }
        }
    }

    fun hasPersistedAccess(): Boolean {
        val uri = storedUri() ?: return false
        return contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission && permission.isWritePermission
        }
    }

    fun clearPersistedAccess() {
        val uri = storedUri()
        preferences.edit().remove(KEY_TREE_URI).commit()
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { contentResolver.releasePersistableUriPermission(uri, flags) }
        }
    }

    fun selectedDirectoryName(): String? {
        if (!hasPersistedAccess()) return null
        val uri = storedUri() ?: return null
        return DocumentFile.fromTreeUri(applicationContext, uri)?.name ?: uri.lastPathSegment
    }

    fun openDirectories(): WorkspaceDirectories {
        val uri = storedUri() ?: throw IOException("尚未选择加解密工作目录")
        val hasAccess = contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission && permission.isWritePermission
        }
        if (!hasAccess) {
            throw IOException("工作目录授权已失效，请重新选择目录")
        }

        val root = DocumentFile.fromTreeUri(applicationContext, uri)
            ?: throw IOException("无法打开所选工作目录")
        if (!root.exists() || !root.isDirectory || !root.canRead() || !root.canWrite()) {
            throw IOException("所选工作目录不可读写，请重新选择目录")
        }

        return WorkspaceDirectories(
            root = root,
            originalFiles = requireDirectory(root, listOf(DIRECTORY_ORIGINAL)),
            encryptedFiles = requireDirectory(
                root,
                listOf(DIRECTORY_INPUT, DIRECTORY_ENCRYPTED)
            ),
            decryptedFiles = requireDirectory(
                root,
                listOf(DIRECTORY_OUTPUT, DIRECTORY_DECRYPTED)
            )
        )
    }

    fun listFilesRecursively(directory: DocumentFile, maxDepth: Int = 10): List<DocumentFile> {
        val files = mutableListOf<DocumentFile>()

        fun visit(current: DocumentFile, depth: Int) {
            if (depth > maxDepth) return
            current.listFiles().forEach { child ->
                when {
                    child.isFile -> files += child
                    child.isDirectory -> visit(child, depth + 1)
                }
            }
        }

        visit(directory, 0)
        return files
    }

    fun replaceFile(directory: DocumentFile, displayName: String): DocumentFile {
        val safeName = sanitizeDisplayName(displayName)
        directory.findFile(safeName)?.let { existing ->
            if (existing.isDirectory) {
                throw IOException("输出目录中存在同名文件夹：$safeName")
            }
            if (!existing.delete()) {
                throw IOException("无法覆盖已有文件：$safeName")
            }
        }
        return directory.createFile(MIME_BINARY, safeName)
            ?: throw IOException("无法创建输出文件：$safeName")
    }

    private fun storedUri(): Uri? {
        val value = preferences.getString(KEY_TREE_URI, null) ?: return null
        return runCatching { Uri.parse(value) }.getOrNull()
    }

    private fun requireDirectory(root: DocumentFile, path: List<String>): DocumentFile {
        return path.fold(root) { parent, name ->
            val existing = parent.findFile(name)
            when {
                existing == null -> parent.createDirectory(name)
                    ?: throw IOException("无法创建工作目录：${path.joinToString("/")}")
                existing.isDirectory -> existing
                else -> throw IOException("工作目录中存在同名文件：$name")
            }
        }
    }

    private fun sanitizeDisplayName(displayName: String): String {
        val safeName = displayName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        if (safeName.isBlank() || safeName == "." || safeName == "..") {
            throw IOException("输出文件名无效")
        }
        return safeName
    }

    private companion object {
        const val PREFERENCES_NAME = "saf_workspace"
        const val KEY_TREE_URI = "tree_uri"
        const val DIRECTORY_ORIGINAL = "origin_media_data"
        const val DIRECTORY_INPUT = "f_input"
        const val DIRECTORY_ENCRYPTED = "encrypt_data"
        const val DIRECTORY_OUTPUT = "f_output"
        const val DIRECTORY_DECRYPTED = "decrypt_data"
        const val MIME_BINARY = "application/octet-stream"
    }
}
