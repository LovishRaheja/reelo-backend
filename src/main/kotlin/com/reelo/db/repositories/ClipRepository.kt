package com.reelo.db.repositories

import com.reelo.db.dbQuery
import com.reelo.db.tables.Clips
import com.reelo.db.tables.Episodes
import com.reelo.models.ClipResponse
import com.reelo.models.EpisodeResponse
import com.reelo.services.VideoMetadata
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.Instant
import java.util.UUID

class ClipRepository {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun createEpisode(
        jobId: String,
        sessionToken: String,
        originalFilename: String,
        durationMs: Int?,
        metadata: VideoMetadata = VideoMetadata()
    ): String = dbQuery {
        Episodes.insert {
            it[Episodes.jobId]            = UUID.fromString(jobId)
            it[Episodes.sessionToken]     = sessionToken
            it[Episodes.originalFilename] = originalFilename
            it[Episodes.durationMs]       = durationMs
            it[Episodes.topics]           = json.encodeToString(metadata.topics)
            it[Episodes.contentType]      = metadata.contentType
            it[Episodes.tone]             = metadata.tone
            it[Episodes.audience]         = metadata.audience
            it[Episodes.createdAt]        = Instant.now()
        } get Episodes.id
    }.toString()

    suspend fun createClip(
        episodeId: String,
        sessionToken: String,
        clipNumber: Int,
        clipUrl: String,
        title: String?,
        transcript: String?,
        energyScore: Double,
        durationMs: Int,
        clipStartS: Double,
        emotion: String? = null,
        platform: String? = null
    ): String = dbQuery {
        Clips.insert {
            it[Clips.episodeId]      = UUID.fromString(episodeId)
            it[Clips.sessionToken]   = sessionToken
            it[Clips.clipNumber]     = clipNumber
            it[Clips.clipUrl]        = clipUrl
            it[Clips.title]          = title
            it[Clips.transcript]     = transcript
            it[Clips.energyScore]    = energyScore
            it[Clips.durationMs]     = durationMs
            it[Clips.clipStartS]     = clipStartS
            it[Clips.emotion]        = emotion
            it[Clips.platform]       = platform
            it[Clips.createdAt]      = Instant.now()
        } get Clips.id
    }.toString()

    suspend fun getEpisode(episodeId: String, sessionToken: String): EpisodeResponse? = dbQuery {
        val episode = Episodes.select {
            (Episodes.id eq UUID.fromString(episodeId)) and
                    (Episodes.sessionToken eq sessionToken)
        }.firstOrNull() ?: return@dbQuery null

        val clips = Clips.select { Clips.episodeId eq UUID.fromString(episodeId) }
            .orderBy(Clips.clipNumber, SortOrder.ASC)
            .map { it.toClipResponse() }

        episode.toEpisodeResponse(clips)
    }

    suspend fun getEpisodeForClip(clipId: String, sessionToken: String): EpisodeResponse? {
        val episodeId = dbQuery {
            Clips.select {
                (Clips.id eq UUID.fromString(clipId)) and (Clips.sessionToken eq sessionToken)
            }.firstOrNull()?.get(Clips.episodeId)?.toString()
        } ?: return null

        return getEpisode(episodeId, sessionToken)
    }

    suspend fun getEpisodeByJobId(jobId: String): EpisodeResponse? = dbQuery {
        val episode = Episodes.select { Episodes.jobId eq UUID.fromString(jobId) }
            .firstOrNull() ?: return@dbQuery null

        val clips = Clips.select { Clips.episodeId eq episode[Episodes.id] }
            .orderBy(Clips.clipNumber, SortOrder.ASC)
            .map { it.toClipResponse() }

        episode.toEpisodeResponse(clips)
    }

    private fun ResultRow.toEpisodeResponse(clips: List<ClipResponse>) = EpisodeResponse(
        id               = this[Episodes.id].toString(),
        title            = this[Episodes.title],
        originalFilename = this[Episodes.originalFilename],
        durationMs       = this[Episodes.durationMs],
        clips            = clips,
        createdAt        = this[Episodes.createdAt].toString()
    )

    private fun ResultRow.toClipResponse() = ClipResponse(
        id           = this[Clips.id].toString(),
        clipNumber   = this[Clips.clipNumber],
        clipUrl      = this[Clips.clipUrl],
        thumbnailUrl = this[Clips.thumbnailUrl],
        title        = this[Clips.title],
        transcript   = this[Clips.transcript],
        energyScore  = this[Clips.energyScore],
        durationMs   = this[Clips.durationMs],
        emotion      = this[Clips.emotion],
        emotionScore = this[Clips.emotionScore],
        platform     = this[Clips.platform]
    )
}