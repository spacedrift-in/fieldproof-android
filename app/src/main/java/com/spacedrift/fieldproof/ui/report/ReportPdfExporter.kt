package com.spacedrift.fieldproof.ui.report

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object ReportPdfExporter {

    fun writeToCache(
        context: Context,
        inspectionId: String,
        bytes: ByteArray
    ): File {
        val directory = File(context.cacheDir, "reports")
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val safeId = inspectionId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(directory, "fieldproof-report-$safeId.pdf")
        file.writeBytes(bytes)
        return file
    }

    fun suggestedFileName(inspectionId: String): String {
        val safeId = inspectionId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return "fieldproof-report-$safeId.pdf"
    }

    fun open(context: Context, file: File): Boolean {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}