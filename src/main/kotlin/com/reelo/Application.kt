package com.reelo

import com.reelo.db.DatabaseFactory
import com.reelo.di.appModule
import com.reelo.plugins.*
import com.reelo.worker.JobProcessor
import io.ktor.server.application.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun main(args: Array<String>) {
    // Two entry points in one JAR:
    //   java -jar reelo-backend.jar          → starts HTTP server
    //   java -jar reelo-backend.jar worker   → starts job processor
    if (args.firstOrNull() == "worker") {
        startWorker()
    } else {
        startServer()
    }
}

fun startServer() {
    embeddedServer(Netty, commandLineEnvironment(emptyArray()))
        .start(wait = true)
}

fun startWorker() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8081
    val dbUrl = System.getenv("DATABASE_URL")
        ?: error("DATABASE_URL not set")
    val restUrl = System.getenv("UPSTASH_REDIS_REST_URL")
        ?: error("UPSTASH_REDIS_REST_URL not set")
    val restToken = System.getenv("UPSTASH_REDIS_REST_TOKEN")
        ?: error("UPSTASH_REDIS_REST_TOKEN not set")

    val config = MapApplicationConfig(
        "database.url" to dbUrl,
        "redis.restUrl" to restUrl,
        "redis.restToken" to restToken,
        "r2.accountId" to (System.getenv("CF_ACCOUNT_ID") ?: ""),
        "r2.accessKeyId" to (System.getenv("R2_ACCESS_KEY_ID") ?: ""),
        "r2.secretAccessKey" to (System.getenv("R2_SECRET_ACCESS_KEY") ?: ""),
        "r2.bucket" to (System.getenv("R2_BUCKET") ?: ""),
        "r2.publicUrl" to (System.getenv("R2_PUBLIC_URL") ?: ""),
        "cloudflare.apiToken" to (System.getenv("CF_AI_API_TOKEN") ?: ""),
        "cloudflare.accountId" to (System.getenv("CF_ACCOUNT_ID") ?: "")
    )

    embeddedServer(Netty, port = port) {
        install(Koin) {
            slf4jLogger()
            modules(appModule(config))
        }
        DatabaseFactory.init(config)
        val processor by inject<JobProcessor>()
        processor.start()
    }.start(wait = true)
}

fun Application.module() {
    // In Application.module()
    install(Koin) {
        slf4jLogger()
        modules(appModule(environment.config))
    }

    // Database
    DatabaseFactory.init(environment.config)

    // Plugins — order matters
    configureSerialization()
    configureCors()
    configureStatusPages()
    configureAuth()
    configureRouting()
    configureCallLogging()
}
