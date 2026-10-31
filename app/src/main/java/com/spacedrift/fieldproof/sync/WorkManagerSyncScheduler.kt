package com.spacedrift.fieldproof.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager-backed [SyncScheduler].
 *
 * The request is constrained to a connected network, so a device that captured
 * evidence offline keeps the work enqueued and WorkManager dispatches it as soon
 * as connectivity returns. Idempotency is handled above WorkManager: [SyncRunner]
 * derives each upload's `Idempotency-Key` from the persisted entity id, so
 * retrying or replaying a worker never creates duplicate server evidence.
 */
@Singleton
class WorkManagerSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) : SyncScheduler {

    override fun enqueueSync() {
        // APPEND_OR_REPLACE: append after an in-flight sync so it re-reads the
        // queue (and picks up newly queued work), or start fresh if the previous
        // chain already finished/cancelled.
        enqueue(ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    override fun forceRetry() {
        enqueue(ExistingWorkPolicy.REPLACE)
    }

    private fun enqueue(policy: ExistingWorkPolicy) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, policy, request())
    }

    private fun request(): OneTimeWorkRequest {
        return OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                BACKOFF_SECONDS,
                TimeUnit.SECONDS
            )
            .build()
    }

    companion object {
        const val WORK_NAME = "fieldproof-sync"
        private const val BACKOFF_SECONDS = 30L
    }
}
