// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
package com.serenvox.app.tts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.serenvox.app.Prefs
import com.serenvox.app.R
import com.serenvox.app.SerenvoxApp
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Downloads one voice as foreground (dataSync) work so it keeps going when the app is in the
 * background. Requires a network connection; network failures pause and retry (resuming from the
 * .part file), hard errors fail with a friendly message in the output data.
 */
class VoiceDownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val voiceId = inputData.getString(KEY_ID).orEmpty()
    private val notifId = 4200 + (voiceId.hashCode() and 0xff)
    private val app get() = applicationContext as SerenvoxApp

    override suspend fun getForegroundInfo() = foregroundInfo(0, 0, 0f)

    override suspend fun doWork(): Result {
        val v = VoiceCatalog.byId(voiceId) ?: return Result.failure()
        val vm = app.voices
        var lastNotified = -1
        try {
            runCatching { setForeground(foregroundInfo(0, 0, vm.partialProgress(v))) }
                .onFailure { Log.w(TAG, "setForeground failed; continuing as expedited work", it) }
            vm.setState(v.id, VoiceState.Downloading(vm.partialProgress(v)))
            vm.runDownload(v) { p, done, total ->
                vm.setState(v.id, VoiceState.Downloading(p, false, done, total))
                val pct = (p * 100).toInt()
                if (pct != lastNotified) {
                    lastNotified = pct
                    notifications().notify(notifId, foregroundInfo(done, total, p).notification)
                }
            }
            if (app.prefs.voice.value == Prefs.SYSTEM_VOICE) app.prefs.setVoice(v.id)
            return Result.success()
        } catch (e: CancellationException) {
            throw e // stopped: handled by the WorkInfo observer (user cancel vs. system stop)
        } catch (e: Exception) {
            Log.w(TAG, "Voice download error (${v.id}, attempt $runAttemptCount)", e)
            if (Downloader.isRetryable(e) && runAttemptCount < MAX_ATTEMPTS) {
                vm.setState(v.id, VoiceState.Downloading(vm.partialProgress(v), waiting = true))
                return Result.retry()
            }
            val msg = Downloader.friendlyMessage(e)
            vm.setState(v.id, VoiceState.Failed(msg))
            return Result.failure(workDataOf(KEY_ERROR to msg))
        }
    }

    private fun notifications(): NotificationManager {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Voice downloads", NotificationManager.IMPORTANCE_LOW))
        return nm
    }

    private fun foregroundInfo(done: Long, total: Long, p: Float): ForegroundInfo {
        notifications()
        val name = VoiceCatalog.byId(voiceId)?.name ?: "voice"
        val pct = (p * 100).toInt()
        val text = if (total > 0) "$pct% · ${done / 1_000_000} of ${total / 1_000_000} MB" else "$pct%"
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Downloading $name")
            .setContentText(text)
            .setProgress(100, pct, total <= 0 && pct == 0)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .addAction(0, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(notifId, n)
    }

    companion object {
        private const val TAG = "Serenvox"
        const val KEY_ID = "voiceId"; const val KEY_PROGRESS = "progress"; const val KEY_ERROR = "error"
        const val TAG_ALL = "voice-download"
        private const val CHANNEL = "downloads"
        const val MAX_ATTEMPTS = 12
        fun workName(id: String) = "voice-download-$id"

        fun enqueue(ctx: Context, id: String) {
            val req = OneTimeWorkRequestBuilder<VoiceDownloadWorker>()
                .setInputData(workDataOf(KEY_ID to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .addTag(TAG_ALL).addTag("voice:$id")
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(ctx: Context, id: String) { WorkManager.getInstance(ctx).cancelUniqueWork(workName(id)) }

        /** Voice id carried in a WorkInfo's tags. */
        fun idOf(info: WorkInfo) = info.tags.firstOrNull { it.startsWith("voice:") }?.removePrefix("voice:")
    }
}
