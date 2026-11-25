package com.alphaai.app.data.repository

import android.content.Context
import android.net.Uri
import com.alphaai.app.data.local.ArtifactDao
import com.alphaai.app.data.local.ArtifactEntity
import com.alphaai.app.data.sandbox.SandboxClient
import com.alphaai.app.util.SafeLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArtifactRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ArtifactDao
) {
    val all: Flow<List<ArtifactEntity>> = dao.observeAll()
    fun forSession(id: Long): Flow<List<ArtifactEntity>> = dao.observeForSession(id)
    suspend fun get(id: Long): ArtifactEntity? = dao.getById(id)

    /** Imports an image or PDF selected through SAF into app-private storage. */
    suspend fun storeAttachment(sessionId: Long, uri: Uri): ArtifactEntity = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri)?.lowercase() ?: "application/octet-stream"
        require(mime.startsWith("image/") || mime == "application/pdf") { "Only image or PDF attachments are supported" }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_ATTACHMENT_BYTES) { "Attachment exceeds 10 MB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw IllegalArgumentException("Unable to open selected attachment")
        val display = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "attachment.${if (mime == "application/pdf") "pdf" else "img"}"
        val name = display.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "attachment" }
        val root = File(context.filesDir, "artifacts").apply { mkdirs() }.canonicalFile
        val dir = File(root, sessionId.toString()).apply { mkdirs() }.canonicalFile
        require(dir.path.startsWith(root.path + File.separator))
        val target = File(dir, "${System.currentTimeMillis()}_$name").canonicalFile
        require(target.path.startsWith(dir.path + File.separator))
        target.writeBytes(bytes)
        val entity = ArtifactEntity(sessionId = sessionId, name = name, mimeType = mime, relativePath = target.relativeTo(root).path, sizeBytes = bytes.size.toLong(), createdAt = System.currentTimeMillis())
        val id = dao.insert(entity)
        entity.copy(id = id)
    }

    /** Pulls a sandbox file into app-private storage and indexes it in Room. */
    suspend fun importFromSandbox(sessionId: Long, path: String, sandbox: SandboxClient): ArtifactEntity? = withContext(Dispatchers.IO) {
        val safePath = path.trim().removePrefix("artifacts/").trimStart('/')
        require(safePath.isNotBlank() && !safePath.split('/').any { it == ".." }) { "invalid artifact path" }
        val bytes = try {
            sandbox.readFileBytes(path)
        } catch (e: Exception) {
            SafeLog.w(TAG, "artifact read failed: ${e.message?.take(200)}")
            return@withContext null
        }
        val root = File(context.filesDir, "artifacts").apply { mkdirs() }.canonicalFile
        val name = safePath.substringAfterLast('/').ifBlank { "artifact.txt" }.take(120)
        val sessionDir = File(root, sessionId.toString()).apply { mkdirs() }.canonicalFile
        require(sessionDir.path.startsWith(root.path + File.separator)) { "invalid artifact destination" }
        val dest = File(sessionDir, "${System.currentTimeMillis()}_$name").canonicalFile
        require(dest.path.startsWith(sessionDir.path + File.separator)) { "invalid artifact filename" }
        dest.writeBytes(bytes)
        val mime = when (dest.extension.lowercase()) {
            "md", "markdown" -> "text/markdown"
            "html", "htm" -> "text/html"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "pdf" -> "application/pdf"
            "svg" -> "image/svg+xml"
            else -> "text/plain"
        }
        val entity = ArtifactEntity(
            sessionId = sessionId,
            name = name,
            mimeType = mime,
            relativePath = dest.relativeTo(root).path,
            sizeBytes = dest.length(),
            createdAt = System.currentTimeMillis()
        )
        val id = dao.insert(entity)
        entity.copy(id = id)
    }

    fun fileFor(entity: ArtifactEntity): File {
        val root = File(context.filesDir, "artifacts").canonicalFile
        val file = File(root, entity.relativePath).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { "artifact path escapes private storage" }
        return file
    }

    suspend fun delete(id: Long) {
        val entity = dao.getById(id) ?: return
        runCatching { fileFor(entity).delete() }
        dao.delete(id)
    }

    suspend fun deleteForSession(sessionId: Long) {
        dao.getForSession(sessionId).forEach { item -> runCatching { fileFor(item).delete() } }
        dao.deleteForSession(sessionId)
        runCatching { File(context.filesDir, "artifacts/$sessionId").deleteRecursively() }
    }

    private companion object {
        const val TAG = "ArtifactRepository"
        const val MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024
    }
}
