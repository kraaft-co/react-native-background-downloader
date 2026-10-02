package com.eko.uidt

import android.app.job.JobParameters
import android.content.Context
import com.eko.RNBackgroundDownloaderModuleImpl
import com.eko.ResumableDownloader
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Data class representing the state of an active UIDT download job.
 */
data class JobState(
    val params: JobParameters,
    val resumableDownloader: ResumableDownloader,
    var notificationId: Int,
    val groupId: String = "",
    val groupName: String = "",
    // Optional per-download notification title (read from metadata.notificationTitle).
    // When non-empty, overrides both groupName and the config's default downloadTitle.
    val customTitle: String = "",
    var lastNotifiedProgress: Int = -1,
    var lastNotificationUpdateTime: Long = 0,
    // Track download progress for summary notification
    @Volatile var bytesDownloaded: Long = 0L,
    @Volatile var bytesTotal: Long = -1L
)

/**
 * Mode for notification display when grouping is enabled.
 */
enum class NotificationGroupingMode {
    INDIVIDUAL,  // Show all notifications (default, current behavior)
    SUMMARY_ONLY  // Show only summary notification, minimize individual ones
}

/**
 * Configuration for notification display.
 */
data class NotificationConfig(
    var groupingEnabled: Boolean = false,
    var showNotificationsEnabled: Boolean = false,
    // Post a "download complete" notification when a download finishes.
    // Opt-in: it alerts on its own channel, unlike the silent progress one.
    var showCompletionNotification: Boolean = false,
    // Add a Cancel button to the progress notification. Opt-in: cancelling
    // from the shade surfaces in JS as a downloadFailed the app has to handle.
    var showCancelAction: Boolean = false,
    var mode: NotificationGroupingMode = NotificationGroupingMode.INDIVIDUAL,
    var updateInterval: Long = 500L,
    val texts: MutableMap<String, String> = mutableMapOf(
        "downloadTitle" to "Download",
        "downloadStarting" to "Starting download...",
        "downloadProgress" to "Downloading... {progress}%",
        "downloadPaused" to "Paused",
        "downloadFinished" to "Download complete",
        "downloadCancel" to "Cancel",
        "groupTitle" to "Downloads",
        "groupText" to "{count} download(s) in progress"
    )
) {
    fun getText(key: String, vararg replacements: Pair<String, Any>): String {
        var text = texts[key] ?: ""
        for ((placeholder, value) in replacements) {
            text = text.replace("{$placeholder}", value.toString())
        }
        return text
    }

    fun updateTexts(newTexts: Map<String, String>) {
        newTexts.forEach { (key, value) ->
            texts[key] = value
        }
    }
}

/** Byte counts of one download inside a group. */
class FileProgress {
    @Volatile var bytesDownloaded: Long = 0L
    @Volatile var bytesTotal: Long = -1L
    @Volatile var completed: Boolean = false
}

/**
 * Aggregate progress of a download group. A file is registered when its job is
 * scheduled and stays in the map once it finishes, so neither the numerator nor
 * the denominator shrinks mid-batch - summing the live jobs instead made the
 * percentage fall back every time a file completed.
 */
class GroupProgress {
    private val files = ConcurrentHashMap<String, FileProgress>()

    fun register(configId: String, bytesTotal: Long) {
        val file = files.getOrPut(configId) { FileProgress() }
        if (bytesTotal > 0) file.bytesTotal = bytesTotal
    }

    fun update(configId: String, bytesDownloaded: Long, bytesTotal: Long) {
        val file = files.getOrPut(configId) { FileProgress() }
        file.bytesDownloaded = bytesDownloaded
        if (bytesTotal > 0) file.bytesTotal = bytesTotal
    }

    fun markCompleted(configId: String) {
        val file = files.getOrPut(configId) { FileProgress() }
        file.completed = true
        if (file.bytesTotal > 0) file.bytesDownloaded = file.bytesTotal
    }

