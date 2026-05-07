package com.reelo.db

import com.reelo.db.tables.*
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.config.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {

    fun init(config: ApplicationConfig) {
        val jdbcUrl = config.property("database.url").getString()
        val hikari = hikariDataSource(jdbcUrl)
        Database.connect(hikari)
        createSchemas()
    }

    private fun hikariDataSource(jdbcUrl: String): HikariDataSource {
        val config = HikariConfig().apply {
            driverClassName  = "org.postgresql.Driver"
            jdbcUrl          = jdbcUrl
            maximumPoolSize  = 10
            minimumIdle      = 2
            idleTimeout      = 600_000
            connectionTimeout = 30_000
            isAutoCommit     = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }
        return HikariDataSource(config)
    }

    private fun createSchemas() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                Jobs,
                Episodes,
                Clips,
                Captions
            )
        }
    }
}

// Helper — runs a DB query on the IO dispatcher
suspend fun <T> dbQuery(block: () -> T): T =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        transaction { block() }
    }
