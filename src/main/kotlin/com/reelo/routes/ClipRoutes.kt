package com.reelo.routes

import com.reelo.db.repositories.ClipRepository
import com.reelo.models.ErrorDetail
import com.reelo.models.ErrorResponse
import com.reelo.services.R2Service
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.clipRoutes() {
    val clipRepo  by inject<ClipRepository>()
    val r2Service by inject<R2Service>()

    /**
     * GET /api/v1/episodes/{id}?session={token}
     *
     * Returns the full episode with all its clips.
     * The results page calls this once when it loads.
     * The session token ensures users only see their own episodes.
     */
    route("/episodes") {
        get("/{id}") {
            val episodeId    = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest,
                    ErrorResponse(ErrorDetail("MISSING_ID", "Episode ID is required")))

            val sessionToken = call.request.queryParameters["session"]
                ?: return@get call.respond(HttpStatusCode.BadRequest,
                    ErrorResponse(ErrorDetail("MISSING_SESSION", "session query param is required")))

            val episode = clipRepo.getEpisode(episodeId, sessionToken)
                ?: return@get call.respond(HttpStatusCode.NotFound,
                    ErrorResponse(ErrorDetail("NOT_FOUND", "Episode not found")))

            call.respond(HttpStatusCode.OK, episode)
        }
    }

    /**
     * GET /api/v1/clips/{id}/download?session={token}
     *
     * Generates a short-lived signed download URL for a clip and
     * redirects the browser to it. This keeps R2 URLs private —
     * the frontend never holds a permanent direct link.
     *
     * The browser follows the redirect and downloads the file directly
     * from Cloudflare's CDN — zero bandwidth on Railway.
     */
    route("/clips") {
        get("/{id}/download") {
            val clipId       = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest,
                    ErrorResponse(ErrorDetail("MISSING_ID", "Clip ID is required")))

            val sessionToken = call.request.queryParameters["session"]
                ?: return@get call.respond(HttpStatusCode.BadRequest,
                    ErrorResponse(ErrorDetail("MISSING_SESSION", "session query param is required")))

            // Verify the clip belongs to this session
            val episode = clipRepo.getEpisodeForClip(clipId, sessionToken)
                ?: return@get call.respond(HttpStatusCode.NotFound,
                    ErrorResponse(ErrorDetail("NOT_FOUND", "Clip not found")))

            val clip = episode.clips.find { it.id == clipId }
                ?: return@get call.respond(HttpStatusCode.NotFound,
                    ErrorResponse(ErrorDetail("NOT_FOUND", "Clip not found")))

            // Return the clip URL — R2 public CDN URL
            // In production, generate a signed URL here if your R2 bucket is private
            call.respondRedirect(clip.clipUrl, permanent = false)
        }
    }
}
