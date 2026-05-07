package com.reelo.db.repositories

import com.reelo.db.dbQuery
import com.reelo.db.tables.Clips
import com.reelo.db.tables.Episodes
import com.reelo.models.ClipResponse
import com.reelo.models.EpisodeResponse
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.Instant
import java.util.UUID

class ClipRepository {

    suspend fun createEpisode(
        jobId: String,
        sessionToken: String,
        originalFilename: String,
        durationMs: Int?
    ): String = dbQuery {
        Episodes.insert {
            it[Episodes.jobId]            = UUID.fromString(jobId)
            it[Episodes.sessionToken]     = sessionToken
            it[Episodes.originalFilename] = originalFilename
            it[Episodes.durationMs]       = durationMs
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
        clipStartS: Double
    ): String = dbQuery {
        Clips.insert {
            it[Clips.episodeId]    = UUID.fromString(episodeId)
            it[Clips.sessionToken] = sessionToken
            it[Clips.clipNumber]   = clipNumber
            it[Clips.clipUrl]      = clipUrl
            it[Clips.title]        = title
            it[Clips.transcript]   = transcript
            it[Clips.energyScore]  = energyScore
            it[Clips.durationMs]   = durationMs
            it[Clips.clipStartS]   = clipStartS
            it[Clips.createdAt]    = Instant.now()
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

        EpisodeResponse(
            id               = episode[Episodes.id].toString(),
            title            = episode[Episodes.title],
            originalFilename = episode[Episodes.originalFilename],
            durationMs       = episode[Episodes.durationMs],
            clips            = clips,
            createdAt        = episode[Episodes.createdAt].toString()
        )
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

        EpisodeResponse(
            id               = episode[Episodes.id].toString(),
            title            = episode[Episodes.title],
            originalFilename = episode[Episodes.originalFilename],
            durationMs       = episode[Episodes.durationMs],
            clips            = clips,
            createdAt        = episode[Episodes.createdAt].toString()
        )
    }

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
        emotionScore = this[Clips.emotionScore]
    )
}
