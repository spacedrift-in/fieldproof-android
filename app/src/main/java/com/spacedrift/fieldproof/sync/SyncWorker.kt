package com.spacedrift.fieldproof.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val runner: SyncRunner
) : CoroutineWorker(
    context,
    workerParams
) {

    override suspend fun doWork(): Result {
        return when (runner.run()) {
            is SyncRunResult.AllDone -> Result.success()

            is SyncRunResult.Retry -> Result.retry()
        }
    }
}