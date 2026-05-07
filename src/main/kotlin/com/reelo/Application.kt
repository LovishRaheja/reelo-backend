package com.reelo

import com.reelo.db.DatabaseFactory
import com.reelo.di.appModule
import com.reelo.plugins.*
import com.reelo.worker.JobProcessor
import io.ktor.server.application.*
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
    embeddedServer(Netty, port = port) {
        install(Koin) {
            slf4jLogger()
            modules(appModule)
        }
        DatabaseFactory.init(environment.config)
        val processor by inject<JobProcessor>()
        processor.start()
    }.start(wait = true)
}

fun Application.module() {
    // DI
    install(Koin) {
        slf4jLogger()
        modules(appModule)
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
