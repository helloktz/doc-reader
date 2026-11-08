package io.github.helloktz.docreader

import android.os.Environment
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object PdfExport {
    fun destination(displayName: String): File = destination(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), displayName)

    fun destination(directory: File, displayName: String): File {
        val name = File(displayName).name
        val stem = (if (name.endsWith(".docx", true)) name.dropLast(5) else name).ifBlank { "文档" }
        return File(directory, "$stem.pdf")
    }

    /** A temporary file in Documents prevents truncating an existing PDF on copy failure. */
    fun save(pdf: File, desired: File, overwrite: Boolean): File {
        val directory = requireNotNull(desired.parentFile)
        if (!directory.isDirectory && !directory.mkdirs()) throw java.io.IOException("无法创建 Documents 目录")
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
                val target = if (index == 0) desired else File(directory, desired.nameWithoutExtension + " ($index).pdf")
                try { Files.move(temporary.toPath(), target.toPath()); return target }
                catch (_: java.nio.file.FileAlreadyExistsException) { index++ }
            }
        } finally { temporary.delete() }
    }
}
