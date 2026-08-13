package com.pulse.core.network

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AssetDownloaderTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var downloader: AssetDownloader

    private val payload = ByteArray(200_000) { (it % 251).toByte() }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        downloader = AssetDownloader(OkHttpClient())
    }

    @After
    fun tearDown() = server.shutdown()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun body(bytes: ByteArray) = Buffer().apply { write(bytes) }

    @Test
    fun `downloads a file and reports completion`() = runTest {
        server.enqueue(MockResponse().setBody(body(payload)))
        val dest = File(temp.root, "food.db")

        val events = downloader.download(server.url("/food.db").toString(), dest).toList()

        assertTrue(events.last() is DownloadProgress.Done)
        assertTrue(dest.exists())
        assertEquals(payload.size.toLong(), dest.length())
        assertTrue(payload.contentEquals(dest.readBytes()))
    }

    @Test
    fun `reports progress as it goes`() = runTest {
        server.enqueue(MockResponse().setBody(body(payload)))
        val dest = File(temp.root, "food.db")

        val events = downloader.download(server.url("/f").toString(), dest).toList()
        val progress = events.filterIsInstance<DownloadProgress.Downloading>()

        assertTrue("expected intermediate progress", progress.size > 1)
        assertEquals(payload.size.toLong(), progress.last().bytesRead)
        assertEquals(1f, progress.last().fraction!!, 1e-6f)
    }

    /** A half-written database must never be visible to Room. */
    @Test
    fun `an interrupted download leaves no file at the destination`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody(body(payload))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        val dest = File(temp.root, "food.db")

        val events = downloader.download(server.url("/f").toString(), dest).toList()

        assertTrue("expected failure", events.last() is DownloadProgress.Failed)
        assertTrue("the destination must stay absent", !dest.exists())
        // The partial is kept so the retry can resume rather than restart.
        assertTrue("expected a .part file to resume from", File(temp.root, "food.db.part").exists())
    }

    /**
     * The point of resuming: a drop at 60 MB on a metered connection must not
     * cost the whole download again.
     */
    @Test
    fun `a retry resumes from the partial file instead of restarting`() = runTest {
        val firstHalf = payload.copyOfRange(0, 120_000)
        val secondHalf = payload.copyOfRange(120_000, payload.size)

        server.enqueue(
            MockResponse()
                .setBody(body(firstHalf))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        val dest = File(temp.root, "food.db")
        val url = server.url("/f").toString()

        downloader.download(url, dest).toList()
        val partial = File(temp.root, "food.db.part")
        assertTrue("expected a partial file", partial.exists())
        val resumedFrom = partial.length()
        assertTrue("partial should hold something", resumedFrom > 0)

        // Second attempt: server honours the Range header with a 206.
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setBody(body(payload.copyOfRange(resumedFrom.toInt(), payload.size))),
        )
        val events = downloader.download(url, dest).toList()

        assertTrue(events.last() is DownloadProgress.Done)
        assertTrue("resumed download must reassemble exactly", payload.contentEquals(dest.readBytes()))

        val secondRequest = server.takeRequest().let { server.takeRequest() }
        assertEquals("bytes=$resumedFrom-", secondRequest.getHeader("Range"))
    }

    /**
     * If the server ignores Range and replies 200, appending would duplicate
     * the prefix and silently corrupt the file.
     */
    @Test
    fun `a server ignoring Range restarts cleanly rather than corrupting`() = runTest {
        val partial = File(temp.root, "food.db.part")
        partial.writeBytes(payload.copyOfRange(0, 50_000))

        // 200, not 206 — the whole body again.
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(payload)))
        val dest = File(temp.root, "food.db")

        val events = downloader.download(server.url("/f").toString(), dest).toList()

        assertTrue(events.last() is DownloadProgress.Done)
        assertEquals(payload.size.toLong(), dest.length())
        assertTrue("must not duplicate the prefix", payload.contentEquals(dest.readBytes()))
    }

    @Test
    fun `a checksum mismatch fails and discards the file`() = runTest {
        server.enqueue(MockResponse().setBody(body(payload)))
        val dest = File(temp.root, "food.db")

        val events = downloader
            .download(server.url("/f").toString(), dest, expectedSha256 = "deadbeef")
            .toList()

        val failure = events.last()
        assertTrue("expected failure, got $failure", failure is DownloadProgress.Failed)
        assertTrue((failure as DownloadProgress.Failed).retryable)
        assertTrue("a corrupt database must not be committed", !dest.exists())
        // Deleted rather than kept: resuming from bad bytes would never recover.
        assertTrue("corrupt partial must be discarded", !File(temp.root, "food.db.part").exists())
    }

    @Test
    fun `a matching checksum commits the file`() = runTest {
        server.enqueue(MockResponse().setBody(body(payload)))
        val dest = File(temp.root, "food.db")

        val events = downloader
            .download(server.url("/f").toString(), dest, expectedSha256 = sha256(payload))
            .toList()

        assertTrue(events.any { it is DownloadProgress.Verifying })
        assertTrue(events.last() is DownloadProgress.Done)
        assertTrue(dest.exists())
    }

    @Test
    fun `an already-downloaded file is not fetched again`() = runTest {
        val dest = File(temp.root, "food.db")
        dest.writeBytes(payload)

        val events = downloader.download(server.url("/f").toString(), dest).toList()

        assertTrue(events.last() is DownloadProgress.Done)
        assertTrue(
            "should short-circuit without transferring anything",
            events.none { it is DownloadProgress.Downloading },
        )
        // The real assertion: a 68 MB asset must never be re-fetched on a
        // metered connection just because the app restarted.
        assertEquals("no request should have been made", 0, server.requestCount)
    }

    @Test
    fun `server errors are retryable and client errors are not`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        val retryable = downloader
            .download(server.url("/f").toString(), File(temp.root, "a.db")).toList().last()
        assertTrue((retryable as DownloadProgress.Failed).retryable)

        server.enqueue(MockResponse().setResponseCode(404))
        val permanent = downloader
            .download(server.url("/f").toString(), File(temp.root, "b.db")).toList().last()
        assertTrue(!(permanent as DownloadProgress.Failed).retryable)
    }

    // --- gzip -----------------------------------------------------------

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    /** Serving gzipped takes the food database from 197 MB to 67 MB. */
    @Test
    fun `a gzipped asset is expanded to the destination`() = runTest {
        val compressed = gzip(payload)
        server.enqueue(MockResponse().setBody(body(compressed)))
        val dest = File(temp.root, "food.db")

        val events = downloader.download(
            server.url("/food.db.gz").toString(),
            dest,
            expectedSha256 = sha256(compressed),
            gzipped = true,
        ).toList()

        assertTrue(events.last() is DownloadProgress.Done)
        assertTrue("must expand to the original bytes", payload.contentEquals(dest.readBytes()))
        assertTrue("no intermediate files left behind", File(temp.root, "food.db.part").let { !it.exists() })
        assertTrue(!File(temp.root, "food.db.expanded").exists())
    }

    /**
     * Verification happens before decompression, so a corrupted transfer is
     * reported as corruption rather than as a confusing gzip error.
     */
    @Test
    fun `a gzipped asset with a bad checksum fails before expanding`() = runTest {
        server.enqueue(MockResponse().setBody(body(gzip(payload))))
        val dest = File(temp.root, "food.db")

        val events = downloader.download(
            server.url("/f").toString(),
            dest,
            expectedSha256 = "deadbeef",
            gzipped = true,
        ).toList()

        val failure = events.last() as DownloadProgress.Failed
        assertTrue("should report corruption", failure.reason.contains("corrupt", ignoreCase = true))
        assertTrue(!dest.exists())
    }

    @Test
    fun `garbage that is not gzip fails cleanly rather than writing nonsense`() = runTest {
        val notGzip = payload
        server.enqueue(MockResponse().setBody(body(notGzip)))
        val dest = File(temp.root, "food.db")

        val events = downloader.download(
            server.url("/f").toString(),
            dest,
            expectedSha256 = sha256(notGzip),
            gzipped = true,
        ).toList()

        assertTrue("expected failure, got ${events.last()}", events.last() is DownloadProgress.Failed)
        assertTrue("must not commit a bad database", !dest.exists())
    }

    @Test
    fun `progress fraction is null when the server sends no length`() {
        val unknown = DownloadProgress.Downloading(bytesRead = 100, totalBytes = null)
        assertEquals(null, unknown.fraction)

        val known = DownloadProgress.Downloading(bytesRead = 50, totalBytes = 200)
        assertEquals(0.25f, known.fraction!!, 1e-6f)
    }
}
