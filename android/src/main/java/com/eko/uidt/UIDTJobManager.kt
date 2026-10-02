package com.eko.uidt

import android.app.DownloadManager
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.job.JobWorkItem
import android.content.Intent
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.eko.utils.NetworkRequestUtils
import android.os.Build
import android.os.PersistableBundle
import androidx.annotation.RequiresApi
import com.eko.RNBackgroundDownloaderModuleImpl
import com.eko.ResumableDownloader
import com.eko.UIDTDownloadJobService
import com.eko.utils.ProgressUtils
import org.json.JSONObject

/**
 * Manages UIDT job scheduling, cancellation, pause and resume operations.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
object UIDTJobManager {

    private val config: NotificationConfig
        get() = UIDTJobRegistry.notificationConfig

    /**
     * JobScheduler refuses to hold more than this many distinct jobs per app
     * (AOSP `JobSchedulerService.MAX_JOBS_PER_APP`) and throws
     * `IllegalStateException` from `schedule()` once the quota is full.
     */
    private const val JOB_SCHEDULER_APP_LIMIT = 150

    /**
     * Slots left to the rest of the app. The quota is app-wide - WorkManager and
     * any other library scheduling jobs draw from the same pool - so a download
     * batch must not be allowed to consume all of it.
     */
    private const val JOB_SCHEDULER_HEADROOM = 30

    /**
     * Check if UIDT is available on this device.
     */
    fun isUIDTAvailable(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }

    /**
     * Snapshot of every job the app currently has scheduled, or null when it
     * can't be read - callers treat null as "unknown", not as "none".
     */
    private fun pendingJobs(jobScheduler: JobScheduler): List<JobInfo>? {
        return try {
            jobScheduler.allPendingJobs
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logW(UIDTConstants.TAG, "Could not read pending JobScheduler jobs: ${e.message}")
            null
        }
    }

    /**
     * Whether a new UIDT job can be handed to the JobScheduler without eating
     * into the app's remaining job quota. With the pending jobs unknown we return
     * true and let the guarded `schedule()` call decide.
     *
     * A download that already holds a job doesn't go through here: re-scheduling
     * replaces that job rather than adding one, so it needs no free slot.
     */
    private fun hasFreeJobSlot(pendingJobs: List<JobInfo>?): Boolean {
        if (pendingJobs == null) return true
        if (pendingJobs.size < JOB_SCHEDULER_APP_LIMIT - JOB_SCHEDULER_HEADROOM) return true

        RNBackgroundDownloaderModuleImpl.logW(
            UIDTConstants.TAG,
            "JobScheduler quota nearly exhausted (${pendingJobs.size} pending jobs, app limit $JOB_SCHEDULER_APP_LIMIT)"
        )
        return false
    }

    /**
     * Whether a download has a job with the JobScheduler that hasn't started yet -
     * e.g. one held by its unmetered-network constraint. Such a job has no entry
     * in [UIDTJobRegistry.activeJobs] (that is only filled in `onStartJob`), and
     * after a process restart the registry is empty for running jobs too, so the
     * system is the only place that knows.
     */
    fun isScheduledJob(context: Context, configId: String): Boolean {
        if (!isUIDTAvailable()) return false
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        return pendingJobs(jobScheduler)?.any { UIDTJobIds.configIdOf(it) == configId } ?: false
    }

    /**
     * Downloads that have a scheduled job but haven't started transferring, so
     * they can be surfaced alongside the running ones. Jobs already tracked in
     * [UIDTJobRegistry.activeJobs] are left out - those carry live progress and
     * are reported from there instead.
     */
    fun getScheduledJobs(context: Context): List<UIDTJobInfo> {
        if (!isUIDTAvailable()) return emptyList()

        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val pending = pendingJobs(jobScheduler)
        return queuedBatchItems(context, pending) + pending.orEmpty().mapNotNull { job ->
            val configId = UIDTJobIds.configIdOf(job) ?: return@mapNotNull null
            if (UIDTJobRegistry.isActiveJob(configId)) return@mapNotNull null

            val extras = job.extras
            // The persisted resume state can be ahead of the extras: it is
            // rewritten whenever the job is stopped and rescheduled
            val persistedBytes = UIDTJobRegistry.loadResumeState(context, configId)?.second ?: 0L

            UIDTJobInfo(
                id = configId,
                // Same status DownloadManager reports for a queued download, so a
                // download waiting to start looks the same whichever mechanism runs it
                status = DownloadManager.STATUS_PENDING,
                bytesDownloaded = maxOf(persistedBytes, extras.getLong(UIDTConstants.KEY_START_BYTE, 0)),
                bytesTotal = extras.getLong(UIDTConstants.KEY_TOTAL_BYTES, -1),
                url = extras.getString(UIDTConstants.KEY_URL) ?: "",
                destination = extras.getString(UIDTConstants.KEY_DESTINATION) ?: "",
                metadata = extras.getString(UIDTConstants.KEY_METADATA) ?: "{}"
            )
        }
    }

    /**
     * Everything needed to persist a scheduled-but-not-yet-running job as a
     * paused download after cancelling it.
     */
    data class PendingJobInfo(
        val url: String,
        val destination: String,
        val startByte: Long,
        val totalBytes: Long,
        val metadata: String,
        val isAllowedOverMetered: Boolean,
        val headers: Map<String, String>
    )

    /**
     * Cancel a UIDT job that is scheduled but not yet running - e.g. held pending
     * by its unmetered-network constraint - and return the info needed to persist
     * it as a paused download. A pending job has no entry in the registry's
     * activeJobs (that is only populated in onStartJob), so the regular pause
     * path can't see it. Returns null when the job is running or doesn't exist.
     */
    fun cancelPendingJob(context: Context, configId: String): PendingJobInfo? {
        if (!isUIDTAvailable()) return null
        if (UIDTJobRegistry.isActiveJob(configId)) return null

        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val jobId = UIDTJobIds.jobIdFor(configId, pendingJobs(jobScheduler)) ?: return null
        val pendingJob = jobScheduler.getPendingJob(jobId) ?: return null

        val extras = pendingJob.extras
        // Defensive: the ID came from this job's own extras, but never act on a
        // job that turns out to belong to another download
        if (extras.getString(UIDTConstants.KEY_DOWNLOAD_ID) != configId) return null
        val url = extras.getString(UIDTConstants.KEY_URL) ?: return null
        val destination = extras.getString(UIDTConstants.KEY_DESTINATION) ?: return null

        // Prefer the persisted resume state (more recent than the extras)
        val resumeState = UIDTJobRegistry.loadResumeState(context, configId)
        val headers = resumeState?.first
            ?: UIDTJobRegistry.pendingHeaders[configId]
            ?: emptyMap()
        val startByte = maxOf(resumeState?.second ?: 0L, extras.getLong(UIDTConstants.KEY_START_BYTE, 0))

        jobScheduler.cancel(jobId)
        UIDTJobRegistry.pendingHeaders.remove(configId)
        UIDTJobRegistry.clearResumeState(context, configId)
        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Cancelled pending UIDT job for $configId (jobId=$jobId) at byte $startByte")

        return PendingJobInfo(
            url = url,
            destination = destination,
            startByte = startByte,
            totalBytes = extras.getLong(UIDTConstants.KEY_TOTAL_BYTES, -1),
            metadata = extras.getString(UIDTConstants.KEY_METADATA) ?: "{}",
            isAllowedOverMetered = extras.getBoolean(UIDTConstants.KEY_IS_ALLOWED_OVER_METERED, true),
            headers = headers
        )
    }

    /**
     * Schedule a UIDT download job.
     *
     * @param context Application context
     * @param configId Unique download identifier
     * @param url Download URL
     * @param destination File destination path
     * @param headers HTTP headers for the request
     * @param startByte Byte position to resume from (0 for new downloads)
     * @param totalBytes Total expected bytes (-1 if unknown)
     * @param metadata JSON metadata with course info for notification grouping
     * @param isAllowedOverMetered Whether the transfer may use metered networks (cellular).
     *        When false the job requires an unmetered network, matching
     *        DownloadManager.Request.setAllowedOverMetered(false) semantics.
     * @return true if job was scheduled successfully. Returns false - never throws -
     *         when the app's JobScheduler quota leaves no room for the job, so the
     *         caller falls back to the foreground service.
     */
    fun scheduleDownload(
        context: Context,
        configId: String,
        url: String,
        destination: String,
        headers: Map<String, String>,
        startByte: Long = 0,
        totalBytes: Long = -1,
        metadata: String = "{}",
        isAllowedOverMetered: Boolean
    ): Boolean {
        if (!isUIDTAvailable()) {
            RNBackgroundDownloaderModuleImpl.logW(UIDTConstants.TAG, "UIDT requires Android 14+, falling back to foreground service")
            return false
        }

        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val pendingJobs = pendingJobs(jobScheduler)

        // A download that already has a job replaces it and needs no extra slot;
        // any other one has to fit in the quota. Bail out before touching any
        // state - the caller falls back to the foreground service.
        if (UIDTJobIds.jobIdFor(configId, pendingJobs) == null && !hasFreeJobSlot(pendingJobs)) {
            RNBackgroundDownloaderModuleImpl.logW(UIDTConstants.TAG, "No JobScheduler slot left for $configId, falling back to foreground service")
            return false
        }

        // Reserve an ID no live job and no concurrent schedule is using
        val jobId = UIDTJobIds.reserveJobId(configId, pendingJobs)
        if (jobId == null) {
            RNBackgroundDownloaderModuleImpl.logE(UIDTConstants.TAG, "No free JobScheduler job ID left for $configId, falling back to foreground service")
            return false
        }

        // A grouped download joins its batch's single job instead of taking a slot
        // of its own. Falls through to a per-download job when that is refused -
        // notably while the app is not visible, which the platform requires for
        // enqueueing user-initiated work.
        if (wantsBatching(metadata)) {
            val groupId = groupIdOf(metadata)
            if (enqueueIntoBatch(context, jobScheduler, pendingJobs, groupId, configId, url, destination, headers, startByte, totalBytes, metadata, isAllowedOverMetered)) {
                UIDTJobIds.endReservation(configId)
                UIDTJobRegistry.registerGroupFile(groupId, configId, totalBytes)
                return true
            }
        }

        try {
            return scheduleJob(context, jobScheduler, jobId, configId, url, destination, headers, startByte, totalBytes, metadata, isAllowedOverMetered)
        } finally {
            // The job is in the system now (or was rejected), so the reservation
            // has done its job either way
            UIDTJobIds.endReservation(configId)
        }
    }

    /**
     * Build and hand over the job for an already reserved ID. Split out of
     * [scheduleDownload] only so the reservation can be released in one place.
     */
    private fun scheduleJob(
        context: Context,
        jobScheduler: JobScheduler,
        jobId: Int,
        configId: String,
        url: String,
        destination: String,
        headers: Map<String, String>,
        startByte: Long,
        totalBytes: Long,
        metadata: String,
        isAllowedOverMetered: Boolean
    ): Boolean {
        // Store headers for later retrieval (PersistableBundle can't store Map<String, String>).
        // Written before scheduling, not after: onStartJob can run on the main thread
        // as soon as schedule() registers the job, and it must find the headers.
        UIDTJobRegistry.pendingHeaders[configId] = headers
        // Also persist to disk so headers survive process death and are available
        // in onStartJob even when the process is restarted by the JobScheduler.
        UIDTJobRegistry.saveResumeState(context, configId, headers, startByte)

        // Create extras bundle
        val extras = PersistableBundle().apply {
            putString(UIDTConstants.KEY_DOWNLOAD_ID, configId)
            putString(UIDTConstants.KEY_URL, url)
            putString(UIDTConstants.KEY_DESTINATION, destination)
            putLong(UIDTConstants.KEY_START_BYTE, startByte)
            putLong(UIDTConstants.KEY_TOTAL_BYTES, totalBytes)
            putString(UIDTConstants.KEY_METADATA, metadata)
            putBoolean(UIDTConstants.KEY_IS_ALLOWED_OVER_METERED, isAllowedOverMetered)
        }

        // When metered networks are not allowed, require an unmetered network so the
        // JobScheduler holds the job until Wi-Fi/ethernet is available - the same
        // behavior as DownloadManager.Request.setAllowedOverMetered(false).
        val networkRequest = NetworkRequestUtils.internetRequest(requireUnmetered = !isAllowedOverMetered)

        // Build the job with UIDT flag
        val jobInfo = JobInfo.Builder(jobId, ComponentName(context, UIDTDownloadJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetwork(networkRequest)
            .setExtras(extras)
            // Estimate network bytes for better scheduling (use 100MB as default estimate)
            .setEstimatedNetworkBytes(
                if (totalBytes > 0) totalBytes else 100 * 1024 * 1024L,
                0 // Upload bytes
            )
            .build()

        // Every rejection here is recoverable for us - the caller falls back to the
        // foreground service - but schedule() signals some of them by throwing:
        // IllegalStateException once the app is over its job quota (the check above
        // races with jobs scheduled elsewhere in the app), and SecurityException when
        // the app can't run user-initiated jobs at all, e.g. a host app that strips
        // RUN_USER_INITIATED_JOBS from the merged manifest.
        val result = try {
            jobScheduler.schedule(jobInfo)
        } catch (e: RuntimeException) {
            RNBackgroundDownloaderModuleImpl.logE(UIDTConstants.TAG, "JobScheduler rejected the UIDT job for $configId: ${e.message}")
            JobScheduler.RESULT_FAILURE
        }
        val success = result == JobScheduler.RESULT_SUCCESS

        if (success) {
            UIDTJobRegistry.registerGroupFile(groupIdOf(metadata), configId, totalBytes)
            RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Scheduled UIDT job for $configId (jobId=$jobId, isAllowedOverMetered=$isAllowedOverMetered)")
        } else {
            RNBackgroundDownloaderModuleImpl.logE(UIDTConstants.TAG, "Failed to schedule UIDT job for $configId")
            // Nothing will consume the state written above - the download runs
            // through the foreground service instead - so don't leave it on disk
            UIDTJobRegistry.pendingHeaders.remove(configId)
            UIDTJobRegistry.clearResumeState(context, configId)
        }

        return success
    }

    /**
     * The downloads still queued behind a batch job. They have no job of their own
     * to read, so they come from what [enqueueIntoBatch] recorded - kept only while
     * their batch job is alive, so a record left by a reboot is dropped rather than
     * reported as pending forever.
     */
    private fun queuedBatchItems(context: Context, pendingJobs: List<JobInfo>?): List<UIDTJobInfo> {
        val liveBatches = pendingJobs.orEmpty().mapNotNull { UIDTJobIds.batchKeyOf(it) }.toSet()

        return UIDTJobRegistry.loadBatchItems(context).mapNotNull { (configId, item) ->
            if (UIDTJobRegistry.isActiveJob(configId)) return@mapNotNull null

            val key = item.optString("groupId") to item.optBoolean("metered", true)
            if (key !in liveBatches) {
                UIDTJobRegistry.clearBatchItem(context, configId)
                return@mapNotNull null
            }

            UIDTJobInfo(
                id = configId,
                status = DownloadManager.STATUS_PENDING,
                bytesDownloaded = UIDTJobRegistry.loadResumeState(context, configId)?.second ?: 0L,
                bytesTotal = item.optLong("totalBytes", -1),
                url = item.optString("url"),
                destination = item.optString("destination"),
                metadata = item.optString("metadata", "{}"),
            )
        }
    }

    /** A batch job's size estimate is fixed, so every enqueue passes the same JobInfo. */
    private const val BATCH_ESTIMATED_BYTES = 500L * 1024 * 1024

    /**
     * Whether this download belongs to a batch. Only summaryOnly grouping asks for
     * one notification covering the group, which is what a single job gives; the
     * other modes want a notification per download, and the platform ties one
     * notification to one job.
     */
    private fun wantsBatching(metadata: String): Boolean {
        val config = UIDTJobRegistry.notificationConfig
        return groupIdOf(metadata).isNotEmpty() &&
            config.groupingEnabled &&
            config.mode == NotificationGroupingMode.SUMMARY_ONLY
    }

    /**
     * Add this download to its batch's job as a work item, creating the job on the
     * first one. Returns false when the platform refuses, leaving the caller to
     * schedule a job for this download alone.
     */
    private fun enqueueIntoBatch(
        context: Context,
        jobScheduler: JobScheduler,
        pendingJobs: List<JobInfo>?,
        groupId: String,
        configId: String,
        url: String,
        destination: String,
        headers: Map<String, String>,
        startByte: Long,
        totalBytes: Long,
        metadata: String,
        isAllowedOverMetered: Boolean,
    ): Boolean {
        val jobId = UIDTJobIds.batchJobIdFor(groupId, isAllowedOverMetered, pendingJobs) ?: return false

        UIDTJobRegistry.pendingHeaders[configId] = headers
        UIDTJobRegistry.saveResumeState(context, configId, headers, startByte)
        UIDTJobRegistry.saveBatchItem(context, configId, url, destination, totalBytes, metadata, groupId, isAllowedOverMetered)

        val work = JobWorkItem.Builder()
            .setIntent(
                Intent().apply {
                    putExtra(UIDTConstants.KEY_DOWNLOAD_ID, configId)
                    putExtra(UIDTConstants.KEY_URL, url)
                    putExtra(UIDTConstants.KEY_DESTINATION, destination)
                    putExtra(UIDTConstants.KEY_START_BYTE, startByte)
                    putExtra(UIDTConstants.KEY_TOTAL_BYTES, totalBytes)
                    putExtra(UIDTConstants.KEY_METADATA, metadata)
                    putExtra(UIDTConstants.KEY_IS_ALLOWED_OVER_METERED, isAllowedOverMetered)
                }
            )
            .setEstimatedNetworkBytes(
                if (totalBytes > 0) totalBytes else JobInfo.NETWORK_BYTES_UNKNOWN.toLong(),
                0,
            )
            .build()

        val result = try {
            jobScheduler.enqueue(batchJobInfo(context, jobId, groupId, isAllowedOverMetered), work)
        } catch (e: RuntimeException) {
            RNBackgroundDownloaderModuleImpl.logW(UIDTConstants.TAG, "Batch enqueue refused for $configId in '$groupId': ${e.message}")
            JobScheduler.RESULT_FAILURE
        }

        if (result != JobScheduler.RESULT_SUCCESS) {
            RNBackgroundDownloaderModuleImpl.logW(UIDTConstants.TAG, "Batch enqueue failed for $configId, falling back to its own job")
            UIDTJobIds.releaseBatch(groupId, isAllowedOverMetered)
            UIDTJobRegistry.clearBatchItem(context, configId)
            return false
        }

        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Enqueued $configId into batch job $jobId for group '$groupId' (isAllowedOverMetered=$isAllowedOverMetered)")
        return true
    }

    /**
     * The batch's JobInfo, built identically on every enqueue so adding work never
     * looks like a different job to the system.
     */
    private fun batchJobInfo(context: Context, jobId: Int, groupId: String, isAllowedOverMetered: Boolean): JobInfo {
        val extras = PersistableBundle().apply {
            putInt(UIDTConstants.KEY_IS_BATCH, 1)
            putString(UIDTConstants.KEY_GROUP_ID, groupId)
            putInt(UIDTConstants.KEY_IS_ALLOWED_OVER_METERED, if (isAllowedOverMetered) 1 else 0)
        }

        return JobInfo.Builder(jobId, ComponentName(context, UIDTDownloadJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetwork(NetworkRequestUtils.internetRequest(requireUnmetered = !isAllowedOverMetered))
            .setExtras(extras)
            .setEstimatedNetworkBytes(BATCH_ESTIMATED_BYTES, 0)
            .build()
    }

    /** The group a download belongs to, read from the metadata the caller passed. */
    private fun groupIdOf(metadata: String): String = try {
        JSONObject(metadata).optString("groupId", "")
    } catch (e: Exception) {
        ""
    }

    /**
     * Every download of a group the library still knows about: the ones running,
     * plus the ones queued behind a batch job, which have no job of their own.
     */
    fun downloadsInGroup(context: Context, groupId: String): List<String> {
        if (groupId.isEmpty()) return emptyList()

        val running = UIDTJobRegistry.activeJobs.entries
            .filter { it.value.groupId == groupId }
            .map { it.key }
        val queued = UIDTJobRegistry.loadBatchItems(context)
            .filterValues { it.optString("groupId") == groupId }
            .keys

        return (running + queued).distinct()
    }

    /**
     * Tear down what is left of a group once its downloads are cancelled: the batch
     * job under each metered setting, its notification, and its progress tally.
     */
    fun finishGroup(context: Context, groupId: String) {
        if (groupId.isEmpty()) return

        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val pending = pendingJobs(jobScheduler)
        for (metered in listOf(true, false)) {
            pending.orEmpty()
                .firstOrNull { UIDTJobIds.batchKeyOf(it) == (groupId to metered) }
                ?.let {
                    jobScheduler.cancel(it.id)
                    RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Cancelled batch job ${it.id} for group '$groupId'")
                }
            UIDTJobIds.releaseBatch(groupId, metered)
        }

        UIDTJobRegistry.markGroupFinalized(groupId)
        UIDTNotificationManager.cancelSummaryNotification(context, groupId)
        UIDTJobRegistry.clearGroupProgress(groupId)
    }

    /**
     * Cancel one download of a batch. The job and its notification belong to the
     * whole group, so neither is touched; the work item is retired by hand because
     * a cancelled download reports to no listener.
     */
    private fun cancelBatchItem(context: Context, configId: String, jobState: JobState): Boolean {
        jobState.resumableDownloader.cancel(configId)

        UIDTJobRegistry.activeJobs.remove(configId)
        UIDTJobRegistry.forgetGroupFile(jobState.groupId, configId)
        UIDTJobRegistry.pendingHeaders.remove(configId)
        UIDTJobRegistry.clearResumeState(context, configId)
        UIDTJobRegistry.clearBatchItem(context, configId)

        jobState.workItem?.let { UIDTJobRegistry.serviceInstance?.completeBatchItem(jobState.params, it) }
        UIDTNotificationManager.updateSummaryNotificationForGroup(context, jobState.groupId, jobState.groupName)

        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Cancelled batch item $configId in group '${jobState.groupId}'")
        return true
    }

    /**
     * Cancel a scheduled UIDT job.
     *
     * @return `true` if a live (active) job was found and cancelled, `false`
     * if there was nothing to cancel (e.g. the download already completed).
     * Callers can use this to avoid dispatching a spurious downloadFailed event.
     */
    fun cancelJob(context: Context, configId: String): Boolean {
        if (!isUIDTAvailable()) return false

        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "cancelJob called for $configId")

        // Get job state before removing (for notification cleanup)
        val jobState = UIDTJobRegistry.activeJobs[configId]
        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "cancelJob: jobState=$jobState")

        if (jobState?.workItem != null) return cancelBatchItem(context, configId, jobState)

        if (jobState != null) {
            // First cancel the download in the ResumableDownloader (stops the actual HTTP download)
            jobState.resumableDownloader.cancel(configId)
            RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Cancelled ResumableDownloader for $configId")

            // Use setNotification with REMOVE policy to properly dismiss the notification
            val service = UIDTJobRegistry.serviceInstance
            if (service != null) {
                RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Using setNotification with REMOVE policy for $configId")
                val emptyNotification = UIDTNotificationManager.createEmptyNotification(context)
                service.setNotification(
                    jobState.params,
                    jobState.notificationId,
                    emptyNotification,
                    JobService.JOB_END_NOTIFICATION_POLICY_REMOVE
                )
                RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Calling jobFinished for $configId")
                service.jobFinished(jobState.params, false)
            }

            // Also cancel via NotificationManager as a fallback
            UIDTNotificationManager.cancelNotification(context, jobState.notificationId)
            // Its notification is gone, so the ID offset can go back to the pool
            UIDTNotificationIds.release(configId)
            RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Cancelled notification ${jobState.notificationId} for $configId")
        }

        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val pending = pendingJobs(jobScheduler)
        if (jobState == null && pending != null && UIDTJobIds.jobIdFor(configId, pending) == null) {
            // No job of its own: it is either gone, or still queued behind a batch
            // job. A work item cannot be pulled out of the system's queue, so mark
            // it and let the service skip it when its turn comes. Cancelling the
            // legacy ID here would take down whichever job happens to hold it.
            RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "No own job for $configId; marking it cancelled")
            UIDTJobRegistry.markCancelled(configId)
            UIDTJobRegistry.pendingHeaders.remove(configId)
            UIDTJobRegistry.clearResumeState(context, configId)
            return true
        }
        jobScheduler.cancel(UIDTJobIds.jobIdToCancel(configId, pending))
        UIDTJobRegistry.pendingHeaders.remove(configId)
        UIDTJobRegistry.activeJobs.remove(configId)
        // Clear persisted resume state so stale headers/bytes don't affect future downloads
        UIDTJobRegistry.clearResumeState(context, configId)

        // Update summary notification if grouping was enabled
        if (jobState != null && config.groupingEnabled && jobState.groupId.isNotEmpty()) {
            UIDTNotificationManager.updateSummaryNotification(context, jobState.groupId, jobState.groupName)
        }

        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Cancelled UIDT job for $configId")
        return jobState != null
    }

    /**
     * Pause an active UIDT download.
     * This will pause the download, cancel the UIDT job, but keep a detached notification showing paused state.
     * The download state is persisted and can be resumed later by creating a new UIDT job.
     */
    fun pauseJob(context: Context, configId: String): Boolean {
        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "pauseJob called: configId=$configId, showNotificationsEnabled=${config.showNotificationsEnabled}")
        val jobState = UIDTJobRegistry.activeJobs[configId] ?: return false

        // First pause the actual download (this saves state to disk)
        val result = jobState.resumableDownloader.pause(configId)
        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "pauseJob: pause result=$result")

        if (result) {
            // Get progress before removing job state
            val downloadState = jobState.resumableDownloader.getState(configId)
            val bytesDownloaded = downloadState?.bytesDownloaded?.get() ?: 0L
            val bytesTotal = downloadState?.bytesTotal ?: -1L
            val progress = ProgressUtils.calculateProgress(bytesDownloaded, bytesTotal)
            val groupName = jobState.groupName
            val notificationId = UIDTNotificationManager.getNotificationIdForConfig(configId)

            // Call jobFinished to properly end the UIDT job
            // Note: jobFinished(params, false) is sufficient - no need for jobScheduler.cancel()
            // since wantsReschedule=false tells the system the job is complete and should not be rescheduled
            val service = UIDTJobRegistry.serviceInstance
            if (service != null) {
                RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Calling jobFinished for paused job $configId")
                service.jobFinished(jobState.params, false)
            }

            // Remove from activeJobs - will create new job on resume
            UIDTJobRegistry.activeJobs.remove(configId)

            // Show detached paused notification (not tied to UIDT job)
            if (config.showNotificationsEnabled) {
                UIDTNotificationManager.showPausedNotification(context, configId, notificationId, progress, groupName)
            }
        }
        return result
    }

    /**
     * Resume a paused UIDT download.
     */
    fun resumeJob(context: Context, configId: String, listener: ResumableDownloader.DownloadListener): Boolean {
        val jobState = UIDTJobRegistry.activeJobs[configId] ?: return false
        UIDTJobRegistry.downloadListener = listener

        // Reset notification timing to allow immediate updates after resume
        jobState.lastNotificationUpdateTime = 0L

        // Update notification to show resuming/downloading state
        if (config.showNotificationsEnabled) {
            UIDTNotificationManager.updateResumedNotification(context, jobState)
        }

        return jobState.resumableDownloader.resume(configId, listener)
    }

    /**
     * Configure notification settings.
     */
    fun setNotificationConfig(
        enabled: Boolean,
        showNotifications: Boolean,
        showCompletionNotification: Boolean,
        showCancelAction: Boolean,
        mode: String,
        texts: Map<String, String>
    ) {
        config.groupingEnabled = enabled
        config.showNotificationsEnabled = showNotifications
        config.showCompletionNotification = showCompletionNotification
        config.showCancelAction = showCancelAction
        config.mode = when (mode) {
            "summaryOnly" -> NotificationGroupingMode.SUMMARY_ONLY
            else -> NotificationGroupingMode.INDIVIDUAL
        }
        config.updateTexts(texts)
        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Notification config updated: grouping=$enabled, showNotificationsEnabled=$showNotifications, showCompletionNotification=$showCompletionNotification, showCancelAction=$showCancelAction, mode=${config.mode}, texts=$texts")
    }

    /**
     * Set notification update interval (should match progressInterval).
     */
    fun setNotificationUpdateInterval(interval: Long) {
        config.updateInterval = interval
        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Notification update interval set to: $interval ms")
    }

    /**
     * Check if notifications are enabled globally.
     */
    fun isNotificationsEnabled(): Boolean = config.showNotificationsEnabled
}
