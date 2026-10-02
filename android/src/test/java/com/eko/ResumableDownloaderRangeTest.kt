package com.eko

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResumableDownloaderRangeTest {
    @get:Rule val files = TemporaryFolder()
    private lateinit var server: MockWebServer
    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun teardown() { server.shutdown() }

    private fun check416(localSize: Int, offset: Long, savedTotal: Long, contentRange: String?, complete: Boolean) {
        val file = files.newFile()
        val original = ByteArray(localSize) { 120 }
        file.writeBytes(original)
        val response = MockResponse().setResponseCode(416)
        if (contentRange != null) response.setHeader("Content-Range", contentRange)
        server.enqueue(response)
        val finished = CountDownLatch(1)
        var completedBytes: Long? = null
        var completedTotal: Long? = null
        var reportedError: Int? = null
        val listener = object : ResumableDownloader.DownloadListener {
            override fun onBegin(id: String, expectedBytes: Long, headers: Map<String, String>) = Unit
            override fun onProgress(id: String, bytesDownloaded: Long, bytesTotal: Long) = Unit
            override fun onComplete(id: String, location: String, bytesDownloaded: Long, bytesTotal: Long) {
                completedBytes = bytesDownloaded
                completedTotal = bytesTotal
                finished.countDown()
            }
            override fun onError(id: String, error: String, errorCode: Int) {
                reportedError = errorCode
                finished.countDown()
            }
        }
        ResumableDownloader().startDownload("recovered", server.url("/file").toString(), file.absolutePath,
            emptyMap(), listener, startByte = offset, totalBytes = savedTotal, isAllowedOverMetered = true)
        assertTrue("download callback", finished.await(5, TimeUnit.SECONDS))
        assertEquals("bytes=$offset-", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Range"))
        if (complete) {
            assertEquals(localSize.toLong(), completedBytes)
            assertEquals(localSize.toLong(), completedTotal)
            assertNull(reportedError)
        } else {
            assertNull(completedBytes)
            assertEquals(416, reportedError)
        }
        assertArrayEquals("416 must not overwrite the destination", original, file.readBytes())
    }

    @Test fun `complete recovered file uses server size when saved total is unknown`() = check416(10, 10, -1, "bytes */10", true)
    @Test fun `response size takes precedence over stale saved total`() = check416(10, 10, 20, "bytes */10", true)
    @Test fun `oversized destination is not complete`() = check416(11, 11, 10, "bytes */10", false)
    @Test fun `offset beyond destination is not complete`() = check416(10, 11, -1, "bytes */10", false)
    @Test fun `partial file is not complete`() = check416(5, 5, 10, "bytes */10", false)
    @Test fun `missing header and unknown total remain an error`() = check416(10, 10, -1, null, false)
    @Test fun `known exact size works without response header`() = check416(10, 10, 10, null, true)
    @Test fun `malformed response cannot confirm completion`() = check416(10, 10, 10, "bytes */invalid", false)
}