    /** Drops a download that will never finish, so it stops holding the group open. */
    fun forget(configId: String) {
        files.remove(configId)
    }

    val isEmpty: Boolean get() = files.isEmpty()
    val totalFiles: Int get() = files.size
    val completedFiles: Int get() = files.values.count { it.completed }
    val pendingFiles: Int get() = totalFiles - completedFiles
    val allCompleted: Boolean get() = files.isNotEmpty() && completedFiles == totalFiles
    val downloadedBytes: Long get() = files.values.sumOf { it.bytesDownloaded }
    val totalBytes: Long get() = files.values.sumOf { if (it.bytesTotal > 0) it.bytesTotal else 0L }

    /**
     * Only every known size makes the bar honest: with one size still missing the
     * denominator is short, and the bar would jump backwards when it arrives.
     * Passing `totalBytes` to the download avoids the indeterminate phase.
     */
    val hasKnownTotal: Boolean get() = files.isNotEmpty() && files.values.all { it.bytesTotal > 0 }

    val progressPercent: Int
        get() = if (hasKnownTotal && totalBytes > 0) {
            ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
        } else {
            0
        }
}

/**
 * Constants for UIDT jobs.
 */
object UIDTConstants {
    const val TAG = "UIDTDownloadJobService"

    // Start of the job-ID range owned by the library. IDs inside it are handed
    // out per download by UIDTJobIds.
    const val JOB_ID_BASE = 10000

    // PersistableBundle keys
    const val KEY_DOWNLOAD_ID = "download_id"
    const val KEY_URL = "url"
    const val KEY_DESTINATION = "destination"
    const val KEY_START_BYTE = "start_byte"
    const val KEY_TOTAL_BYTES = "total_bytes"
    const val KEY_METADATA = "metadata"
    const val KEY_IS_ALLOWED_OVER_METERED = "is_allowed_over_metered"

    // Notification channel for UIDT jobs (visible notifications)
    const val NOTIFICATION_CHANNEL_ID = "uidt_download_channel"

    // Notification channel for UIDT jobs (silent/hidden notifications)
    const val NOTIFICATION_CHANNEL_SILENT_ID = "uidt_download_channel_silent"

    // Notification channel for UIDT jobs (ultra-silent for summaryOnly mode)
    const val NOTIFICATION_CHANNEL_ULTRA_SILENT_ID = "uidt_download_channel_ultra_silent"

    // Notification channel for the one-shot "download complete" notification.
    // Uses IMPORTANCE_DEFAULT so completion is actually surfaced to the user
    // (the progress channel is IMPORTANCE_LOW and never alerts).
    const val NOTIFICATION_CHANNEL_FINISHED_ID = "uidt_download_channel_finished"

    // Notification group for grouping all download notifications together
    const val NOTIFICATION_GROUP_KEY = "com.eko.DOWNLOAD_GROUP"

    // Summary notification ID (used to group all download notifications)
    const val SUMMARY_NOTIFICATION_ID = 19999

    // Base for individual notification IDs
    const val NOTIFICATION_ID_BASE = 20000

    // Base for one-shot "download complete" notification IDs. Kept in a
    // disjoint range from NOTIFICATION_ID_BASE (which spans 20000..119999 via
    // hash % 100000) so a finished notification can never collide with another
    // download's in-progress notification.
    const val FINISHED_NOTIFICATION_ID_BASE = 200000
}

/**
 * Data class representing the state of a UIDT job for external queries.
 */
data class UIDTJobInfo(
    val id: String,
    val status: Int,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
    val url: String,
    val destination: String,
    val metadata: String
)

/**
 * Singleton for managing active UIDT jobs state.
 */
object UIDTJobRegistry {

    private const val PREFS_NAME = "rnbd_uidt_resume"
    private const val KEY_BYTES_PREFIX = "bytes_"
    private const val KEY_HEADERS_PREFIX = "headers_"

