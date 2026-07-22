package com.reelo.routes

import com.reelo.db.repositories.JobRepository
import com.reelo.models.ErrorDetail
import com.reelo.models.ErrorResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject

@Serializable
data class ClaimRequest(val sessionToken: String)

fun Route.userRoutes() {
    val jobRepo by inject<JobRepository>()

    post("/users/claim") {
        val userId = extractUserIdFromJwt(call)
            ?: return@post call.respond(HttpStatusCode.Unauthorized,
                ErrorResponse(ErrorDetail("UNAUTHORIZED", "Sign in required")))

        val body = call.receive<ClaimRequest>()
        jobRepo.claimJobsBySession(body.sessionToken, userId)
        call.respond(HttpStatusCode.OK, mapOf("status" to "claimed"))
    }
}