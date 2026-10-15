package com.spacedrift.fieldproof.domain.conflict

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Maps the raw body of a backend HTTP 409 response onto a [ServerConflict].
 *
 * The backend (FastAPI) wraps every error as `{"detail": <value>}`. This parser
 * is deliberately lenient: anything it cannot recognize becomes
 * [ServerConflict.Unrecognized] that still carries the raw text, so the payload
 * is preserved for the UI either way. It never throws.
 */
object ServerConflictParser {

    const val FALLBACK_MESSAGE = "The server reported a conflict."

    fun parse(body: String?): ServerConflict {
        val raw = body.orEmpty()
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return ServerConflict.Unrecognized(FALLBACK_MESSAGE, raw)
        }

        val root = runCatching { JsonParser.parseString(trimmed) }.getOrNull()
        if (root == null || !root.isJsonObject) {
            return ServerConflict.Unrecognized(trimmed, raw)
        }

        val detail = root.asJsonObject.get("detail")
            ?: return ServerConflict.Unrecognized(trimmed, raw)

        return when {
            detail.isJsonPrimitive && detail.asJsonPrimitive.isString ->
                ServerConflict.Rejected(detail.asString, raw)

            detail.isJsonObject -> parseObject(detail.asJsonObject, raw)

            else -> ServerConflict.Unrecognized(trimmed, raw)
        }
    }

    private fun parseObject(
        detail: JsonObject,
        raw: String
    ): ServerConflict {
        val message = detail.stringOrNull("message")

        detail.stringListOrNull("missing_checklist_item_ids")?.let { missing ->
            return ServerConflict.MissingRequiredEvidence(
                message = message
                    ?: "Missing required evidence for submission.",
                missingChecklistItemIds = missing,
                rawDetail = raw
            )
        }

        val currentVersion = detail.intOrNull("current_version")
        val currentStatus = detail.stringOrNull("current_status")
        if (currentVersion != null || currentStatus != null) {
            return ServerConflict.Version(
                message = message
                    ?: "Version conflict: the inspection changed on the server.",
                currentVersion = currentVersion ?: 0,
                currentStatus = currentStatus ?: "",
                rawDetail = raw
            )
        }

        return ServerConflict.Rejected(
            message = message ?: detail.toString(),
            rawDetail = raw
        )
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val element = get(key) ?: return null
        return if (element.isJsonPrimitive) element.asString else null
    }

    private fun JsonObject.intOrNull(key: String): Int? {
        val element = get(key) ?: return null
        return if (element.isJsonPrimitive && element.asJsonPrimitive.isNumber) {
            element.asInt
        } else {
            null
        }
    }

    private fun JsonObject.stringListOrNull(key: String): List<String>? {
        val element = get(key) ?: return null
        if (!element.isJsonArray) return null
        return element.asJsonArray.mapNotNull { item ->
            if (item.isJsonPrimitive) item.asString else null
        }
    }
}
