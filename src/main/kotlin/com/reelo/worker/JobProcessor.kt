package com.reelo.worker

import com.reelo.db.repositories.ClipRepository
import com.reelo.db.repositories.JobRepository
import com.reelo.models.CaptionWord
import com.reelo.services.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File

class JobProcessor(
    private val jobRepo: JobRepository,
    private val clipRepo: ClipRepository,
    private val r2Service: R2Service,
    private val whisperService: WhisperService,
    private val ffmpegService: FfmpegService,
    private val redisQueue: RedisQueue,
    private val llmService: LlmService
) {
    private val log = LoggerFactory.getLogger(JobProcessor::class.java)

    fun start() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val activeJobs = java.util.concurrent.atomic.AtomicInteger(0)
        val maxConcurrent = 4

        kotlinx.coroutines.runBlocking {
            log.info("Worker started, watching Redis queue...")
            var backoffMs = 2_000L

            while (true) {
                if (activeJobs.get() >= maxConcurrent) {
                    delay(2_000)
                    continue
                }

                val jobId = redisQueue.pop()
                if (jobId == null) {
                    delay(backoffMs)
                    backoffMs = minOf(backoffMs * 2, 30_000)
                    continue
                }

                backoffMs = 2_000L
                activeJobs.incrementAndGet()

                scope.launch {
                    try {
                        processJob(jobId)
                    } catch (e: Exception) {
                        log.error("Job $jobId failed: ${e.message}", e)
                        jobRepo.updateStatus(jobId, "failed", errorCode = "PROCESSING_FAILED")
                    } finally {
                        activeJobs.decrementAndGet()
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

        // ── Resume after confirmation ─────────────────────────────────────────
        if (job.status == "confirmed") {
            processClipping(jobId, job)
            return
        }

        var videoFile: File? = null
        var audioFile: File? = null

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

            // ── Step 4: LLM metadata detection ───────────────────────────────
            jobRepo.updateStatus(jobId, "analyzing", "Understanding your content...", 40)
            val metadata = llmService.detectMetadata(transcript.text)
            log.info("Detected metadata for job $jobId: ${metadata.contentType}, topics=${metadata.topics}")

            // ── Step 5: Save transcript + metadata, pause for confirmation ────
            jobRepo.saveTranscriptAndMetadata(jobId, transcript.text, metadata)
            jobRepo.updateStatus(jobId, "awaiting_confirmation", "Review your content details", 50)
            log.info("Job $jobId awaiting user confirmation")

        } finally {
            videoFile?.delete()
            audioFile?.delete()
        }
    }

    suspend fun processClipping(jobId: String, job: com.reelo.db.repositories.JobRecord) {
        var videoFile: File? = null
        val clipFiles = mutableListOf<File>()

        try {
            // Re-download video for clipping
            jobRepo.updateStatus(jobId, "downloading", "Fetching your episode...", 55)
            videoFile = File.createTempFile("reelo_video_", ".mp4")
            r2Service.downloadFile(job.fileKey, videoFile)

            val audioFile = ffmpegService.extractAudio(videoFile)
            val transcript = jobRepo.getTranscript(jobId)
            val metadata = jobRepo.getMetadata(jobId)
            val extraContext = job.extraContext

            // ── Step 6: Energy analysis ───────────────────────────────────────
            jobRepo.updateStatus(jobId, "analyzing", "Finding the best moments...", 60)
            val energyMap        = ffmpegService.getAudioEnergyMap(audioFile)
            val totalDurationSec = ffmpegService.getAudioDurationMs(audioFile) / 1000.0

            // ── Step 7: Semantic clip selection ───────────────────────────────
            val semanticClips = llmService.findBestMoments(
                transcript   = transcript ?: "",
                metadata     = metadata,
                clipCount    = job.clipCount,
                extraContext = extraContext
            )
            log.info("LLM found ${semanticClips.size} semantic moments for job $jobId")

            // ── Step 8: Pick clip windows (energy + semantic combined) ─────────
            val clipWindows = ffmpegService.pickClipWindows(
                energyMap        = energyMap,
                totalDurationSec = totalDurationSec,
                clipCount        = job.clipCount,
                semanticClips    = semanticClips
            )
            log.info("Selected ${clipWindows.size} clip windows for job $jobId")

            if (clipWindows.isEmpty()) {
                jobRepo.updateStatus(jobId, "failed", errorCode = "NO_CLIPS_FOUND")
                return
            }

            // ── Step 9: Create episode record ─────────────────────────────────
            val episodeId = clipRepo.createEpisode(
                jobId            = jobId,
                sessionToken     = job.sessionToken,
                originalFilename = job.originalFilename,
                durationMs       = (totalDurationSec * 1000).toInt(),
                metadata         = metadata
            )

            // ── Step 10: Cut, upload, save each clip ──────────────────────────
            val progressPerClip = 30 / clipWindows.size
            clipWindows.forEachIndexed { index, window ->
                jobRepo.updateStatus(
                    jobId, "clipping",
                    "Creating clip ${index + 1} of ${clipWindows.size}...",
                    70 + (index * progressPerClip)
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
                    episodeId      = episodeId,
                    sessionToken   = job.sessionToken,
                    clipNumber     = window.clipNumber,
                    clipUrl        = clipUrl,
                    title          = window.semanticClip?.reason ?: extractClipTitle(emptyList(), window.startSec, window.startSec + window.durationSec),
                    transcript     = extractClipTranscript(emptyList(), window.startSec, window.startSec + window.durationSec),
                    energyScore    = window.energyScore,
                    durationMs     = (window.durationSec * 1000).toInt(),
                    clipStartS     = window.startSec,
                    emotion        = window.semanticClip?.emotion,
                    platform       = window.semanticClip?.platform
                )

                log.info("Clip ${window.clipNumber} uploaded: $clipUrl")
            }

            jobRepo.updateStatus(jobId, "done", "Your clips are ready!", 100)
            log.info("Job $jobId complete — ${clipWindows.size} clips")

            audioFile.delete()

        } finally {
            videoFile?.delete()
            clipFiles.forEach { it.delete() }
        }
    }

    private fun extractClipTitle(words: List<CaptionWord>, startSec: Double, endSec: Double): String? {
        val startMs = (startSec * 1000).toInt()
        val endMs   = (endSec   * 1000).toInt()
        return words.filter { it.startMs >= startMs && it.endMs <= endMs }
            .joinToString(" ") { it.word }.trim().take(80).ifBlank { null }
    }

    private fun extractClipTranscript(words: List<CaptionWord>, startSec: Double, endSec: Double): String? {
        val startMs = (startSec * 1000).toInt()
        val endMs   = (endSec   * 1000).toInt()
        return words.filter { it.startMs >= startMs && it.endMs <= endMs }
            .joinToString(" ") { it.word }.trim().ifBlank { null }
    }
}