package com.spacedrift.fieldproof.ui.navigation

import android.net.Uri

object Routes {
    const val HOME = "home"
    const val DETAIL = "detail/{inspectionId}"
    const val CAPTURE = "capture/{inspectionId}?checklistItemId={checklistItemId}&label={label}"

    const val ARG_INSPECTION_ID = "inspectionId"
    const val ARG_CHECKLIST_ITEM_ID = "checklistItemId"
    const val ARG_LABEL = "label"

    fun detail(inspectionId: String): String = "detail/$inspectionId"

    fun capture(
        inspectionId: String,
        checklistItemId: String? = null,
        label: String? = null
    ): String {
        val base = "capture/$inspectionId"
        val query = buildList {
            if (checklistItemId != null) {
                add("checklistItemId=$checklistItemId")
            }
            if (label != null) {
                add("label=${Uri.encode(label)}")
            }
        }
        return if (query.isEmpty()) base else "$base?${query.joinToString("&")}"
    }
}