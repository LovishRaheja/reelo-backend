package com.reelo.routes

import com.reelo.models.SignUploadRequest
import com.reelo.models.SignUploadResponse
import com.reelo.services.R2Service
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import java.util.UUID

fun Route.uploadRoutes() {
    val r2Service by inject<R2Service>()

    route("/uploads") {

        /**
         * POST /api/v1/uploads/sign
         *
         * Frontend sends file metadata, backend returns a signed R2 URL.
         * The browser then PUTs the file directly to R2 — the backend
         * never handles the video bytes.
         *
         * Request:  { fileName, fileSize, contentType, sessionToken }
         * Response: { uploadUrl, fileKey }
         */
        post("/sign") {
            val body = call.receive<SignUploadRequest>()

            // Validate
            require(body.sessionToken.isNotBlank()) { "sessionToken is required" }
            require(body.fileName.isNotBlank())     { "fileName is required" }
            require(body.fileSize > 0)              { "fileSize must be > 0" }
            require(body.fileSize <= 4L * 1024 * 1024 * 1024) { "File too large — max 4GB" }

            val allowedTypes = listOf("video/mp4", "audio/mpeg", "audio/mp3", "audio/wav", "video/quicktime")
            require(body.contentType in allowedTypes) {
                "Unsupported file type. Allowed: MP4, MP3, WAV, MOV"
            }

            // Build a stable R2 key for this upload
            val jobId    = UUID.randomUUID().toString()
            val ext      = body.fileName.substringAfterLast(".", "mp4")
            val fileKey  = r2Service.rawFileKey(body.sessionToken, jobId, ext)

            // Generate short-lived signed URL (15 min)
            val uploadUrl = r2Service.generateUploadUrl(fileKey, body.contentType)

            call.respond(
                HttpStatusCode.OK,
                SignUploadResponse(
                    uploadUrl = uploadUrl,
                    fileKey   = fileKey
                )
            )
        }
    }
}
