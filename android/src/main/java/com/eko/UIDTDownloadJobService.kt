package com.eko

import android.app.job.JobParameters
import android.app.job.JobService
import android.app.job.JobWorkItem
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import com.eko.uidt.JobState
import com.eko.uidt.NotificationGroupingMode
import com.eko.uidt.UIDTConstants
import com.eko.uidt.UIDTJobInfo
import com.eko.uidt.UIDTJobManager
import com.eko.uidt.UIDTJobRegistry
import com.eko.uidt.UIDTNotificationIds
import com.eko.uidt.UIDTNotificationManager
import com.eko.utils.ProgressUtils
import org.json.JSONObject

/**
 * A JobService that uses User-Initiated Data Transfer (UIDT) for background downloads.
 *
 * UIDT was introduced in Android 14 (API 34) and is required for reliable background
 * downloads on Android 16+ where foreground services are more restricted.
 *
 * Key benefits of UIDT:
 * - Not affected by App Standby Buckets quotas
 * - Can run for extended periods as system conditions allow
 * - Shows in Task Manager for user visibility
 * - Properly handles thermal throttling and system health restrictions
 *
 * This class handles only the JobService lifecycle. Job management, notifications,
 * and state are delegated to:
 * - [UIDTJobManager] - Job scheduling, cancellation, pause/resume
 * - [UIDTNotificationManager] - Notification creation and updates
 * - [UIDTJobRegistry] - Active job state tracking
 *
 * @see <a href="https://developer.android.com/develop/background-work/background-tasks/uidt">UIDT Documentation</a>
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class UIDTDownloadJobService : JobService() {

    companion object {
        // Delegate static methods to UIDTJobManager and UIDTJobRegistry for backward compatibility

        /**
         * Schedule a UIDT download job.
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
        ): Boolean = UIDTJobManager.scheduleDownload(context, configId, url, destination, headers, startByte, totalBytes, metadata, isAllowedOverMetered)

        /**
         * Cancel a scheduled UIDT job.
         */
        fun cancelJob(context: Context, configId: String) = UIDTJobManager.cancelJob(context, configId)

        /**
         * Check if a download is active as a UIDT job.
         */
        fun isActiveJob(configId: String): Boolean = UIDTJobRegistry.isActiveJob(configId)

        /**
         * Check if a download is paused in a UIDT job.
         */
        fun isPausedJob(configId: String): Boolean = UIDTJobRegistry.isPausedJob(configId)

        /**
         * Get the download state for a UIDT job.
         */
        fun getJobDownloadState(configId: String): ResumableDownloader.DownloadState? = UIDTJobRegistry.getJobDownloadState(configId)

        /**
         * Get the metered-network permission of an active UIDT job (null if not active).
         */
        fun getJobIsAllowedOverMetered(configId: String): Boolean? = UIDTJobRegistry.getJobIsAllowedOverMetered(configId)

        /**
         * Pause an active UIDT download.
         */
        fun pauseJob(context: Context, configId: String): Boolean = UIDTJobManager.pauseJob(context, configId)

        /**
         * Cancel a scheduled-but-not-running UIDT job (e.g. held pending by its
         * unmetered-network constraint) and return its persistable info.
         */
        fun cancelPendingJob(context: Context, configId: String): UIDTJobManager.PendingJobInfo? =
            UIDTJobManager.cancelPendingJob(context, configId)

        /**
         * Resume a paused UIDT download.
         */
        fun resumeJob(context: Context, configId: String, listener: ResumableDownloader.DownloadListener): Boolean =
            UIDTJobManager.resumeJob(context, configId, listener)

        /**
         * Configure notification grouping and texts.
         */
        fun setNotificationGroupingConfig(
            enabled: Boolean,
            showNotificationsEnabled: Boolean,
            showCompletionNotification: Boolean,
            showCancelAction: Boolean,
            mode: String,
            texts: Map<String, String>
        ) = UIDTJobManager.setNotificationConfig(
            enabled,
            showNotificationsEnabled,
            showCompletionNotification,
            showCancelAction,
            mode,
            texts
        )

        /**
         * Set notification update interval.
         */
        fun setNotificationUpdateInterval(interval: Long) = UIDTJobManager.setNotificationUpdateInterval(interval)

        /**
         * Check if notifications are enabled globally.
         */
        fun isNotificationsEnabled(): Boolean = UIDTJobManager.isNotificationsEnabled()

        /**
         * Cancel notification for a specific download.
         */
        fun cancelNotification(context: Context, configId: String) = UIDTNotificationManager.cancelNotification(context, configId)

        /**
         * Returns a snapshot of all currently active UIDT jobs.
         * Used by the module to populate getExistingDownloads on Android 14+.
         */
        fun getAllActiveJobs(): List<UIDTJobInfo> = UIDTJobRegistry.getAllActiveJobs()

        /**
         * Returns the downloads whose job is scheduled but hasn't started yet.
         * These have no registry entry, so they are invisible to [getAllActiveJobs].
         */
        fun getScheduledJobs(context: Context): List<UIDTJobInfo> = UIDTJobManager.getScheduledJobs(context)

        /**
         * Whether a download has a job with the JobScheduler, running or not yet started.
         */
        fun isScheduledJob(context: Context, configId: String): Boolean =
            UIDTJobManager.isScheduledJob(context, configId)

        /**
         * Set download listener.
         */
        var downloadListener: ResumableDownloader.DownloadListener?
            get() = UIDTJobRegistry.downloadListener
            set(value) { UIDTJobRegistry.downloadListener = value }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        UIDTJobRegistry.serviceInstance = this
        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "UIDTDownloadJobService created")
        UIDTNotificationManager.createNotificationChannels(this)
    }

    override fun onDestroy() {
        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "UIDTDownloadJobService destroyed")
        UIDTJobRegistry.serviceInstance = null
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onStartJob(params: JobParameters): Boolean {
        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "onStartJob called")

        if (params.extras.getInt(UIDTConstants.KEY_IS_BATCH, 0) == 1) return startBatchJob(params)

        val extras = params.extras
        val configId = extras.getString(UIDTConstants.KEY_DOWNLOAD_ID) ?: run {
            RNBackgroundDownloaderModuleImpl.logE(UIDTConstants.TAG, "No download ID in job extras")
            return false
        }
        val url = extras.getString(UIDTConstants.KEY_URL) ?: return false
        val destination = extras.getString(UIDTConstants.KEY_DESTINATION) ?: return false
        val startByteFromExtras = extras.getLong(UIDTConstants.KEY_START_BYTE, 0)
        val totalBytes = extras.getLong(UIDTConstants.KEY_TOTAL_BYTES, -1)
        val isAllowedOverMetered = extras.getBoolean(UIDTConstants.KEY_IS_ALLOWED_OVER_METERED, true)

        // Extract group info from metadata (for notification grouping)
        val metadataJson = extras.getString(UIDTConstants.KEY_METADATA) ?: "{}"
        var groupId = ""
        var groupName = ""
        var customTitle = ""
        var tapUrl = ""
        var groupTapUrl = ""
        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "onStartJob: configId=$configId, metadataJson=$metadataJson")
        try {
            val json = JSONObject(metadataJson)
            groupId = json.optString("groupId", "")
            groupName = json.optString("groupName", "")
            customTitle = json.optString("notificationTitle", "")
            tapUrl = json.optString("tapUrl", "")
            groupTapUrl = json.optString("groupTapUrl", "")
            RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Parsed metadata: groupId='$groupId', groupName='$groupName', customTitle='$customTitle'")
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logE(UIDTConstants.TAG, "Failed to parse metadata: ${e.message}")
        }

        // Re-enrol in the group: the tally lives in memory, so a job the system
        // restarts after process death finds none.
        UIDTJobRegistry.registerGroupFile(groupId, configId, totalBytes)

        // Resolve headers and start byte.
        // In-memory pendingHeaders is populated in the same process (scheduleDownload / onStopJob).
        // The disk-persisted resume state is the fallback for a fresh process after a restart.
        val inMemoryHeaders = UIDTJobRegistry.pendingHeaders.remove(configId)
        val diskResumeState = UIDTJobRegistry.loadResumeState(this, configId)
        // Clear disk state now that we've consumed it.
        UIDTJobRegistry.clearResumeState(this, configId)

        val headers: Map<String, String>
        val startByte: Long
        when {
            inMemoryHeaders != null -> {
                // Same-process restart: use in-memory headers.
                // Prefer the disk byte position if it is more advanced than the extras
                // (written by onStopJob with the actual download offset).
                headers = inMemoryHeaders
                startByte = if (diskResumeState != null && diskResumeState.second > startByteFromExtras)
                    diskResumeState.second else startByteFromExtras
            }
            diskResumeState != null -> {
                // Cross-process restart: use the persisted state.
                headers = diskResumeState.first
                startByte = diskResumeState.second
            }
            else -> {
                headers = emptyMap()
                startByte = startByteFromExtras
            }
        }
        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "onStartJob: configId=$configId, startByte=$startByte, headers=${headers.size}")

        // Create notification for UIDT job (required)
        // Every job of a group shares one notification id, which is how the platform
        // collapses them: "If separate jobs use the same notification ID, the most
        // recently provided notification will be shown to the user".
        val sharesGroupNotification = UIDTNotificationManager.sharesGroupNotification(groupId)
        val notificationId = if (sharesGroupNotification)
            UIDTNotificationManager.getNotificationIdForGroup(groupId)
        else
            UIDTNotificationManager.getNotificationIdForConfig(configId)
        // Built without the groupId when shared, so the one notification left is the
        // visible one rather than the blank placeholder summaryOnly gives each job.
        val notification = UIDTNotificationManager.createDownloadNotification(
            this,
            configId,
            if (sharesGroupNotification) "" else groupId,
            groupName,
            customTitle,
            if (sharesGroupNotification) groupTapUrl else tapUrl
        )

        // Set the notification for this job (required for UIDT)
        setNotification(params, notificationId, notification, JOB_END_NOTIFICATION_POLICY_DETACH)

        // Acquire wake lock
        acquireWakeLock()

        // Create ResumableDownloader for this job
        val resumableDownloader = ResumableDownloader()

        // Unmark group as finalized if it was previously (in case of new batch with same groupId)
        if (groupId.isNotEmpty()) {
            UIDTJobRegistry.unmarkGroupFinalized(groupId)
        }

        // Store job state BEFORE updating summary (so count includes this job)
        UIDTJobRegistry.activeJobs[configId] = JobState(params, resumableDownloader, notificationId, groupId, groupName, customTitle, tapUrl, groupTapUrl)

        // Update summary notification if grouping enabled (now includes new job in count)
        UIDTNotificationManager.updateSummaryNotificationForGroup(this, groupId, groupName)

        // Create listener that will notify completion
        val jobListener = createJobListener(configId, params, groupId, groupName, customTitle)

        // Start the download asynchronously. The network constraint is enforced by
        // the JobScheduler; the flag is passed so the DownloadState stays truthful
        // (it is a fallback source for pause/snapshot persistence in the module).
        // Bind the transfer to the network that satisfied the job's constraint -
        // otherwise the sockets use the DEFAULT network, which can be metered
        // cellular even while the satisfying unmetered network is connected.
        resumableDownloader.startDownload(
            id = configId,
            url = url,
            destination = destination,
            headers = headers,
            listener = jobListener,
            startByte = startByte,
            totalBytes = totalBytes,
            isAllowedOverMetered = isAllowedOverMetered,
            network = params.network
        )

        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Started UIDT download: $configId from byte $startByte")

        // Return true - work is being done asynchronously
        return true
    }

    /**
     * Retire one item of a batch and move on to the next. Used when a download is
     * cancelled, which the downloader reports to no listener.
     */
    fun completeBatchItem(params: JobParameters, item: JobWorkItem) {
        try {
            params.completeWork(item)
        } catch (error: Exception) {
            RNBackgroundDownloaderModuleImpl.logW(UIDTConstants.TAG, "completeWork refused for a cancelled item: ${error.message}")
        }
        val groupId = params.extras.getString(UIDTConstants.KEY_GROUP_ID) ?: return
        pumpBatchWork(params, groupId, params.extras.getInt(UIDTConstants.KEY_IS_ALLOWED_OVER_METERED, 1) == 1)
    }

    /**
     * Run a whole group off one job. The platform requires a user-initiated job to
     * carry a notification, so one job per file meant one notification per file and
     * N slots of the app's 150-job quota; a job with a queue of work items costs
     * one of each, whatever the batch size.
     */
    private fun startBatchJob(params: JobParameters): Boolean {
        val groupId = params.extras.getString(UIDTConstants.KEY_GROUP_ID) ?: run {
            RNBackgroundDownloaderModuleImpl.logE(UIDTConstants.TAG, "Batch job without a group id")
            return false
        }
        val isAllowedOverMetered = params.extras.getInt(UIDTConstants.KEY_IS_ALLOWED_OVER_METERED, 1) == 1

        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Batch job started for group '$groupId' (isAllowedOverMetered=$isAllowedOverMetered)")
        UIDTJobRegistry.unmarkGroupFinalized(groupId)
        acquireWakeLock()

        setNotification(
            params,
            UIDTNotificationManager.getNotificationIdForGroup(groupId),
            UIDTNotificationManager.createBatchNotification(this, groupId),
            JOB_END_NOTIFICATION_POLICY_REMOVE,
        )

        pumpBatchWork(params, groupId, isAllowedOverMetered)
        return true
    }

    /**
     * Take every work item the system is holding and start it. Called again after
     * each one finishes, which is what picks up downloads enqueued while the job
     * was already running - those bring no second onStartJob.
     */
    private fun pumpBatchWork(params: JobParameters, groupId: String, isAllowedOverMetered: Boolean) {
        while (true) {
            val item = try {
                params.dequeueWork()
            } catch (error: Exception) {
                RNBackgroundDownloaderModuleImpl.logW(UIDTConstants.TAG, "dequeueWork refused for group '$groupId': ${error.message}")
                null
            } ?: return

            if (!startBatchWorkItem(params, item, groupId, isAllowedOverMetered)) {
                try {
                    params.completeWork(item)
                } catch (error: Exception) {
                    RNBackgroundDownloaderModuleImpl.logW(UIDTConstants.TAG, "completeWork refused while skipping an item: ${error.message}")
                }
            }
        }
    }

    /**
     * Start the download one work item describes. Returns false when there is
     * nothing to run - a malformed item, or one the app cancelled before its turn
     * came - so the caller can retire it.
     */
    private fun startBatchWorkItem(
        params: JobParameters,
        item: JobWorkItem,
        groupId: String,
        isAllowedOverMetered: Boolean,
    ): Boolean {
        val intent = item.intent ?: return false
        val configId = intent.getStringExtra(UIDTConstants.KEY_DOWNLOAD_ID) ?: return false
        val url = intent.getStringExtra(UIDTConstants.KEY_URL) ?: return false
        val destination = intent.getStringExtra(UIDTConstants.KEY_DESTINATION) ?: return false

        if (UIDTJobRegistry.isCancelled(configId)) {
            RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Skipping $configId: cancelled before its turn in group '$groupId'")
            UIDTJobRegistry.forgetGroupFile(groupId, configId)
            UIDTJobRegistry.clearCancelled(configId)
            return false
        }

        val totalBytes = intent.getLongExtra(UIDTConstants.KEY_TOTAL_BYTES, -1)
        val metadataJson = intent.getStringExtra(UIDTConstants.KEY_METADATA) ?: "{}"
        var groupName = ""
        var customTitle = ""
        var tapUrl = ""
        var groupTapUrl = ""
        try {
            val json = JSONObject(metadataJson)
            groupName = json.optString("groupName", "")
            customTitle = json.optString("notificationTitle", "")
            tapUrl = json.optString("tapUrl", "")
            groupTapUrl = json.optString("groupTapUrl", "")
        } catch (error: Exception) {
            RNBackgroundDownloaderModuleImpl.logE(UIDTConstants.TAG, "Failed to parse metadata of $configId: ${error.message}")
        }

        val persisted = UIDTJobRegistry.loadResumeState(this, configId)
        val headers = persisted?.first ?: UIDTJobRegistry.pendingHeaders[configId] ?: emptyMap()
        val startByte = persisted?.second ?: intent.getLongExtra(UIDTConstants.KEY_START_BYTE, 0)
        UIDTJobRegistry.clearResumeState(this, configId)

        UIDTJobRegistry.registerGroupFile(groupId, configId, totalBytes)

        val resumableDownloader = ResumableDownloader()
        // Every job of the batch shares one notification, which the group's own id
        // owns - an item never posts or removes one of its own.
        UIDTJobRegistry.activeJobs[configId] = JobState(
            params,
            resumableDownloader,
            UIDTNotificationManager.getNotificationIdForGroup(groupId),
            groupId,
            groupName,
            customTitle,
            tapUrl,
            groupTapUrl,
            item,
        )

        UIDTNotificationManager.updateSummaryNotificationForGroup(this, groupId, groupName)

        resumableDownloader.startDownload(
            id = configId,
            url = url,
            destination = destination,
            headers = headers,
            listener = createJobListener(configId, params, groupId, groupName, customTitle, item, isAllowedOverMetered),
            startByte = startByte,
            totalBytes = totalBytes,
            isAllowedOverMetered = isAllowedOverMetered,
            network = params.network,
        )

        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Started batch item $configId in group '$groupId' from byte $startByte")
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val extras = params.extras
        val configId = extras.getString(UIDTConstants.KEY_DOWNLOAD_ID)

        val stopReason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.stopReason
        } else {
            -1
        }

        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "onStopJob called for $configId, reason: $stopReason")

        // A batch job holds several downloads, and none of them is named in the job
        // extras: pause every one it is running so each resumes where it stopped.
        // Returning true hands the uncompleted work items back for the next run.
        if (params.extras.getInt(UIDTConstants.KEY_IS_BATCH, 0) == 1) {
            val groupId = params.extras.getString(UIDTConstants.KEY_GROUP_ID)
            UIDTJobRegistry.activeJobs.entries
                .filter { it.value.groupId == groupId }
                .forEach { (id, jobState) -> pauseForReschedule(id, jobState) }
            releaseWakeLock()
            return true
        }

        if (configId != null) {
            val jobState = UIDTJobRegistry.activeJobs[configId]
            if (jobState != null) {
                // Pause the download - it can be resumed later
                jobState.resumableDownloader.pause(configId)

                // Save state for potential resume
                val state = jobState.resumableDownloader.getState(configId)
                if (state != null) {
                    val bytesDownloaded = state.bytesDownloaded.get()
                    // Keep in-memory headers so the same-process reschedule works.
                    UIDTJobRegistry.pendingHeaders[configId] = state.headers
                    // Persist to disk so a fresh process can resume from the right position.
                    UIDTJobRegistry.saveResumeState(this, configId, state.headers, bytesDownloaded)
                    RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "onStopJob: saved resume state for $configId at $bytesDownloaded bytes")
                }
            }
            UIDTJobRegistry.activeJobs.remove(configId)
        }

        releaseWakeLock()

        // Return true to reschedule the job if it was stopped by system
        return true
    }

    /** Save a download's position and drop it from the registry, ready to resume. */
    private fun pauseForReschedule(configId: String, jobState: com.eko.uidt.JobState) {
        jobState.resumableDownloader.pause(configId)
        jobState.resumableDownloader.getState(configId)?.let { state ->
            val bytesDownloaded = state.bytesDownloaded.get()
            UIDTJobRegistry.pendingHeaders[configId] = state.headers
            UIDTJobRegistry.saveResumeState(this, configId, state.headers, bytesDownloaded)
            RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "onStopJob: saved resume state for $configId at $bytesDownloaded bytes")
        }
        UIDTJobRegistry.activeJobs.remove(configId)
    }

    private fun createJobListener(
        configId: String,
        params: JobParameters,
        groupId: String,
        groupName: String,
        customTitle: String = "",
        // Set when the transfer is one item of a batch job: finishing it completes
        // that item and asks for the next, instead of ending the job for everyone.
        workItem: JobWorkItem? = null,
        isAllowedOverMetered: Boolean = true,
    ): ResumableDownloader.DownloadListener {
        fun finishWork() {
            if (workItem == null) {
                jobFinished(params, false)
                return
            }
            try {
                params.completeWork(workItem)
            } catch (error: Exception) {
                RNBackgroundDownloaderModuleImpl.logW(UIDTConstants.TAG, "completeWork refused for $configId: ${error.message}")
            }
            pumpBatchWork(params, groupId, isAllowedOverMetered)
        }

        return object : ResumableDownloader.DownloadListener {
            override fun onBegin(id: String, expectedBytes: Long, headers: Map<String, String>) {
                RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "UIDT download begin: $id, expectedBytes: $expectedBytes")

                // Update notification with size info
                val jobState = UIDTJobRegistry.activeJobs[id]
                if (jobState != null) {
                    // Store total bytes for summary progress calculation
                    jobState.bytesTotal = expectedBytes
                    UIDTNotificationManager.updateProgressNotification(this@UIDTDownloadJobService, id, jobState, 0, expectedBytes)
                }

                // Notify external listener
                UIDTJobRegistry.downloadListener?.onBegin(id, expectedBytes, headers)
            }

            override fun onProgress(id: String, bytesDownloaded: Long, bytesTotal: Long) {
                val jobState = UIDTJobRegistry.activeJobs[id]
                if (jobState != null) {
                    // Always update progress tracking in JobState for summary calculation
                    jobState.bytesDownloaded = bytesDownloaded
                    if (bytesTotal > 0) {
                        jobState.bytesTotal = bytesTotal
                    }

                    // Check if download is paused - don't update notification with progress
                    val isPaused = jobState.resumableDownloader.isPaused(id)
                    if (isPaused) {
                        // Still notify external listener for state tracking, but don't update notification
                        UIDTJobRegistry.downloadListener?.onProgress(id, bytesDownloaded, bytesTotal)
                        return
                    }

                    val config = UIDTJobRegistry.notificationConfig
                    val isSummaryOnlyMode = config.mode == NotificationGroupingMode.SUMMARY_ONLY

                    if (bytesTotal > 0) {
                        val progress = ProgressUtils.calculateProgress(bytesDownloaded, bytesTotal)
                        val currentTime = System.currentTimeMillis()

                        val shouldUpdate = ProgressUtils.shouldUpdateProgress(
                            progress,
                            jobState.lastNotifiedProgress,
                            currentTime,
                            jobState.lastNotificationUpdateTime,
                            config.updateInterval
                        )

                        if (shouldUpdate) {
                            jobState.lastNotifiedProgress = progress
                            jobState.lastNotificationUpdateTime = currentTime

                            if (isSummaryOnlyMode && config.groupingEnabled && jobState.groupId.isNotEmpty()) {
                                // In summaryOnly mode, update only the summary notification
                                UIDTNotificationManager.updateSummaryNotificationForGroup(
                                    this@UIDTDownloadJobService, jobState.groupId, jobState.groupName
                                )
                            } else {
                                // Update individual notification
                                UIDTNotificationManager.updateProgressNotification(
                                    this@UIDTDownloadJobService, id, jobState, bytesDownloaded, bytesTotal
                                )
                            }
                        }
                    }
                }

                // Notify external listener (always - JS handles its own throttling)
                UIDTJobRegistry.downloadListener?.onProgress(id, bytesDownloaded, bytesTotal)
            }

            override fun onComplete(id: String, location: String, bytesDownloaded: Long, bytesTotal: Long) {
                RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "UIDT download complete: $id, groupId=$groupId")

                val jobState = UIDTJobRegistry.activeJobs[id]
                val notificationId = jobState?.notificationId

                RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "onComplete: notificationId=$notificationId, jobState groupId=${jobState?.groupId}")

                // Resolve a display name for the completion notification.
                // Prefer metadata.fileName (set from JS), fall back to the
                // saved file's basename.
                val displayName = try {
                    val metadataStr = params.extras.getString(UIDTConstants.KEY_METADATA) ?: "{}"
                    val metadata = JSONObject(metadataStr)
                    metadata.optString("fileName", "").ifEmpty { location.substringAfterLast('/') }
                } catch (e: Exception) {
                    location.substringAfterLast('/')
                }

                // Post a standalone "download complete" notification BEFORE
                // we tear down the UIDT-controlled one so it persists. Tap
                // opens the saved file via FileProvider (system file viewer).
                UIDTNotificationManager.showFinishedNotification(
                    this@UIDTDownloadJobService,
                    id,
                    location,
                    displayName,
                    jobState?.groupId ?: groupId,
                    jobState?.customTitle ?: customTitle,
                    jobState?.tapUrl ?: "",
                )

                // Clean up - remove from activeJobs first
                UIDTJobRegistry.activeJobs.remove(id)
                UIDTJobRegistry.markFileCompleted(groupId, id)
                releaseWakeLock()

                // The group is over when every file it enrolled is done, not when no
                // job is live: files whose job has not started yet hold no live job.
                val groupIsOver = groupId.isNotEmpty() && UIDTJobRegistry.isGroupComplete(groupId)
                val remainingInGroup = UIDTJobRegistry.groupProgress[groupId]?.pendingFiles ?: 0
                RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Files left in group '$groupId': $remainingInGroup, activeJobs count=${UIDTJobRegistry.activeJobs.size}")

                if (groupIsOver) {
                    RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Last job in group completed. Marking finalized and cancelling summary.")
                    // Mark group as finalized FIRST to prevent race conditions
                    // where delayed progress callbacks might recreate the notification
                    UIDTJobRegistry.markGroupFinalized(groupId)
                    // Last job in group - cancel summary notification
                    UIDTNotificationManager.cancelSummaryNotification(this@UIDTDownloadJobService, groupId)
                } else if (groupId.isEmpty()) {
                    RNBackgroundDownloaderModuleImpl.logW(UIDTConstants.TAG, "groupId is empty in onComplete for $id")
                } else {
                    // Update summary notification for this group (update count/progress)
                    UIDTNotificationManager.updateSummaryNotificationForGroup(this@UIDTDownloadJobService, groupId, groupName)
                }

                // Use setNotification with REMOVE policy to tell Android to remove the UIDT-controlled notification
                // This is required because UIDT notifications are managed by the system, not NotificationManager
                // A shared notification belongs to the group, so only the last job may take
                // it down: otherwise the first to finish removes it while the others are
                // still transferring.
                val mayRemoveNotification = remainingInGroup == 0 || groupId.isEmpty()
                if (notificationId != null && mayRemoveNotification) {
                    val emptyNotification = UIDTNotificationManager.createEmptyNotification(this@UIDTDownloadJobService)
                    setNotification(params, notificationId, emptyNotification, JOB_END_NOTIFICATION_POLICY_REMOVE)
                    RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Set REMOVE policy for notification $notificationId")
                }

                // Signal job completion - this triggers the REMOVE policy
                finishWork()

                // Clear persisted resume state - no longer needed after successful completion
                UIDTJobRegistry.clearResumeState(this@UIDTDownloadJobService, id)
                UIDTJobRegistry.clearBatchItem(this@UIDTDownloadJobService, id)
                // The progress notification is gone; the completion one above already
                // took its ID from this offset, so it can go back to the pool
                UIDTNotificationIds.release(id)

                // Notify external listener
                UIDTJobRegistry.downloadListener?.onComplete(id, location, bytesDownloaded, bytesTotal)
            }

            override fun onError(id: String, error: String, errorCode: Int) {
                RNBackgroundDownloaderModuleImpl.logE(UIDTConstants.TAG, "UIDT download error: $id - $error ($errorCode)")

                val jobState = UIDTJobRegistry.activeJobs[id]
                val notificationId = jobState?.notificationId

                // Clean up - remove from activeJobs first
                UIDTJobRegistry.activeJobs.remove(id)
                // A failed file never reaches 100%, so drop it instead of letting it
                // hold the group's progress short of complete for good.
                UIDTJobRegistry.forgetGroupFile(groupId, id)
                releaseWakeLock()

                val groupIsOver = groupId.isNotEmpty() && UIDTJobRegistry.isGroupComplete(groupId)
                val remainingInGroup = UIDTJobRegistry.groupProgress[groupId]?.pendingFiles ?: 0
                RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Files left in group '$groupId' after error: $remainingInGroup")

                if (groupIsOver) {
                    // Mark group as finalized FIRST to prevent race conditions
                    UIDTJobRegistry.markGroupFinalized(groupId)
                    // Last job in group - cancel summary notification
                    UIDTNotificationManager.cancelSummaryNotification(this@UIDTDownloadJobService, groupId)
                } else {
                    // Update summary notification for this group (update count/progress)
                    UIDTNotificationManager.updateSummaryNotificationForGroup(this@UIDTDownloadJobService, groupId, groupName)
                }

                // Use setNotification with REMOVE policy to tell Android to remove the UIDT-controlled notification
                val mayRemoveNotification = remainingInGroup == 0 || groupId.isEmpty()
                if (notificationId != null && mayRemoveNotification) {
                    val emptyNotification = UIDTNotificationManager.createEmptyNotification(this@UIDTDownloadJobService)
                    setNotification(params, notificationId, emptyNotification, JOB_END_NOTIFICATION_POLICY_REMOVE)
                    RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Set REMOVE policy for notification $notificationId (error)")
                }

                // Signal job completion with no reschedule - this triggers the REMOVE policy
                finishWork()

                // Clear persisted resume state - no longer needed after failure
                UIDTJobRegistry.clearResumeState(this@UIDTDownloadJobService, id)
                UIDTJobRegistry.clearBatchItem(this@UIDTDownloadJobService, id)
                UIDTNotificationIds.release(id)

                // Notify external listener
                UIDTJobRegistry.downloadListener?.onError(id, error, errorCode)
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "UIDTDownloadJobService::WakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(DownloadConstants.WAKELOCK_TIMEOUT_MS)
            }
            RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "WakeLock acquired")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }
}
