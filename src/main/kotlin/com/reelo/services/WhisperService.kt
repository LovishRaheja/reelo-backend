package com.reelo.services

import com.reelo.models.CaptionWord
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.server.config.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

class WhisperService(
    private val httpClient: HttpClient,
    config: ApplicationConfig
) {
    private val apiToken  = config.property("cloudflare.apiToken").getString()
    private val accountId = config.property("cloudflare.accountId").getString()
    private val endpoint  = "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/run/@cf/openai/whisper"

    /**
     * Transcribes an audio file and returns word-level timestamps.
     * For files > 25MB, call transcribeLong() which chunks the audio first.
     */
    suspend fun transcribe(audioFile: File): TranscriptResult {
        val response: WhisperResponse = httpClient.post(endpoint) {
            header("Authorization", "Bearer $apiToken")
            setBody(MultiPartFormDataContent(
                formData {
                    append("audio", audioFile.readBytes(), Headers.build {
                        append(HttpHeaders.ContentType, "audio/wav")
                        append(HttpHeaders.ContentDisposition, "filename=\"audio.wav\"")
                    })
                }
            ))
        }.body()

        return TranscriptResult(
            text  = response.result.text,
            words = response.result.words?.map {
                CaptionWord(
                    word    = it.word,
                    startMs = (it.start * 1000).toInt(),
                    endMs   = (it.end * 1000).toInt()
                )
            } ?: emptyList()
        )
    }

    /**
     * For long podcast audio files — splits into 10-min chunks,
     * transcribes each, stitches results with correct time offsets.
     */
    suspend fun transcribeLong(audioFile: File, ffmpegService: FfmpegService): TranscriptResult {
        val chunks = ffmpegService.splitAudio(audioFile, chunkMinutes = 10)
        var timeOffsetMs = 0
        val allWords = mutableListOf<CaptionWord>()
        val fullText = StringBuilder()

        try {
            chunks.forEach { chunk ->
                val result = transcribe(chunk)
                fullText.append(result.text).append(" ")
                result.words.forEach { word ->
                    allWords.add(word.copy(
                        startMs = word.startMs + timeOffsetMs,
                        endMs   = word.endMs   + timeOffsetMs
                    ))
                }
                timeOffsetMs += ffmpegService.getAudioDurationMs(chunk)
            }
        } finally {
            chunks.forEach { it.delete() }
        }

        return TranscriptResult(text = fullText.trim().toString(), words = allWords)
    }
}

data class TranscriptResult(
    val text: String,
    val words: List<CaptionWord>
)

// ── Cloudflare response shapes ────────────────────────────────────────────────

@Serializable
private data class WhisperResponse(val result: WhisperResult)

@Serializable
private data class WhisperResult(
    val text: String,
    val words: List<WhisperWord>? = null
)

@Serializable
private data class WhisperWord(
    val word: String,
    val start: Double,
    val end: Double
)
