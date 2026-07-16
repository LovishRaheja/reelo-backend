package com.reelo.services

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.config.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.ceil
import kotlin.math.min

class LlmService(
    private val httpClient: HttpClient,
    config: ApplicationConfig
) {
    private val apiToken  = config.property("cloudflare.apiToken").getString()
    private val accountId = config.property("cloudflare.accountId").getString()
    private val endpoint  = "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/run/@cf/meta/llama-3.1-8b-instruct"

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun detectMetadata(transcript: String): VideoMetadata {
        val prompt = """
            Analyse this video transcript and return a JSON object with exactly these fields:
            - topics: array of 3-5 main topics as short strings
            - contentType: one of "podcast", "interview", "talk", "tutorial", "stream", "other"
            - tone: one of "educational", "entertaining", "inspirational", "controversial", "conversational"
            - audience: short description of target audience
            - language: detected language name in English
            
            Return ONLY valid JSON, no explanation, no markdown, no backticks.
            
            Transcript:
            ${transcript.take(3000)}
        """.trimIndent()

        val response: LlamaResponse = httpClient.post(endpoint) {
            header("Authorization", "Bearer $apiToken")
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "${prompt.replace("\"", "\\\"").replace("\n", "\\n")}", "max_tokens": 300}""")
        }.body()

        return try {
            val result = response.result?.response ?: return VideoMetadata()
            json.decodeFromString(VideoMetadata.serializer(), result.trim())
        } catch (e: Exception) {
            VideoMetadata()
        }
    }

    suspend fun findBestMoments(
        transcript: String,
        metadata: VideoMetadata,
        clipCount: Int,
        totalDurationSec: Double = 0.0,
        extraContext: String? = null
    ): List<SemanticClip> {
        val contextLine = if (!extraContext.isNullOrBlank())
            "Additional context from the creator: $extraContext"
        else ""

        // Single call for short videos (under 10 minutes)
        if (totalDurationSec <= 600.0 || totalDurationSec == 0.0) {
            return callLlmForChunk(
                chunkTranscript = transcript.take(4000),
                metadata        = metadata,
                clipsNeeded     = clipCount,
                contextLine     = contextLine
            )
        }

        // Chunked approach for longer videos
        // Number of chunks = clip count (1 clip per chunk), capped by actual 10-min segments
        val maxChunksByDuration = ceil(totalDurationSec / 600.0).toInt()
        val numberOfChunks = min(clipCount, maxChunksByDuration)
        val charsPerChunk = transcript.length / numberOfChunks

        val allMoments = mutableListOf<SemanticClip>()

        for (i in 0 until numberOfChunks) {
            val start = i * charsPerChunk
            val end = min(start + charsPerChunk, transcript.length)
            val chunkText = transcript.substring(start, end)

            val moments = callLlmForChunk(
                chunkTranscript = chunkText,
                metadata        = metadata,
                clipsNeeded     = 1,
                contextLine     = contextLine
            )
            allMoments.addAll(moments)
        }

        return allMoments.take(clipCount)
    }

    private suspend fun callLlmForChunk(
        chunkTranscript: String,
        metadata: VideoMetadata,
        clipsNeeded: Int,
        contextLine: String
    ): List<SemanticClip> {
        val prompt = """
        This is a ${metadata.contentType} about ${metadata.topics.joinToString(", ")}.
        Tone: ${metadata.tone}. Audience: ${metadata.audience}.
        $contextLine
        
        Find the $clipsNeeded most engaging, shareable moment(s) from this transcript section.
        Each moment should work as a standalone social media clip.
        
        Return a JSON array with exactly $clipsNeeded object(s), each with:
        - startWord: the first few words of the clip moment (exact quote from transcript)
        - endWord: the last few words of the clip moment (exact quote from transcript)
        - reason: one sentence why this moment is engaging
        - emotion: one of "excited", "insightful", "funny", "emotional", "controversial", "inspiring"
        - platform: best platform — "linkedin", "tiktok", "instagram", "youtube_shorts"
        
        Return ONLY valid JSON array, no explanation, no markdown, no backticks.
        
        Transcript section:
        $chunkTranscript
        """.trimIndent()

        val response: LlamaResponse = httpClient.post(endpoint) {
            header("Authorization", "Bearer $apiToken")
            contentType(ContentType.Application.Json)
            setBody("""{"prompt": "${prompt.replace("\"", "\\\"").replace("\n", "\\n")}", "max_tokens": 400}""")
        }.body()

        return try {
            val result = response.result?.response ?: return emptyList()
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(SemanticClip.serializer()),
                result.trim()
            )
        } catch (e: Exception) {
            emptyList()
        }
    }
}

@Serializable
data class VideoMetadata(
    val topics: List<String> = emptyList(),
    val contentType: String = "other",
    val tone: String = "conversational",
    val audience: String = "",
    val language: String = "English"
)

@Serializable
data class SemanticClip(
    val startWord: String = "",
    val endWord: String = "",
    val reason: String = "",
    val emotion: String = "",
    val platform: String = ""
)

@Serializable
private data class LlamaResponse(
    val result: LlamaResult? = null,
    val success: Boolean = false
)

@Serializable
private data class LlamaResult(
    val response: String? = null
)