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
import org.koin.ktor.ext.inject

fun Route.jobRoutes() {
    val jobRepo    by inject<JobRepository>()
    val clipRepo   by inject<ClipRepository>()
    val redisQueue by inject<RedisQueue>()

    route("/jobs") {

        /**
         * POST /api/v1/jobs
         *
         * Creates a processing job after the file has been uploaded to R2.
         * Saves the job to Postgres and pushes the job ID to Redis so the
         * worker picks it up.
         *
         * Request:  { fileKey, originalFilename, clipCount?, sessionToken }
         * Response: { id, sessionToken, status: "queued", progress: 0 }
         */
        post {
            val body = call.receive<CreateJobRequest>()

            require(body.fileKey.isNotBlank())          { "fileKey is required" }
            require(body.originalFilename.isNotBlank()) { "originalFilename is required" }
            require(body.sessionToken.isNotBlank())     { "sessionToken is required" }
            require(body.clipCount in 1..10)            { "clipCount must be between 1 and 10" }

            // Create job row in Postgres
            val job = jobRepo.create(
                sessionToken     = body.sessionToken,
                fileKey          = body.fileKey,
                originalFilename = body.originalFilename,
                clipCount        = body.clipCount
            )

            // Push to Redis — worker picks this up
            redisQueue.push(job.id)

            call.respond(HttpStatusCode.Created, job)
        }

        /**
         * GET /api/v1/jobs/{id}?session={token}
         *
         * Polled by the frontend every 2 seconds while a job is running.
         * Returns current status, progress (0-100), and the episode with
         * all clips once status = "done".
         *
         * The session token is required to prevent users from accessing
         * each other's jobs.
         */
        get("/{id}") {
            val jobId        = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest,
                    ErrorResponse(ErrorDetail("MISSING_ID", "Job ID is required")))

            val sessionToken = call.request.queryParameters["session"]
                ?: return@get call.respond(HttpStatusCode.BadRequest,
                    ErrorResponse(ErrorDetail("MISSING_SESSION", "session query param is required")))

            val job = jobRepo.findById(jobId, sessionToken)
                ?: return@get call.respond(HttpStatusCode.NotFound,
                    ErrorResponse(ErrorDetail("NOT_FOUND", "Job not found")))

            // If done, attach the full episode + clips to the response
            val response = if (job.status == "done") {
                val episode = clipRepo.getEpisodeByJobId(jobId)
                job.copy(episode = episode)
            } else {
                job
            }

            // Add a rough time estimate based on status
            val withEstimate = response.copy(
                estimatedSeconds = when (job.status) {
                    "queued"       -> 300   // 5 min — hasn't started yet
                    "downloading"  -> 240
                    "transcribing" -> 180
                    "analyzing"    -> 120
                    "clipping"     -> 60
                    "uploading"    -> 20
                    else           -> null
                }
            )

            call.respond(HttpStatusCode.OK, withEstimate)
        }
    }
}
