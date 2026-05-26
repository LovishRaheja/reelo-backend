package com.reelo.routes

import com.reelo.db.repositories.JobRepository
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
data class ConfirmRequest(
    val extraContext: String? = null
)

fun Route.confirmRoutes() {
    val jobRepo    by inject<JobRepository>()
    val redisQueue by inject<RedisQueue>()

    post("/jobs/{id}/confirm") {
        val jobId = call.parameters["id"]
            ?: return@post call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(ErrorDetail("MISSING_ID", "Job ID is required"))
            )

        val body = call.receive<ConfirmRequest>()

        // Save extra context and mark as confirmed
        jobRepo.confirmJob(jobId, body.extraContext)

        // Push back to Redis for clipping phase
        redisQueue.push(jobId)

        call.respond(HttpStatusCode.OK, mapOf("status" to "confirmed"))
    }
}