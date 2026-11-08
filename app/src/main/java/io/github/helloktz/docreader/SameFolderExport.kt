package io.github.helloktz.docreader

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object SameFolderExport {
    fun sourceFile(context: Context, uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let(::File)?.canonicalFile
        val authority = uri.authority
        val id = try { DocumentsContract.getDocumentId(uri) } catch (_: Exception) { "" }
        if (authority == "com.android.externalstorage.documents") {
            val pieces = id.split(':', limit = 2)
            if (pieces.size == 2) {
                val volume = if (pieces[0].equals("primary", true)) Environment.getExternalStorageDirectory()
                else context.getSystemService(StorageManager::class.java).storageVolumes.firstOrNull { it.uuid.equals(pieces[0], true) }?.directory
                if (volume != null) {
                    val file = File(volume, pieces[1]).canonicalFile
                    if (file.path.startsWith(volume.canonicalPath + File.separator)) return file
                }
            }
        }
        if (authority == "com.android.providers.downloads.documents") {
            if (id.startsWith("raw:")) return File(id.removePrefix("raw:")).canonicalFile
            if (id.startsWith("msf:")) id.substringAfter(':').toLongOrNull()?.let {
                return dataPath(context, ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), it))
            }
            id.toLongOrNull()?.let { return dataPath(context, ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), it)) }
        }
        if (authority == "com.android.providers.media.documents") {
            id.substringAfter(':', "").toLongOrNull()?.let {
                return dataPath(context, ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), it))
            }
        }
        return null
    }

    private fun dataPath(context: Context, uri: Uri): File? = try {
        context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) File(it.getString(0)).canonicalFile else null
        }
    } catch (_: Exception) { null }

    fun destination(source: File) = File(source.parentFile, source.nameWithoutExtension + ".pdf")

    /** Writes a sibling only. A temporary sibling prevents truncating a PDF on copy failure. */
    fun save(pdf: File, source: File, overwrite: Boolean): File {
        require(source.isFile) { "原文件已移动或无法访问" }
        val directory = requireNotNull(source.parentFile)
        val desired = destination(source)
        val temporary = File.createTempFile(".doc-reader-", ".tmp", directory)
        try {
            pdf.inputStream().use { input -> temporary.outputStream().use { input.copyTo(it); it.fd.sync() } }
            if (overwrite) {
                try { Files.move(temporary.toPath(), desired.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), desired.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                return desired
            }
            var index = 0
            while (true) {
                val target = if (index == 0) desired else File(directory, source.nameWithoutExtension + " ($index).pdf")
                try { Files.move(temporary.toPath(), target.toPath()); return target }
                catch (_: java.nio.file.FileAlreadyExistsException) { index++ }
            }
        } finally { temporary.delete() }
    }
}
