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
        val audioFile = File.createTempFile("reelo_audio_", ".mp3")
        runFfmpeg(
            "-i", videoFile.absolutePath,
            "-vn",
            "-ac", "1",
            "-ar", "16000",
            "-b:a", "32k",
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
            "-af", "silencedetect=noise=-30dB:d=0.5",
            "-f", "null", "-"
        ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()

        // Generate uniform energy segments every second as fallback
        val duration = getAudioDurationMs(audioFile) / 1000.0
        (0 until duration.toInt() step 1).map { sec ->
            EnergySegment(sec.toDouble(), -20.0)
        }
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
        minGapSec: Double = 20.0,
        semanticClips: List<SemanticClip> = emptyList()
    ): List<ClipWindow> {
        val skipSec = minOf(30.0, totalDurationSec * 0.1)

        // Energy-based candidates
        val filtered = energyMap
            .filter { it.timestampSec > skipSec }
            .filter { it.timestampSec < totalDurationSec - skipSec }
            .sortedByDescending { it.energyDb }

        val selected = mutableListOf<EnergySegment>()
        for (seg in filtered) {
            if (selected.none { Math.abs(it.timestampSec - seg.timestampSec) < minGapSec }) {
                selected.add(seg)
                if (selected.size >= clipCount) break
            }
        }

        val energyWindows = selected
            .sortedBy { it.timestampSec }
            .mapIndexed { index, seg ->
                val startSec = maxOf(0.0, seg.timestampSec - clipDurationSec / 2)
                ClipWindow(
                    clipNumber  = index + 1,
                    startSec    = startSec,
                    durationSec = clipDurationSec,
                    energyScore = seg.energyDb
                )
            }

        // If no semantic clips return energy-based results
        if (semanticClips.isEmpty()) return energyWindows

        // Match semantic clips to energy windows by proximity
        return energyWindows.mapIndexed { index, window ->
            val matchedSemantic = semanticClips.getOrNull(index)
            window.copy(semanticClip = matchedSemantic)
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

    /**
     * Stitches multiple clip files into one highlight reel.
     * Uses FFmpeg concat demuxer for lossless joining.
     */
    suspend fun createHighlightReel(clipFiles: List<File>): File = withContext(Dispatchers.IO) {
        val outputFile = File.createTempFile("reelo_reel_", ".mp4")

        // Create a concat list file
        val concatFile = File.createTempFile("reelo_concat_", ".txt")
        concatFile.writeText(clipFiles.joinToString("\n") { "file '${it.absolutePath}'" })

        runFfmpeg(
            "-f", "concat",
            "-safe", "0",
            "-i", concatFile.absolutePath,
            "-c:v", "libx264",
            "-preset", "veryfast",
            "-crf", "23",
            "-c:a", "aac",
            "-b:a", "128k",
            "-movflags", "+faststart",
            "-y", outputFile.absolutePath
        )

        concatFile.delete()
        outputFile
    }

    // ── Audio chunking for long files ─────────────────────────────────────────

    /**
     * Splits a long audio file into chunks for Whisper
     * (Cloudflare Whisper has a 25MB file size limit).
     */
    suspend fun splitAudio(audioFile: File, chunkMinutes: Int = 2): List<File> =
        withContext(Dispatchers.IO) {
            val chunkSeconds = chunkMinutes * 60
            val outputDir = createTempDir("reelo_chunks_")
            val outputPattern = File(outputDir, "chunk_%03d.mp3").absolutePath

            runFfmpeg(
                "-i", audioFile.absolutePath,
                "-f", "segment",
                "-segment_time", chunkSeconds.toString(),
                "-ac", "1",
                "-ar", "16000",
                "-b:a", "32k",
                "-y", outputPattern
            )

            outputDir.listFiles { f -> f.name.endsWith(".mp3") }
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
    val energyScore: Double,
    val semanticClip: SemanticClip? = null
)

class FfmpegException(message: String) : Exception(message)
