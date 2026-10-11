package com.spacedrift.fieldproof.ui.format

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {

    private val timeMillis: Long get() = System.currentTimeMillis()

    fun timestamp(epochMillis: Long): String {
        if (epochMillis <= 0L) return "—"
        return SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
            .format(Date(epochMillis))
    }

    fun relative(epochMillis: Long, now: Long = timeMillis): String {
        if (epochMillis <= 0L) return "—"
        val diff = now - epochMillis
        return when {
            diff < 60_000L -> "Just now"
            diff < 3_600_000L -> "${diff / 60_000L}m ago"
            diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
            diff < 7 * 86_400_000L -> "${diff / 86_400_000L}d ago"
            else -> timestamp(epochMillis)
        }
    }
}