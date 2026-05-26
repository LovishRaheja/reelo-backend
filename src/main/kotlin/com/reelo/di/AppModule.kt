package com.reelo.di

import com.reelo.db.repositories.ClipRepository
import com.reelo.db.repositories.JobRepository
import com.reelo.services.FfmpegService
import com.reelo.services.LlmService
import com.reelo.services.R2Service
import com.reelo.services.WhisperService
import com.reelo.worker.JobProcessor
import com.reelo.worker.RedisQueue
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.koin.dsl.module

fun appModule(config: io.ktor.server.config.ApplicationConfig) = module {

    single { config }

    single {
        HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(Logging) { level = LogLevel.INFO }
            install(HttpTimeout) {
                requestTimeoutMillis = 300_000  // 5 minutes
                connectTimeoutMillis = 30_000
                socketTimeoutMillis  = 300_000
            }
        }
    }

    single { R2Service(get()) }
    single { WhisperService(get(), get()) }
    single { FfmpegService() }
    single { RedisQueue(get()) }
    single { LlmService(get(), get()) }

    single { JobRepository() }
    single { ClipRepository() }

    single {
        JobProcessor(
            jobRepo        = get(),
            clipRepo       = get(),
            r2Service      = get(),
            whisperService = get(),
            ffmpegService  = get(),
            redisQueue     = get(),
            llmService     = get()
        )
    }
}