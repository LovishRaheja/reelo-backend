package com.reelo.worker

import io.github.crackthecodeabhi.kreds.connection.Endpoint
import io.github.crackthecodeabhi.kreds.connection.newClient
import io.ktor.server.config.*

class RedisQueue(config: ApplicationConfig) {

    private val redisUrl = config.property("redis.url").getString()
    private val queueKey = "reelo:jobs:queued"

    private fun parseEndpoint(): Endpoint {
        // Parse redis://default:password@host:port
        val uri = java.net.URI(redisUrl)
        return Endpoint(uri.host, uri.port.takeIf { it > 0 } ?: 6379)
    }

    /** Push a job ID onto the queue. Called by the API after creating a job. */
    suspend fun push(jobId: String) {
        newClient(parseEndpoint()).use { client ->
            client.rpush(queueKey, jobId)
        }
    }

    /**
     * Pop the next job ID from the queue.
     * Returns null if the queue is empty.
     * The worker calls this in a loop.
     */
    suspend fun pop(): String? {
        return newClient(parseEndpoint()).use { client ->
            client.lpop(queueKey)
        }
    }
}
