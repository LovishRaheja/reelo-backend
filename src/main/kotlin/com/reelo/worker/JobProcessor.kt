package com.reelo.worker

import com.reelo.db.repositories.ClipRepository
import com.reelo.db.repositories.JobRepository
import com.reelo.models.CaptionWord
import com.reelo.services.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.slf4j.LoggerFactory
import java.io.File

class JobProcessor(
    private val jobRepo: JobRepository,
    private val clipRepo: ClipRepository,
    private val r2Service: R2Service,
    private val whisperService: WhisperService,
    private val ffmpegService: FfmpegService,
    private val redisQueue: RedisQueue
) {
    private val log = LoggerFactory.getLogger(JobProcessor::class.java)

    /** Main loop — runs forever, picks jobs from Redis one by one. */
    fun start() = kotlinx.coroutines.runBlocking {
        log.info("Worker started, watching Redis queue...")
        var backoffMs = 2_000L
        while (true) {
            val jobId = redisQueue.pop()
            if (jobId == null) {
                delay(backoffMs)
                backoffMs = minOf(backoffMs * 2, 30_000)
                continue
            }
            backoffMs = 2_000L
            supervisorScope {
                launch {
                    try {
                        processJob(jobId)
                    } catch (e: Exception) {
                        log.error("Job $jobId failed: ${e.message}", e)
                        jobRepo.updateStatus(jobId, "failed", errorCode = "PROCESSING_FAILED")
                    }
                }
            }
        }
    }

    private suspend fun processJob(jobId: String) {
        log.info("Processing job $jobId")

        val job = jobRepo.findRecordById(jobId) ?: run {
            log.warn("Job $jobId not found in DB — skipping")
            return
        }

        var videoFile: File? = null
        var audioFile: File? = null
        val clipFiles = mutableListOf<File>()

        try {
            // ── Step 1: Download ──────────────────────────────────────────────
            jobRepo.updateStatus(jobId, "downloading", "Fetching your episode...", 10)
            videoFile = File.createTempFile("reelo_video_", ".mp4")
            r2Service.downloadFile(job.fileKey, videoFile)
            log.info("Downloaded ${videoFile.length() / 1024}KB for job $jobId")

            // ── Step 2: Extract audio ─────────────────────────────────────────
            jobRepo.updateStatus(jobId, "transcribing", "Listening to your episode...", 25)
            audioFile = ffmpegService.extractAudio(videoFile)

            // ── Step 3: Whisper transcription ─────────────────────────────────
            val transcript = if (audioFile.length() > 9 * 1024 * 1024) {
                log.info("Long audio — chunking for Whisper")
                whisperService.transcribeLong(audioFile, ffmpegService)
            } else {
                whisperService.transcribe(audioFile)
            }
            log.info("Transcribed ${transcript.words.size} words for job $jobId")

            // ── Step 4: Audio energy analysis ─────────────────────────────────
            jobRepo.updateStatus(jobId, "analyzing", "Finding the best moments...", 45)
            val energyMap        = ffmpegService.getAudioEnergyMap(audioFile)
            val totalDurationSec = ffmpegService.getAudioDurationMs(audioFile) / 1000.0
            val clipWindows      = ffmpegService.pickClipWindows(
                energyMap        = energyMap,
                totalDurationSec = totalDurationSec,
                clipCount        = job.clipCount
            )
            log.info("Selected ${clipWindows.size} clip windows for job $jobId")

            if (clipWindows.isEmpty()) {
                jobRepo.updateStatus(jobId, "failed", errorCode = "NO_CLIPS_FOUND")
                log.warn("No clip windows selected for job $jobId — audio too short or silent")
                return
            }

            // ── Step 5: Create episode record ─────────────────────────────────
            val episodeId = clipRepo.createEpisode(
                jobId            = jobId,
                sessionToken     = job.sessionToken,
                originalFilename = job.originalFilename,
                durationMs       = (totalDurationSec * 1000).toInt()
            )

            // ── Step 6: Cut, upload, and save each clip ───────────────────────
            val progressPerClip = 40 / clipWindows.size
            clipWindows.forEachIndexed { index, window ->
                jobRepo.updateStatus(
                    jobId, "clipping",
                    "Creating clip ${index + 1} of ${clipWindows.size}...",
                    50 + (index * progressPerClip)
                )

                val clipFile = ffmpegService.cutClip(
                    inputFile   = videoFile,
                    startSec    = window.startSec,
                    durationSec = window.durationSec
                )
                clipFiles.add(clipFile)

                val clipKey = r2Service.clipFileKey(job.sessionToken, jobId, window.clipNumber)
                val clipUrl = r2Service.uploadFile(clipFile, clipKey, "video/mp4")

                clipRepo.createClip(
                    episodeId    = episodeId,
                    sessionToken = job.sessionToken,
                    clipNumber   = window.clipNumber,
                    clipUrl      = clipUrl,
                    title        = extractClipTitle(transcript.words, window.startSec, window.startSec + window.durationSec),
                    transcript   = extractClipTranscript(transcript.words, window.startSec, window.startSec + window.durationSec),
                    energyScore  = window.energyScore,
                    durationMs   = (window.durationSec * 1000).toInt(),
                    clipStartS   = window.startSec
                )

                log.info("Clip ${window.clipNumber} uploaded: $clipUrl")
            }

            // ── Step 7: Done ──────────────────────────────────────────────────
            jobRepo.updateStatus(jobId, "done", "Your clips are ready!", 100)
            log.info("Job $jobId complete — ${clipWindows.size} clips")

        } finally {
            videoFile?.delete()
            audioFile?.delete()
            clipFiles.forEach { it.delete() }
        }
    }

    /**
     * Extracts the words spoken inside a clip window and returns them
     * as a sentence — becomes the clip's auto-generated title.
     */
    private fun extractClipTitle(
        words: List<CaptionWord>,
        startSec: Double,
        endSec: Double
    ): String? {
        val startMs = (startSec * 1000).toInt()
        val endMs   = (endSec   * 1000).toInt()
        val text = words
            .filter { it.startMs >= startMs && it.endMs <= endMs }
            .joinToString(" ") { it.word }
            .trim()
        return text.take(80).ifBlank { null }
    }

    /**
     * Same as title but full length — shown as copy-paste caption
     * on the results screen.
     */
    private fun extractClipTranscript(
        words: List<CaptionWord>,
        startSec: Double,
        endSec: Double
    ): String? {
        val startMs = (startSec * 1000).toInt()
        val endMs   = (endSec   * 1000).toInt()
        return words
            .filter { it.startMs >= startMs && it.endMs <= endMs }
            .joinToString(" ") { it.word }
            .trim()
            .ifBlank { null }
    }
}
