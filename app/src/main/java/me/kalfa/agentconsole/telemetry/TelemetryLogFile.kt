package me.kalfa.agentconsole.telemetry

import java.io.File
import java.io.RandomAccessFile

/**
 * The record of truth: an append-only file in app-private storage.
 *
 * **Why a local file exists at all, when the point of the feature is streaming to
 * a server.** The upload path depends on three things that all fail on precisely
 * the scenario being diagnosed — a Supabase JWT (loaded asynchronously, so null
 * for the first stretch of a push cold start), the process surviving long enough
 * for a fire-and-forget coroutine to run, and a network on a dozing device. If
 * the only evidence were the server log, the owner would tail it, see two lines,
 * and have no way to tell *"the app stopped at step 3"* from *"telemetry stopped
 * at step 3"*. That ambiguity is the exact thing this whole channel was built to
 * remove, so the file is written first and the POST is a convenience on top of it.
 *
 * Bounded by construction: one line is capped at [TELEMETRY_MAX_LINE_CHARS], the
 * live file rotates at [MAX_BYTES], and exactly one rotation is kept. Worst case
 * on disk is therefore ~2 MB, whatever happens upstream — a device stuck in a
 * retry loop cannot fill the phone any more than it can fill the server.
 */
class TelemetryLogFile(baseDir: File) {

    private val dir = File(baseDir, DIR_NAME)
    private val live = File(dir, FILE_NAME)
    private val rotated = File(dir, "$FILE_NAME.1")

    /** Single-writer discipline: only DeviceTelemetry's writer thread calls this. */
    fun append(lines: List<String>) {
        if (lines.isEmpty()) return
        if (!dir.exists() && !dir.mkdirs()) return
        rotateIfNeeded()
        // ONE write for the whole batch. Appending line by line would multiply
        // syscalls on the wake path, which is the one path where the process may
        // be killed mid-loop and lose the tail that mattered.
        val payload = lines.joinToString(separator = "\n", postfix = "\n")
        live.appendText(payload)
    }

    private fun rotateIfNeeded() {
        if (live.length() < MAX_BYTES) return
        if (rotated.exists()) rotated.delete()
        // A failed rename must not stop logging: fall back to truncating the live
        // file. Losing history is bad; silently ceasing to record is worse.
        if (!live.renameTo(rotated)) live.writeText("")
    }

    /**
     * The most recent [maxLines] lines, oldest first, across the rotation.
     *
     * Reads the tail rather than the whole file: after a long shift the rotated
     * file is a megabyte of lines nobody is going to scroll through, and loading
     * it all into a Compose list would be a jank source on the one screen that
     * must stay responsive while a call is ringing.
     */
    fun readTail(maxLines: Int): List<String> {
        val fromLive = tailOf(live, maxLines)
        if (fromLive.size >= maxLines) return fromLive
        val fromRotated = tailOf(rotated, maxLines - fromLive.size)
        return fromRotated + fromLive
    }

    /** Total bytes currently held on disk, for the Debug Live screen's status row. */
    fun sizeBytes(): Long = live.length() + rotated.length()

    fun clear() {
        live.delete()
        rotated.delete()
    }

    private fun tailOf(file: File, maxLines: Int): List<String> {
        if (maxLines <= 0 || !file.exists()) return emptyList()
        val length = file.length()
        if (length == 0L) return emptyList()
        // Read at most a bounded window off the end. TELEMETRY_MAX_LINE_CHARS is
        // the hard per-line cap, so this window cannot under-read the requested
        // number of lines by more than the truncation at the leading edge, which
        // is dropped below anyway.
        val window = minOf(length, maxLines.toLong() * (TELEMETRY_MAX_LINE_CHARS + 1))
        val bytes = ByteArray(window.toInt())
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(length - window)
            raf.readFully(bytes)
        }
        val text = String(bytes, Charsets.UTF_8)
        // The first line is probably a fragment (the window started mid-line);
        // drop it unless the window covered the whole file.
        val lines = text.split('\n').filter { it.isNotBlank() }
        val whole = window == length
        val usable = if (whole || lines.isEmpty()) lines else lines.drop(1)
        return usable.takeLast(maxLines)
    }

    private companion object {
        const val DIR_NAME = "telemetry"
        const val FILE_NAME = "device-telemetry.log"
        const val MAX_BYTES = 1L * 1024 * 1024
    }
}
