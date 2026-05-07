package com.reelo.worker

import io.github.crackthecodeabhi.kreds.connection.Endpoint
import io.github.crackthecodeabhi.kreds.connection.newClient
import io.ktor.server.config.*

class RedisQueue(config: ApplicationConfig) {

    private val redisUrl = config.property("redis.url").getString()
    private val queueKey = "reelo:jobs:queued"

    private fun parseEndpoint(): Endpoint {
        val uri = java.net.URI(redisUrl)
        return Endpoint(uri.host, uri.port.takeIf { it > 0 } ?: 6379)
    }

    private fun parsePassword(): String? {
        val uri = java.net.URI(redisUrl)
        return uri.userInfo?.substringAfter(":")
    }

    suspend fun push(jobId: String) {
        val password = parsePassword()
        newClient(parseEndpoint()).use { client ->
            if (password != null) client.auth(password)
            client.rpush(queueKey, jobId)
        }
    }

    suspend fun pop(): String? {
        val password = parsePassword()
        return newClient(parseEndpoint()).use { client ->
            if (password != null) client.auth(password)
            client.lpop(queueKey)
        }
    }
}