package com.pulse.core.network

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request

/** Progress of a bundled-database download. */
sealed interface DownloadProgress {
    data object Starting : DownloadProgress

    data class Downloading(
        val bytesRead: Long,
        val totalBytes: Long?,
    ) : DownloadProgress {
        /** Null when the server sends no Content-Length. */
        val fraction: Float?
            get() = totalBytes?.takeIf { it > 0 }?.let { (bytesRead.toFloat() / it).coerceIn(0f, 1f) }
    }

    data object Verifying : DownloadProgress
    data class Done(val file: File) : DownloadProgress
    data class Failed(val reason: String, val retryable: Boolean) : DownloadProgress
}

/**
 * Downloads a large database asset, resumably.
 *
 * The food database is ~68 MB compressed and cannot ship in the APK, so it is
 * fetched on first run (PHASE3_REPORT.md — the largest open item). Three things
 * make that survivable on a phone:
 *
 * 1. **Resume via HTTP Range.** A dropped connection at 60 MB must not restart
 *    from zero on a metered network.
 * 2. **Download to a temp file, rename on success.** A half-written database
 *    must never be visible to Room — the rename is the commit.
 * 3. **Verify the checksum before committing.** A truncated or corrupted file
 *    that Room later opens produces baffling errors far from the cause.
 */
class AssetDownloader(
    private val client: OkHttpClient,
) {

    /**
     * @param expectedSha256 checksum of the bytes actually transferred. When
     *   [gzipped] is true this is the checksum of the *compressed* file, since
     *   that is what was downloaded and what corruption would affect.
     * @param gzipped decompress after verifying. Serving the food database
     *   gzipped takes the download from 197 MB to 67 MB; GitHub release assets
     *   are served verbatim, so nothing decompresses it for us.
     */
    fun download(
        url: String,
        destination: File,
        expectedSha256: String? = null,
        expectedBytes: Long? = null,
        gzipped: Boolean = false,
    ): Flow<DownloadProgress> = flow {
        emit(DownloadProgress.Starting)

        if (destination.exists() && destination.length() > 0) {
            emit(DownloadProgress.Done(destination))
            return@flow
        }

        val temp = File(destination.parentFile, "${destination.name}.part")
        destination.parentFile?.mkdirs()

        // Anything already fetched is reused rather than re-downloaded.
        val existing = if (temp.exists()) temp.length() else 0L

        val request = Request.Builder()
            .url(url)
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .build()

        try {
            client.newCall(request).execute().use { response ->
                // 416 means our partial file is at least as long as the
                // resource — usually a stale or corrupt .part. Start over.
                if (response.code == 416) {
                    temp.delete()
                    emit(DownloadProgress.Failed("Partial download was stale", retryable = true))
                    return@flow
                }
                if (!response.isSuccessful) {
                    emit(
                        DownloadProgress.Failed(
                            "HTTP ${response.code}",
                            retryable = response.code == 429 || response.code >= 500,
                        ),
                    )
                    return@flow
                }

                // A server that ignores Range replies 200, not 206 — appending
                // in that case would corrupt the file with a duplicated prefix.
                val resuming = response.code == 206 && existing > 0
                if (!resuming && existing > 0) temp.delete()

                val alreadyHave = if (resuming) existing else 0L
                val body = response.body
                    ?: run {
                        emit(DownloadProgress.Failed("Empty response", retryable = true))
                        return@flow
                    }
                val total = body.contentLength()
                    .takeIf { it > 0 }
                    ?.let { it + alreadyHave }
                    ?: expectedBytes

                var written = alreadyHave
                emit(DownloadProgress.Downloading(written, total))

                body.byteStream().use { input ->
                    java.io.FileOutputStream(temp, resuming).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var lastEmit = 0L
                        while (true) {
                            // Cancellation must leave the .part file intact so
                            // the next attempt resumes rather than restarts.
                            currentCoroutineContext().ensureActive()

                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            written += read

                            if (written - lastEmit >= EMIT_EVERY_BYTES) {
                                lastEmit = written
                                emit(DownloadProgress.Downloading(written, total))
                            }
                        }
                        output.flush()
                    }
                }

                emit(DownloadProgress.Downloading(written, total))
            }
        } catch (e: IOException) {
            // The partial file is deliberately kept for the next attempt.
            emit(DownloadProgress.Failed(e.message ?: "Network error", retryable = true))
            return@flow
        }

        if (expectedSha256 != null) {
            emit(DownloadProgress.Verifying)
            val actual = temp.sha256()
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                // A corrupt file must not be resumed from — that would append
                // to bad bytes forever.
                temp.delete()
                emit(
                    DownloadProgress.Failed(
                        "Downloaded file was corrupted, please try again",
                        retryable = true,
                    ),
                )
                return@flow
            }
        }

        // Decompress *after* verifying, so a corrupted transfer is caught
        // before it is fed to the gzip reader and reported as something else.
        if (gzipped) {
            emit(DownloadProgress.Verifying)
            val expanded = File(temp.parentFile, "${destination.name}.expanded")
            try {
                java.util.zip.GZIPInputStream(temp.inputStream().buffered()).use { input ->
                    expanded.outputStream().buffered().use { output ->
                        input.copyTo(output, BUFFER_BYTES)
                    }
                }
            } catch (e: IOException) {
                expanded.delete()
                temp.delete()
                emit(
                    DownloadProgress.Failed(
                        e.message ?: "Downloaded file could not be expanded",
                        retryable = true,
                    ),
                )
                return@flow
            }
            temp.delete()
            expanded.commitTo(destination)
            emit(DownloadProgress.Done(destination))
            return@flow
        }

        // The rename is the commit: Room never sees a partial database.
        temp.commitTo(destination)
        emit(DownloadProgress.Done(destination))
    }.flowOn(Dispatchers.IO)

    /** Atomic where the filesystem allows it; a copy is the fallback. */
    private fun File.commitTo(destination: File) {
        if (!renameTo(destination)) {
            copyTo(destination, overwrite = true)
            delete()
        }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024

        /** Emit roughly every 512 KB — often enough to look live, rare enough
         *  not to flood the UI with recompositions on a fast connection. */
        const val EMIT_EVERY_BYTES = 512L * 1024
    }
}

internal fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { stream ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
