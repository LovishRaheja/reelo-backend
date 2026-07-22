package com.reelo.routes

import io.ktor.server.application.*
import io.ktor.server.request.*

fun extractUserIdFromJwt(call: ApplicationCall): String? {
    return try {
        val authHeader = call.request.header("Authorization") ?: return null
        val token = authHeader.removePrefix("Bearer ").trim()
        val payload = token.split(".")[1]
        val decoded = String(java.util.Base64.getUrlDecoder().decode(
            payload.padEnd((payload.length + 3) / 4 * 4, '=')
        ))
        val subMatch = Regex("\"sub\":\"([^\"]+)\"").find(decoded)
        subMatch?.groupValues?.get(1)
    } catch (e: Exception) {
        null
    }
}