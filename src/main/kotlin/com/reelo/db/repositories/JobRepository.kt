package com.reelo.db.repositories

import com.reelo.db.dbQuery
import com.reelo.db.tables.Jobs
import com.reelo.db.tables.TranscriptWords
import com.reelo.models.CaptionWord
import com.reelo.models.JobResponse
import com.reelo.services.VideoMetadata
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.Instant
import java.util.UUID

data class JobRecord(
    val id: String,
    val sessionToken: String,
    val fileKey: String,
    val originalFilename: String,
    val clipCount: Int,
    val status: String,
    val extraContext: String? = null,
    val transcript: String? = null,
    val userId: String? = null
)

class JobRepository {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun create(
        sessionToken: String,
        fileKey: String,
        originalFilename: String,
        clipCount: Int,
        userId: String? = null
    ): JobResponse = dbQuery {
        val id = Jobs.insert {
            it[Jobs.sessionToken]     = sessionToken
            it[Jobs.fileKey]          = fileKey
            it[Jobs.originalFilename] = originalFilename
            it[Jobs.clipCount]        = clipCount
            it[Jobs.status]           = "queued"
            it[Jobs.userId]           = userId?.let { UUID.fromString(it) }
            it[Jobs.createdAt]        = Instant.now()
            it[Jobs.updatedAt]        = Instant.now()
        } get Jobs.id

        JobResponse(
            id           = id.toString(),
            sessionToken = sessionToken,
            status       = "queued",
            progress     = 0
        )
    }

    suspend fun findById(jobId: String, sessionToken: String): JobResponse? = dbQuery {
        Jobs.select {
            (Jobs.id eq UUID.fromString(jobId)) and (Jobs.sessionToken eq sessionToken)
        }.mapNotNull { it.toJobResponse() }.firstOrNull()
    }

    suspend fun findRecordById(jobId: String): JobRecord? = dbQuery {
        Jobs.select { Jobs.id eq UUID.fromString(jobId) }
            .mapNotNull {
                JobRecord(
                    id               = it[Jobs.id].toString(),
                    sessionToken     = it[Jobs.sessionToken],
                    fileKey          = it[Jobs.fileKey],
                    originalFilename = it[Jobs.originalFilename],
                    clipCount        = it[Jobs.clipCount],
                    status           = it[Jobs.status],
                    extraContext     = it[Jobs.extraContext],
                    transcript       = it[Jobs.transcript],
                    userId           = it[Jobs.userId]?.toString()
                )
            }.firstOrNull()
    }

    suspend fun updateStatus(
        jobId: String,
        status: String,
        currentStep: String? = null,
        progress: Int = 0,
        errorCode: String? = null
    ) = dbQuery {
        Jobs.update({ Jobs.id eq UUID.fromString(jobId) }) {
            it[Jobs.status]      = status
            it[Jobs.currentStep] = currentStep
            it[Jobs.progress]    = progress
            it[Jobs.errorCode]   = errorCode
            it[Jobs.updatedAt]   = Instant.now()
        }
    }

    suspend fun saveTranscriptWords(jobId: String, words: List<CaptionWord>) = dbQuery {
        TranscriptWords.batchInsert(words) { word ->
            this[TranscriptWords.jobId]   = UUID.fromString(jobId)
            this[TranscriptWords.word]    = word.word
            this[TranscriptWords.startMs] = word.startMs
            this[TranscriptWords.endMs]   = word.endMs
        }
    }

    suspend fun saveTranscriptAndMetadata(
        jobId: String,
        transcript: String,
        metadata: VideoMetadata
    ) = dbQuery {
        Jobs.update({ Jobs.id eq UUID.fromString(jobId) }) {
            it[Jobs.transcript]          = transcript
            it[Jobs.detectedTopics]      = json.encodeToString(metadata.topics)
            it[Jobs.detectedContentType] = metadata.contentType
            it[Jobs.detectedTone]        = metadata.tone
            it[Jobs.detectedAudience]    = metadata.audience
            it[Jobs.updatedAt]           = Instant.now()
        }
    }

    suspend fun getTranscriptWordsForWindow(
        jobId: String,
        startMs: Int,
        endMs: Int
    ): List<CaptionWord> = dbQuery {
        TranscriptWords.select {
            (TranscriptWords.jobId eq UUID.fromString(jobId)) and
                    (TranscriptWords.startMs greaterEq startMs) and
                    (TranscriptWords.endMs lessEq endMs)
        }.orderBy(TranscriptWords.startMs, SortOrder.ASC)
            .map { CaptionWord(it[TranscriptWords.word], it[TranscriptWords.startMs], it[TranscriptWords.endMs]) }
    }

    suspend fun confirmJob(
        jobId: String,
        extraContext: String?
    ) = dbQuery {
        Jobs.update({ Jobs.id eq UUID.fromString(jobId) }) {
            it[Jobs.status]       = "confirmed"
            it[Jobs.extraContext] = extraContext
            it[Jobs.confirmedAt]  = Instant.now()
            it[Jobs.updatedAt]    = Instant.now()
        }
    }

    suspend fun getTranscript(jobId: String): String? = dbQuery {
        Jobs.select { Jobs.id eq UUID.fromString(jobId) }
            .firstOrNull()?.get(Jobs.transcript)
    }

    suspend fun getMetadata(jobId: String): VideoMetadata = dbQuery {
        val row = Jobs.select { Jobs.id eq UUID.fromString(jobId) }.firstOrNull()
            ?: return@dbQuery VideoMetadata()
        VideoMetadata(
            topics      = row[Jobs.detectedTopics]?.let { json.decodeFromString(it) } ?: emptyList(),
            contentType = row[Jobs.detectedContentType] ?: "other",
            tone        = row[Jobs.detectedTone] ?: "conversational",
            audience    = row[Jobs.detectedAudience] ?: ""
        )
    }

    suspend fun claimJobsBySession(sessionToken: String, userId: String) = dbQuery {
        Jobs.update({
            (Jobs.sessionToken eq sessionToken) and Jobs.userId.isNull()
        }) {
            it[Jobs.userId]    = UUID.fromString(userId)
            it[Jobs.updatedAt] = Instant.now()
        }
    }

    suspend fun getJobsByUserId(userId: String): List<JobResponse> = dbQuery {
        Jobs.select { Jobs.userId eq UUID.fromString(userId) }
            .orderBy(Jobs.createdAt, SortOrder.DESC)
            .map { it.toJobResponse() }
    }

    suspend fun getQueuedJobs(): List<String> = dbQuery {
        Jobs.select { Jobs.status eq "queued" }
            .orderBy(Jobs.createdAt, SortOrder.ASC)
            .limit(10)
            .map { it[Jobs.id].toString() }
    }

    private fun ResultRow.toJobResponse() = JobResponse(
        id           = this[Jobs.id].toString(),
        sessionToken = this[Jobs.sessionToken],
        status       = this[Jobs.status],
        currentStep  = this[Jobs.currentStep],
        progress     = this[Jobs.progress],
        errorCode    = this[Jobs.errorCode],
        clipCount    = this[Jobs.clipCount]
    )
}