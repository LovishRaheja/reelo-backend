package com.reelo.models

import kotlinx.serialization.Serializable

// ── Job ───────────────────────────────────────────────────────────────────────

@Serializable
data class CreateJobRequest(
    val fileKey: String,
    val originalFilename: String,
    val sessionToken: String,
    val clipCount: Int = 6
)

@Serializable
data class JobResponse(
    val id: String,
    val sessionToken: String,
    val status: String,
    val currentStep: String? = null,
    val progress: Int = 0,
    val estimatedSeconds: Int? = null,
    val errorCode: String? = null,
    val episode: EpisodeResponse? = null   // populated when status = "done"
)

// ── Upload ────────────────────────────────────────────────────────────────────

@Serializable
data class SignUploadRequest(
    val fileName: String,
    val fileSize: Long,
    val contentType: String,
    val sessionToken: String
)

@Serializable
data class SignUploadResponse(
    val uploadUrl: String,
    val fileKey: String
)

// ── Episode ───────────────────────────────────────────────────────────────────

@Serializable
data class EpisodeResponse(
    val id: String,
    val title: String?,
    val originalFilename: String,
    val durationMs: Int?,
    val clips: List<ClipResponse>,
    val createdAt: String
)

// ── Clip ──────────────────────────────────────────────────────────────────────

@Serializable
data class ClipResponse(
    val id: String,
    val clipNumber: Int,
    val clipUrl: String,
    val thumbnailUrl: String?,
    val title: String?,           // auto-generated from transcript
    val transcript: String?,      // full text of the clip — copy as caption
    val energyScore: Double,
    val durationMs: Int,
    val emotion: String? = null,  // null in v1, filled in v2
    val emotionScore: Double? = null
)

// ── Captions ──────────────────────────────────────────────────────────────────

@Serializable
data class CaptionWord(
    val word: String,
    val startMs: Int,
    val endMs: Int
)

// ── Errors ────────────────────────────────────────────────────────────────────

@Serializable
data class ErrorResponse(
    val error: ErrorDetail
)

@Serializable
data class ErrorDetail(
    val code: String,
    val message: String
)
