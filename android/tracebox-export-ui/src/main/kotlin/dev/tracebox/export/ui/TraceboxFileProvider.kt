package dev.tracebox.export.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Path

/** Read-only provider deliberately constrained to direct files in Tracebox's one staging directory. */
class TraceboxFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "application/zip"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Tracebox exports are read-only")
        val file = checkedFile(uri)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    private fun checkedFile(uri: Uri): File {
        val name = uri.lastPathSegment ?: throw FileNotFoundException("missing export name")
        if (name.contains('/') || name.contains('\\') || name == "." || name == "..") throw FileNotFoundException("invalid export name")
        val root = checkNotNull(context).noBackupFilesDir.toPath()
            .resolve("tracebox")
            .resolve("export-staging")
            .toFile()
            .canonicalFile
        val file = File(root, name).canonicalFile
        if (file.parentFile != root || !file.isFile) throw FileNotFoundException("outside staging")
        return file
    }

    companion object {
        fun uriForFile(context: android.content.Context, path: Path): Uri {
            val root = context.noBackupFilesDir.toPath()
                .resolve("tracebox")
                .resolve("export-staging")
                .toFile()
                .canonicalFile
            val file = path.toFile().canonicalFile
            require(file.parentFile == root && file.isFile) { "export must be a direct staging file" }
            return Uri.Builder()
                .scheme("content")
                .authority("${context.packageName}.tracebox.export")
                .appendPath(file.name)
                .build()
        }
    }
}
