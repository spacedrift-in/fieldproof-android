package com.spacedrift.fieldproof.sync

/**
 * Schedules the background sync pass that uploads locally queued operations
 * (inspections first, then their evidence).
 *
 * Implementations must enqueue *unique* work so repeated triggers (app start,
 * each newly queued operation, manual retry) collapse into one work chain rather
 * than piling up parallel syncs.
 */
interface SyncScheduler {

    /**
     * Enqueues the next sync pass. Safe to call whenever a new operation is
     * queued: if a sync is already pending it appends after it, and if one
     * finished it starts a fresh pass, so the newly queued work is never lost.
     */
    fun enqueueSync()

    /**
     * Replaces any pending sync with a fresh pass. Used by explicit user retries
     * so a stuck/backing-off worker is superseded immediately.
     */
    fun forceRetry()
}
