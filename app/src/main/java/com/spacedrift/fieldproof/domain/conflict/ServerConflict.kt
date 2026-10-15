package com.spacedrift.fieldproof.domain.conflict

/**
 * Stable, UI-facing representation of a backend HTTP 409 conflict response.
 *
 * The backend wraps every error in FastAPI's default envelope
 * (`{"detail": <value>}`). The `detail` is either a structured object (version
 * conflict, missing required evidence) or a plain message string (invalid state
 * transition, idempotency-key reuse, id collision). This model maps those shapes
 * onto typed cases while always retaining [rawDetail] — the server's original,
 * unmodified payload — so nothing is lost on the way to the UI.
 *
 * The model is intentionally decoupled from both the Retrofit DTOs and the Room
 * entities so the wire contract can evolve without leaking into the UI.
 */
sealed interface ServerConflict {

    /** Human-readable summary taken from the server's `detail.message`. */
    val message: String

    /** The original, unmodified server payload body (empty when none was sent). */
    val rawDetail: String

    /**
     * Optimistic-concurrency conflict: the inspection changed on the server.
     * Wire shape: `{"detail":{"message", "current_version", "current_status"}}`.
     */
    data class Version(
        override val message: String,
        val currentVersion: Int,
        val currentStatus: String,
        override val rawDetail: String
    ) : ServerConflict

    /**
     * Submission blocked because required checklist items have no evidence.
     * Wire shape: `{"detail":{"message", "missing_checklist_item_ids":[...]}}`.
     */
    data class MissingRequiredEvidence(
        override val message: String,
        val missingChecklistItemIds: List<String>,
        override val rawDetail: String
    ) : ServerConflict

    /**
     * A 409 whose `detail` is a plain message: invalid state transition,
     * idempotency-key reuse, or an id that already exists with different
     * content/attributes. The server text is preserved verbatim in [message].
     */
    data class Rejected(
        override val message: String,
        override val rawDetail: String
    ) : ServerConflict

    /**
     * A 409 whose body was missing, empty, or not a recognized shape. [message]
     * falls back to whatever text was available so the UI is never blank, and
     * [rawDetail] still carries the original payload.
     */
    data class Unrecognized(
        override val message: String,
        override val rawDetail: String
    ) : ServerConflict
}
