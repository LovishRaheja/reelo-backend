package com.reelo.plugins

import com.reelo.routes.clipRoutes
import com.reelo.routes.jobRoutes
import com.reelo.routes.uploadRoutes
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        // Health check — Railway uses this to verify the server is up
        get("/health") {
            call.respond(mapOf("status" to "ok", "service" to "reelo-backend"))
        }

        // API routes
        route("/api/v1") {
            uploadRoutes()
            jobRoutes()
            clipRoutes()
        }
    }
}
