package com.eko.uidt

import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UIDTNotificationProgressTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val groupId = "progress-test"

    @Before fun setup() {
        UIDTJobRegistry.groupProgress.clear()
        UIDTJobRegistry.activeJobs.clear()
        UIDTJobRegistry.serviceInstance = null
        UIDTJobRegistry.unmarkGroupFinalized(groupId)
        UIDTJobRegistry.notificationConfig.apply {
            showNotificationsEnabled = true
            groupingEnabled = true
            mode = NotificationGroupingMode.SUMMARY_ONLY
            updateInterval = 1L
        }
        UIDTNotificationManager.createNotificationChannels(context)
        UIDTNotificationManager.cancelSummaryNotification(context, groupId)
    }

    @Test fun `a burst posts the latest progress after the minimum interval`() {
        repeat(100) { UIDTJobRegistry.registerGroupFile(groupId, "file-$it", -1) }
        repeat(80) {
            UIDTJobRegistry.markFileCompleted(groupId, "file-$it")
            UIDTNotificationManager.updateSummaryNotificationWithProgress(context, groupId, "Folder")
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(499))
        assertEquals(0, manager.activeNotifications.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        val visible = manager.activeNotifications.single { it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0 }.notification
        assertEquals(80, visible.extras.getInt("android.progress"))
        assertEquals(2, manager.activeNotifications.size)
        assertFalse(visible.extras.getBoolean("android.progressIndeterminate"))
    }

    @Test fun `cancel removes pending progress instead of recreating the notification`() {
        UIDTJobRegistry.registerGroupFile(groupId, "file", 100)
        UIDTNotificationManager.updateSummaryNotificationWithProgress(context, groupId, "Folder")
        UIDTNotificationManager.cancelSummaryNotification(context, groupId)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test fun `completion before a pending update prevents a stale notification`() {
        UIDTJobRegistry.registerGroupFile(groupId, "file", 100)
        UIDTNotificationManager.updateSummaryNotificationWithProgress(context, groupId, "Folder")
        UIDTJobRegistry.markGroupFinalized(groupId)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(0, manager.activeNotifications.size)
    }
}
