package com.reelo.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object Jobs : Table("jobs") {
    val id               = uuid("id").autoGenerate()
    val sessionToken     = text("session_token")
    val status           = text("status").default("queued")
    val currentStep      = text("current_step").nullable()
    val errorCode        = text("error_code").nullable()
    val fileKey          = text("file_key")
    val originalFilename = text("original_filename").default("")
    val clipCount        = integer("clip_count").default(6)
    val progress         = integer("progress").default(0)
    val createdAt        = timestamp("created_at")
    val updatedAt        = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object Episodes : Table("episodes") {
    val id               = uuid("id").autoGenerate()
    val jobId            = uuid("job_id").references(Jobs.id)
    val sessionToken     = text("session_token")
    val title            = text("title").nullable()
    val originalFilename = text("original_filename")
    val durationMs       = integer("duration_ms").nullable()
    val createdAt        = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object Clips : Table("clips") {
    val id           = uuid("id").autoGenerate()
    val episodeId    = uuid("episode_id").references(Episodes.id)
    val sessionToken = text("session_token")
    val clipNumber   = integer("clip_number")
    val clipUrl      = text("clip_url")
    val thumbnailUrl = text("thumbnail_url").nullable()
    val title        = text("title").nullable()        // auto from transcript
    val transcript   = text("transcript").nullable()   // clip text for captions
    val energyScore  = double("energy_score")
    val durationMs   = integer("duration_ms")
    val clipStartS   = double("clip_start_s")
    val emotion      = text("emotion").nullable()      // v2
    val emotionScore = double("emotion_score").nullable() // v2
    val createdAt    = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object Captions : Table("captions") {
    val id        = uuid("id").autoGenerate()
    val clipId    = uuid("clip_id").references(Clips.id)
    val language  = text("language").default("en")
    val words     = text("words")   // stored as JSON string
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}
