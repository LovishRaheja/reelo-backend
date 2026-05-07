package com.reelo.db.repositories

import com.reelo.db.dbQuery
import com.reelo.db.tables.Jobs
import com.reelo.models.JobResponse
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.Instant
import java.util.UUID

data class JobRecord(
    val id: String,
    val sessionToken: String,
    val fileKey: String,
    val originalFilename: String,
    val clipCount: Int,
    val status: String
)

class JobRepository {

    suspend fun create(
        sessionToken: String,
        fileKey: String,
        originalFilename: String,
        clipCount: Int
    ): JobResponse = dbQuery {
        val id = Jobs.insert {
            it[Jobs.sessionToken]     = sessionToken
            it[Jobs.fileKey]          = fileKey
            it[Jobs.originalFilename] = originalFilename
            it[Jobs.clipCount]        = clipCount
            it[Jobs.status]           = "queued"
            it[Jobs.createdAt]        = Instant.now()
            it[Jobs.updatedAt]        = Instant.now()
        } get Jobs.id

        JobResponse(
            id           = id.toString(),
            sessionToken = sessionToken,
            status       = "queued",
            progress     = 0
        )
    }

    suspend fun findById(jobId: String, sessionToken: String): JobResponse? = dbQuery {
        Jobs.select {
            (Jobs.id eq UUID.fromString(jobId)) and (Jobs.sessionToken eq sessionToken)
        }.mapNotNull { it.toJobResponse() }.firstOrNull()
    }

    suspend fun findRecordById(jobId: String): JobRecord? = dbQuery {
        Jobs.select { Jobs.id eq UUID.fromString(jobId) }
            .mapNotNull {
                JobRecord(
                    id               = it[Jobs.id].toString(),
                    sessionToken     = it[Jobs.sessionToken],
                    fileKey          = it[Jobs.fileKey],
                    originalFilename = it[Jobs.originalFilename],
                    clipCount        = it[Jobs.clipCount],
                    status           = it[Jobs.status]
                )
            }.firstOrNull()
    }

    suspend fun updateStatus(
        jobId: String,
        status: String,
        currentStep: String? = null,
        progress: Int = 0,
        errorCode: String? = null
    ) = dbQuery {
        Jobs.update({ Jobs.id eq UUID.fromString(jobId) }) {
            it[Jobs.status]      = status
            it[Jobs.currentStep] = currentStep
            it[Jobs.progress]    = progress
            it[Jobs.errorCode]   = errorCode
            it[Jobs.updatedAt]   = Instant.now()
        }
    }

    suspend fun getQueuedJobs(): List<String> = dbQuery {
        Jobs.select { Jobs.status eq "queued" }
            .orderBy(Jobs.createdAt, SortOrder.ASC)
            .limit(10)
            .map { it[Jobs.id].toString() }
    }

    private fun ResultRow.toJobResponse() = JobResponse(
        id           = this[Jobs.id].toString(),
        sessionToken = this[Jobs.sessionToken],
        status       = this[Jobs.status],
        currentStep  = this[Jobs.currentStep],
        progress     = this[Jobs.progress],
        errorCode    = this[Jobs.errorCode]
    )
}
