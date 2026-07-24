package com.reelo.routes

import com.reelo.db.repositories.ClipRepository
import com.reelo.db.repositories.JobRepository
import com.reelo.models.CreateJobRequest
import com.reelo.models.ErrorDetail
import com.reelo.models.ErrorResponse
import com.reelo.worker.RedisQueue
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject

@Serializable
data class JobMetadataResponse(
    val topics: List<String>,
    val contentType: String,
    val tone: String,
    val audience: String
)

fun Route.jobRoutes() {
    val jobRepo    by inject<JobRepository>()
    val clipRepo   by inject<ClipRepository>()
    val redisQueue by inject<RedisQueue>()

    route("/jobs") {

        post {
            val body = call.receive<CreateJobRequest>()

            require(body.fileKey.isNotBlank()) { "fileKey is required" }
            require(body.originalFilename.isNotBlank()) { "originalFilename is required" }
            require(body.sessionToken.isNotBlank())     { "sessionToken is required" }
            require(body.clipCount in 1..10)            { "clipCount must be between 1 and 10" }

            val userId = extractUserIdFromJwt(call)

            val job = jobRepo.create(
                sessionToken     = body.sessionToken,
                fileKey          = body.fileKey,
                originalFilename = body.originalFilename,
                clipCount        = body.clipCount,
                userId           = userId
            )

            redisQueue.push(job.id)
            call.respond(HttpStatusCode.Created, job)
        }

        get("/{id}") {
            val jobId = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest,
                    ErrorResponse(ErrorDetail("MISSING_ID", "Job ID is required")))

            val sessionToken = call.request.queryParameters["session"]
                ?: return@get call.respond(HttpStatusCode.BadRequest,
                    ErrorResponse(ErrorDetail("MISSING_SESSION", "session query param is required")))

            val job = jobRepo.findById(jobId, sessionToken)
                ?: return@get call.respond(HttpStatusCode.NotFound,
                    ErrorResponse(ErrorDetail("NOT_FOUND", "Job not found")))

            val response = if (job.status == "done") {
                val episode = clipRepo.getEpisodeByJobId(jobId)
                job.copy(episode = episode)
            } else {
                job
            }

            val withEstimate = response.copy(
                estimatedSeconds = when (job.status) {
                    "queued"                  -> 300
                    "downloading"             -> 240
                    "transcribing"            -> 180
                    "analyzing"               -> 120
                    "awaiting_confirmation"   -> null
                    "confirmed"               -> 60
                    "clipping"                -> 60
                    "uploading"               -> 20
                    else                      -> null
                }
            )

            call.respond(HttpStatusCode.OK, withEstimate)
        }

        get("/{id}/metadata") {
            val jobId = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest,
                    ErrorResponse(ErrorDetail("MISSING_ID", "Job ID is required")))

            val sessionToken = call.request.queryParameters["session"]
                ?: return@get call.respond(HttpStatusCode.BadRequest,
                    ErrorResponse(ErrorDetail("MISSING_SESSION", "session query param is required")))

            jobRepo.findById(jobId, sessionToken)
                ?: return@get call.respond(HttpStatusCode.NotFound,
                    ErrorResponse(ErrorDetail("NOT_FOUND", "Job not found")))

            val metadata = jobRepo.getMetadata(jobId)
            call.respond(HttpStatusCode.OK, JobMetadataResponse(
                topics      = metadata.topics,
                contentType = metadata.contentType,
                tone        = metadata.tone,
                audience    = metadata.audience
            ))
        }

        get("/history") {
            val userId = extractUserIdFromJwt(call)
                ?: return@get call.respond(HttpStatusCode.Unauthorized,
                    ErrorResponse(ErrorDetail("UNAUTHORIZED", "Sign in required")))

            val jobs = jobRepo.getJobsByUserId(userId).map { job ->
                if (job.status == "done") {
                    val episode = clipRepo.getEpisodeByJobId(job.id)
                    job.copy(episode = episode)
                } else {
                    job
                }
            }
            call.respond(HttpStatusCode.OK, jobs)
        }
    }
}