package com.reelo.services

import com.reelo.models.CaptionWord
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.server.config.*
import kotlinx.serialization.Serializable
import java.io.File

class WhisperService(
    private val httpClient: HttpClient,
    config: ApplicationConfig
) {
    private val apiToken  = config.property("cloudflare.apiToken").getString()
    private val accountId = config.property("cloudflare.accountId").getString()
    private val endpoint  = "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/run/@cf/openai/whisper"

    suspend fun transcribe(audioFile: File): TranscriptResult {
        val bytes = audioFile.readBytes()
        val response: WhisperResponse = httpClient.post(endpoint) {
            header("Authorization", "Bearer $apiToken")
            header("Content-Type", "audio/mp3")
            setBody(bytes)
        }.body()

        val result = response.result
            ?: throw IllegalStateException("Whisper returned no result. success=${response.success}")

        return TranscriptResult(
            text  = result.text ?: "",
            words = result.words?.map {
                CaptionWord(
                    word    = it.word,
                    startMs = (it.start * 1000).toInt(),
                    endMs   = (it.end * 1000).toInt()
                )
            } ?: emptyList()
        )
    }

    suspend fun transcribeLong(audioFile: File, ffmpegService: FfmpegService): TranscriptResult {
        val chunks = ffmpegService.splitAudio(audioFile, chunkMinutes = 2)
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

@Serializable
private data class WhisperResponse(
    val result: WhisperResult? = null,
    val success: Boolean = false
)

@Serializable
private data class WhisperResult(
    val text: String? = null,
    val words: List<WhisperWord>? = null
)

@Serializable
private data class WhisperWord(
    val word: String,
    val start: Double,
    val end: Double
)