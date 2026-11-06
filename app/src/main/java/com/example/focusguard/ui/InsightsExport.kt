package com.example.focusguard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Our own FileProvider subclass, so it can never clash with another provider in the project. */
class InsightsFileProvider : FileProvider()

/** Shares the data files (email, Drive, USB...) so you can train the model on a computer. */
object InsExport {
    fun share(context: Context) {
        val files = listOf(InsightsEngine.EVENTS_FILE, InsightsEngine.DAILY_EXPORT_FILE)
            .map { File(context.filesDir, it) }
            .filter { it.exists() && it.length() > 0 }

        if (files.isEmpty()) {
            Toast.makeText(context, "No data yet. Use your phone for a while first.", Toast.LENGTH_LONG).show()
            return
        }

        val uris = ArrayList<Uri>(
            files.map { FileProvider.getUriForFile(context, context.packageName + ".insightsexport", it) }
        )
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, "FocusGuard data export")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "Export data").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
