package com.reelo.worker

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.config.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RedisQueue(config: ApplicationConfig) {

    private val restUrl   = config.property("redis.restUrl").getString()
    private val restToken = config.property("redis.restToken").getString()
    private val queueKey  = "reelo:jobs:queued"

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    suspend fun push(jobId: String) {
        client.post("$restUrl/rpush/$queueKey/$jobId") {
            header("Authorization", "Bearer $restToken")
        }
    }

    suspend fun pop(): String? {
        val response = client.post("$restUrl/lpop/$queueKey") {
            header("Authorization", "Bearer $restToken")
        }.body<String>()

        return try {
            val json = Json.parseToJsonElement(response)
            json.jsonObject["result"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }
    }
}