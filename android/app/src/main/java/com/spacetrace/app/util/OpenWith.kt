package com.spacetrace.app.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.spacetrace.app.model.FileEntry
import java.io.File

/**
 * Hands a file or folder off to whatever app the user picks, via a FileProvider content:// URI --
 * plain file:// URIs are blocked (StrictMode) once shared outside this app's own process.
 */
object OpenWith {

    fun launch(context: Context, entry: FileEntry) {
        launch(context, path = entry.path, isDirectory = entry.isDirectory, name = entry.name)
    }

    /** For folder nodes in the tree view, which don't carry a full [FileEntry]. */
    fun launch(context: Context, path: String, isDirectory: Boolean, name: String) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                File(path),
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                if (isDirectory) {
                    setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                } else {
                    setDataAndType(uri, mimeTypeFor(File(path).extension))
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Open with"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app found to open this", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(context, "Couldn't open \"$name\"", Toast.LENGTH_SHORT).show()
        }
    }

    private fun mimeTypeFor(extension: String): String {
        if (extension.isBlank()) return "*/*"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()) ?: "*/*"
    }
}
