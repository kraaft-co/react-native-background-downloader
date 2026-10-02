package com.eko

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.JavaOnlyArray
import com.facebook.react.bridge.JavaOnlyMap
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.time.Duration
import org.robolectric.shadows.ShadowSystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Implements(Arguments::class)
class ProgressReporterArguments {
    companion object {
        @Implementation @JvmStatic fun createMap(): WritableMap = JavaOnlyMap()
        @Implementation @JvmStatic fun createArray(): WritableArray = JavaOnlyArray()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ProgressReporterArguments::class])
class ProgressReporterTest {
    @Test fun `simultaneous workers must not emit two batches inside the interval`() {
        val firstEmitting = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val emissions = AtomicInteger()
        val reporter = ProgressReporter({
            if (emissions.incrementAndGet() == 1) {
                firstEmitting.countDown()
                assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
            }
        })
        reporter.configure(1000, 1)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1000))
        val first = Thread { reporter.reportProgress("a", 50, 100) }
        first.start()
        assertTrue(firstEmitting.await(5, TimeUnit.SECONDS))
        try {
            reporter.reportProgress("b", 50, 100)
        } finally {
            releaseFirst.countDown()
            first.join(5000)
        }
        assertEquals("one batch allowed per 1000 ms", 1, emissions.get())
    }

    @Test fun `reports received during emission remain queued for the next interval`() {
        val firstEmitting = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val batches = mutableListOf<WritableArray>()
        val reporter = ProgressReporter({ batch ->
            batches.add(batch)
            if (batches.size == 1) {
                firstEmitting.countDown()
                assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
            }
        })
        reporter.configure(1000, 1)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1000))
        val first = Thread { reporter.reportProgress("a", 50, 100) }
        first.start()
        assertTrue(firstEmitting.await(5, TimeUnit.SECONDS))
        try {
            reporter.reportProgress("b", 50, 100)
        } finally {
            releaseFirst.countDown()
            first.join(5000)
        }
        ShadowSystemClock.advanceBy(Duration.ofMillis(1000))
        reporter.reportProgress("c", 50, 100)
        assertEquals(2, batches.size)
        val next = batches[1].toArrayList().map { (it as com.facebook.react.bridge.ReadableMap).toHashMap() }.associateBy { it["id"] }
        assertEquals(setOf("b", "c"), next.keys)
        assertEquals(50.0, next["b"]!!["bytesDownloaded"])
    }

    @Test fun `batch contains the latest report per file and excludes cleared tasks`() {
        val batches = mutableListOf<WritableArray>()
        val reporter = ProgressReporter({ batches.add(it) })
        reporter.configure(1000, 1)
        reporter.reportProgress("a", 20, 100)
        reporter.reportProgress("a", 40, 100)
        reporter.reportProgress("cancelled", 20, 100)
        reporter.clearDownloadState("cancelled")
        assertTrue(batches.isEmpty())
        ShadowSystemClock.advanceBy(Duration.ofMillis(1000))
        reporter.reportProgress("b", 60, 100)
        assertEquals(1, batches.size)
        val batch = batches.single().toArrayList().map { (it as com.facebook.react.bridge.ReadableMap).toHashMap() }.associateBy { it["id"] }
        assertEquals(setOf("a", "b"), batch.keys)
        assertEquals(40.0, batch["a"]!!["bytesDownloaded"])
    }

}
