package com.reelo.services

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.presigners.presignPutObject
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.toByteArray
import aws.smithy.kotlin.runtime.net.url.Url
import io.ktor.server.config.*
import java.io.File
import kotlin.time.Duration.Companion.minutes

class R2Service(config: ApplicationConfig) {

    private val bucket    = config.property("r2.bucket").getString()
    private val publicUrl = config.property("r2.publicUrl").getString()
    private val accountId = config.property("r2.accountId").getString()
    private val accessKey = config.property("r2.accessKeyId").getString()
    private val secretKey = config.property("r2.secretAccessKey").getString()

    private val endpoint = "https://$accountId.r2.cloudflarestorage.com"

    private fun buildClient(): S3Client = S3Client {
        region = "auto"
        endpointUrl = Url.parse(endpoint)
        credentialsProvider = StaticCredentialsProvider(
            Credentials(accessKey, secretKey)
        )
    }

    suspend fun generateUploadUrl(fileKey: String, contentType: String): String {
        return buildClient().use { s3 ->
            val request = PutObjectRequest {
                bucket = this@R2Service.bucket
                key    = fileKey
                this.contentType = contentType
            }
            s3.presignPutObject(request, 15.minutes).url.toString()
        }
    }

    suspend fun uploadFile(localFile: File, fileKey: String, contentType: String): String {
        buildClient().use { s3 ->
            s3.putObject(PutObjectRequest {
                bucket = this@R2Service.bucket
                key    = fileKey
                body   = ByteStream.fromBytes(localFile.readBytes())
                this.contentType = contentType
            })
        }
        return getPublicUrl(fileKey)
    }

    suspend fun downloadFile(fileKey: String, destination: File) {
        buildClient().use { s3 ->
            s3.getObject(GetObjectRequest {
                bucket = this@R2Service.bucket
                key    = fileKey
            }) { response ->
                response.body?.let { byteStream ->
                    val bytes = byteStream.toByteArray()
                    destination.writeBytes(bytes)
                }
            }
        }
    }

    fun getPublicUrl(fileKey: String): String = "$publicUrl/$fileKey"

    fun rawFileKey(sessionToken: String, jobId: String, extension: String): String =
        "raw/$sessionToken/$jobId.$extension"

    fun clipFileKey(sessionToken: String, jobId: String, clipNumber: Int): String =
        "clips/$sessionToken/$jobId/clip_$clipNumber.mp4"
}