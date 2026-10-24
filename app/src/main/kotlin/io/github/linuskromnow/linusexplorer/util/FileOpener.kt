package io.github.linuskromnow.linusexplorer.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/** Hands local files to other apps through the app's FileProvider. */
object FileOpener {
    private const val ANY_TYPE = "*/*"

    fun mimeType(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: ANY_TYPE
    }

    /** Returns false if no installed app can open the file. */
    fun open(context: Context, path: String): Boolean {
        val file = File(path)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(context, file), mimeType(file.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    fun share(context: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        val files = paths.map(::File)
        val uris = files.map { uriFor(context, it) }
        val types = files.map { mimeType(it.name) }.distinct()
        val type = when {
            types.size == 1 -> types.single()
            types.map { it.substringBefore('/') }.distinct().size == 1 -> types.first().substringBefore('/') + "/*"
            else -> ANY_TYPE
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = type
        intent.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, null))
    }

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
