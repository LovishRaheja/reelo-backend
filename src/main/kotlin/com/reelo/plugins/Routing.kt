package com.reelo.plugins

import com.reelo.routes.clipRoutes
import com.reelo.routes.confirmRoutes
import com.reelo.routes.jobRoutes
import com.reelo.routes.uploadRoutes
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok", "service" to "reelo-backend"))
        }

        route("/api/v1") {
            uploadRoutes()
            jobRoutes()
            clipRoutes()
            confirmRoutes()
        }
    }
}