package com.reelo.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FfmpegService {

    // ── Audio extraction ──────────────────────────────────────────────────────

    /**
     * Extracts mono 16kHz WAV audio from a video file.
     * This is what Whisper expects.
     */
    suspend fun extractAudio(videoFile: File): File = withContext(Dispatchers.IO) {
        val audioFile = File.createTempFile("reelo_audio_", ".wav")
        runFfmpeg(
            "-i", videoFile.absolutePath,
            "-vn",                  // no video
            "-ac", "1",            // mono
            "-ar", "16000",        // 16kHz sample rate
            "-y", audioFile.absolutePath
        )
        audioFile
    }

    // ── Audio energy analysis ─────────────────────────────────────────────────

    /**
     * Measures RMS audio energy per second across the whole file.
     * Returns list of (timestampSeconds, energyDb) pairs.
     * Used to find the loudest/most active moments for clip selection.
     */
    suspend fun getAudioEnergyMap(audioFile: File): List<EnergySegment> = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(
            "ffmpeg", "-i", audioFile.absolutePath,
            "-af", "astats=metadata=1:reset=1,ametadata=print:key=lavfi.astats.Overall.RMS_level:file=-",
            "-f", "null", "-"
        ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()

        // Parse lines like: lavfi.astats.Overall.RMS_level=-23.4
        // interleaved with frame timestamps
        parseEnergyOutput(output)
    }

    private fun parseEnergyOutput(output: String): List<EnergySegment> {
        val segments = mutableListOf<EnergySegment>()
        var currentTime = 0.0

        output.lines().forEach { line ->
            when {
                line.startsWith("pts_time:") -> {
                    currentTime = line.substringAfter("pts_time:").trim().toDoubleOrNull() ?: currentTime
                }
                line.contains("RMS_level=") -> {
                    val db = line.substringAfter("RMS_level=").trim().toDoubleOrNull()
                    if (db != null && db > -100) {
                        segments.add(EnergySegment(currentTime, db))
                    }
                }
            }
        }
        return segments
    }

    /**
     * Picks the best 6 clip windows from the energy map.
     * Rules:
     *   - Skip first/last 30 seconds (intros/outros)
     *   - At least 60 seconds between selected clips
     *   - Takes the loudest peaks
     */
    fun pickClipWindows(
        energyMap: List<EnergySegment>,
        totalDurationSec: Double,
        clipCount: Int = 6,
        clipDurationSec: Double = 12.0,
        minGapSec: Double = 60.0
    ): List<ClipWindow> {
        val filtered = energyMap
            .filter { it.timestampSec > 30 }
            .filter { it.timestampSec < totalDurationSec - 30 }
            .sortedByDescending { it.energyDb }

        val selected = mutableListOf<EnergySegment>()
        for (seg in filtered) {
            if (selected.none { Math.abs(it.timestampSec - seg.timestampSec) < minGapSec }) {
                selected.add(seg)
                if (selected.size >= clipCount) break
            }
        }

        return selected
            .sortedBy { it.timestampSec }   // order chronologically
            .mapIndexed { index, seg ->
                val startSec = maxOf(0.0, seg.timestampSec - clipDurationSec / 2)
                ClipWindow(
                    clipNumber    = index + 1,
                    startSec      = startSec,
                    durationSec   = clipDurationSec,
                    energyScore   = seg.energyDb
                )
            }
    }

    // ── Clip cutting ──────────────────────────────────────────────────────────

    /**
     * Cuts a single clip from the source video.
     * Applies silence trimming at start/end and 720p output.
     * isPaid=true uses higher quality CRF.
     */
    suspend fun cutClip(
        inputFile: File,
        startSec: Double,
        durationSec: Double,
        isPaid: Boolean = false
    ): File = withContext(Dispatchers.IO) {
        val outputFile = File.createTempFile("reelo_clip_", ".mp4")
        val crf = if (isPaid) "18" else "23"

        runFfmpeg(
            "-ss", startSec.toString(),
            "-i", inputFile.absolutePath,
            "-t", durationSec.toString(),
            // Silence trimming — removes dead air at start/end
            "-af", "silenceremove=start_periods=1:start_silence=0.3:start_threshold=-40dB",
            // 720p video
            "-vf", "scale=1280:720",
            "-c:v", "libx264",
            "-preset", "veryfast",
            "-crf", crf,
            "-c:a", "aac",
            "-b:a", "128k",
            "-movflags", "+faststart",
            "-y", outputFile.absolutePath
        )
        outputFile
    }

    // ── Audio chunking for long files ─────────────────────────────────────────

    /**
     * Splits a long audio file into chunks for Whisper
     * (Cloudflare Whisper has a 25MB file size limit).
     */
    suspend fun splitAudio(audioFile: File, chunkMinutes: Int = 10): List<File> =
        withContext(Dispatchers.IO) {
            val chunkSeconds = chunkMinutes * 60
            val outputDir = createTempDir("reelo_chunks_")
            val outputPattern = File(outputDir, "chunk_%03d.wav").absolutePath

            runFfmpeg(
                "-i", audioFile.absolutePath,
                "-f", "segment",
                "-segment_time", chunkSeconds.toString(),
                "-c", "copy",
                "-y", outputPattern
            )

            outputDir.listFiles { f -> f.name.endsWith(".wav") }
                ?.sortedBy { it.name }
                ?: emptyList()
        }

    /** Returns the duration of an audio file in milliseconds. */
    suspend fun getAudioDurationMs(audioFile: File): Int = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(
            "ffprobe", "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            audioFile.absolutePath
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        ((output.toDoubleOrNull() ?: 0.0) * 1000).toInt()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun runFfmpeg(vararg args: String) {
        val command = listOf("ffmpeg") + args.toList()
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw FfmpegException("FFmpeg failed (exit $exitCode): $output")
        }
    }
}

// ── Data shapes ───────────────────────────────────────────────────────────────

data class EnergySegment(
    val timestampSec: Double,
    val energyDb: Double
)

data class ClipWindow(
    val clipNumber: Int,
    val startSec: Double,
    val durationSec: Double,
    val energyScore: Double
)

class FfmpegException(message: String) : Exception(message)