    /**
     * Persist UIDT resume state (headers + byte position) to disk so it
     * survives process death and can be used when the job is rescheduled in a
     * new process.
     */
    fun saveResumeState(context: Context, configId: String, headers: Map<String, String>, bytesDownloaded: Long) {
        try {
            val headersJson = JSONObject(headers as Map<*, *>).toString()
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putLong("$KEY_BYTES_PREFIX$configId", bytesDownloaded)
                .putString("$KEY_HEADERS_PREFIX$configId", headersJson)
                .apply()
            RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Saved UIDT resume state for $configId: bytes=$bytesDownloaded")
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logE(UIDTConstants.TAG, "Failed to save UIDT resume state for $configId: ${e.message}")
        }
    }

    /**
     * Load persisted UIDT resume state for a download.
     * Returns (headers, bytesDownloaded) or null if no state was saved.
     */
    fun loadResumeState(context: Context, configId: String): Pair<Map<String, String>, Long>? {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val bytesDownloaded = prefs.getLong("$KEY_BYTES_PREFIX$configId", -1L)
            val headersJson = prefs.getString("$KEY_HEADERS_PREFIX$configId", null)
            if (bytesDownloaded < 0 || headersJson == null) return null
            val json = JSONObject(headersJson)
            val headers = mutableMapOf<String, String>()
            for (key in json.keys()) headers[key] = json.getString(key)
            RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Loaded UIDT resume state for $configId: bytes=$bytesDownloaded, headers=${headers.size}")
            return Pair(headers, bytesDownloaded)
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logE(UIDTConstants.TAG, "Failed to load UIDT resume state for $configId: ${e.message}")
            return null
        }
    }

    /**
     * Clear persisted UIDT resume state for a download once it is no longer needed.
     */
    fun clearResumeState(context: Context, configId: String) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .remove("$KEY_BYTES_PREFIX$configId")
                .remove("$KEY_HEADERS_PREFIX$configId")
                .apply()
            RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Cleared UIDT resume state for $configId")
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logE(UIDTConstants.TAG, "Failed to clear UIDT resume state for $configId: ${e.message}")
        }
    }

    // Track active jobs for pause/resume
    val activeJobs = ConcurrentHashMap<String, JobState>()

    // Static storage for headers (PersistableBundle can't store complex objects)
    val pendingHeaders = ConcurrentHashMap<String, Map<String, String>>()

    // Static listener reference (set by Downloader)
    @Volatile
    var downloadListener: ResumableDownloader.DownloadListener? = null

    // Store service instance reference for notification updates
    @Volatile
    var serviceInstance: com.eko.UIDTDownloadJobService? = null

    // Notification configuration
    val notificationConfig = NotificationConfig()

    // Group progress tracking for each groupId
    val groupProgress = ConcurrentHashMap<String, GroupProgress>()

    // Track groups that have been finalized (all jobs completed)
    // This prevents race conditions where progress updates recreate cancelled notifications
    val finalizedGroups = ConcurrentHashMap.newKeySet<String>()

    fun isActiveJob(configId: String): Boolean = activeJobs.containsKey(configId)

    fun isPausedJob(configId: String): Boolean {
        val jobState = activeJobs[configId]
        return jobState?.resumableDownloader?.isPaused(configId) ?: false
    }

    fun getJobDownloadState(configId: String): ResumableDownloader.DownloadState? {
        val jobState = activeJobs[configId] ?: return null
        return jobState.resumableDownloader.getState(configId)
    }

    /**
     * Read the metered-network permission of an active job from its persisted extras.
     * Returns null when the job is not active. The extras survive process death, so
     * this is the source of truth when the module's in-memory map has been lost.
     */
    fun getJobIsAllowedOverMetered(configId: String): Boolean? {
        val jobState = activeJobs[configId] ?: return null
        return jobState.params.extras.getBoolean(UIDTConstants.KEY_IS_ALLOWED_OVER_METERED, true)
    }

    /**
     * Enrol a download in its group as soon as its job is scheduled, so a file
     * that has not started yet already counts towards the group's total.
     */
    fun registerGroupFile(groupId: String, configId: String, bytesTotal: Long) {
        if (groupId.isEmpty()) return
        groupProgress.getOrPut(groupId) { GroupProgress() }.register(configId, bytesTotal)
    }

    /**
     * Update aggregate progress for a group.
     */
    fun updateGroupProgress(groupId: String, configId: String, bytesDownloaded: Long, bytesTotal: Long) {
        if (groupId.isEmpty()) return
        groupProgress.getOrPut(groupId) { GroupProgress() }.update(configId, bytesDownloaded, bytesTotal)
    }

    /**
     * Mark a file as completed in a group.
     */
    fun markFileCompleted(groupId: String, configId: String) {
        if (groupId.isEmpty()) return
        // No tally means the group is already over; don't resurrect it.
        groupProgress[groupId]?.markCompleted(configId)
    }

    /**
     * Drop a download that will never finish (cancelled, or failed for good) from
     * its group, so the remaining files can still reach 100%.
     */
    fun forgetGroupFile(groupId: String, configId: String) {
        if (groupId.isEmpty()) return
        groupProgress[groupId]?.forget(configId)
    }

    /**
     * Whether every download enrolled in the group has finished. Unlike counting
     * the live jobs, this also waits for the ones whose job has not started yet.
     */
    fun isGroupComplete(groupId: String): Boolean {
        if (groupId.isEmpty()) return false
        // A group with nothing left in it is over too - every file failed and was
        // forgotten, or the tally was already cleared.
        val progress = groupProgress[groupId] ?: return true
        return progress.isEmpty || progress.allCompleted
    }

    /**
     * Clear group progress when all downloads complete.
     */
    fun clearGroupProgress(groupId: String) {
        groupProgress.remove(groupId)
    }

    /**
     * Mark a group as finalized (all downloads complete).
     * This prevents race conditions where delayed progress updates might recreate the notification.
     */
    fun markGroupFinalized(groupId: String) {
        if (groupId.isNotEmpty()) {
            finalizedGroups.add(groupId)
        }
    }

    /**
     * Check if a group is finalized.
     */
    fun isGroupFinalized(groupId: String): Boolean {
        return finalizedGroups.contains(groupId)
    }

    /**
     * Unmark a group as finalized (when new downloads start).
     */
    fun unmarkGroupFinalized(groupId: String) {
        finalizedGroups.remove(groupId)
    }

    /**
     * Returns a snapshot of all currently active UIDT jobs.
     * Used by the module to populate getExistingDownloads on Android 14+.
     *
     * Status values match DownloadManager constants for JS consistency:
     * - STATUS_RUNNING = 2 (1 << 1)
     * - STATUS_PAUSED = 4 (1 << 2)
     */
    fun getAllActiveJobs(): List<UIDTJobInfo> {
        return activeJobs.map { (configId, jobState) ->
            val state = jobState.resumableDownloader.getState(configId)
            val isPaused = jobState.resumableDownloader.isPaused(configId)

            // Map to DownloadManager constants so JS logic stays consistent
            // STATUS_RUNNING = 2, STATUS_PAUSED = 4
            val status = if (isPaused) 4 else 2

            // Retrieve URL/Dest from the job extras
            val extras = jobState.params.extras
            val url = extras.getString(UIDTConstants.KEY_URL) ?: ""
            val destination = extras.getString(UIDTConstants.KEY_DESTINATION) ?: ""
            val metadata = extras.getString(UIDTConstants.KEY_METADATA) ?: "{}"

            UIDTJobInfo(
                id = configId,
                status = status,
                bytesDownloaded = state?.bytesDownloaded?.get() ?: 0L,
                bytesTotal = state?.bytesTotal ?: -1L,
                url = url,
                destination = destination,
                metadata = metadata
            )
        }
    }
}
