package com.reelo.services

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.sdk.kotlin.services.s3.presigners.presignPutObject
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.net.url.Url
import io.ktor.server.config.*
import java.io.File
import kotlin.time.Duration.Companion.minutes

class R2Service(config: ApplicationConfig) {

    private val bucket     = config.property("r2.bucket").getString()
    private val publicUrl  = config.property("r2.publicUrl").getString()
    private val accountId  = config.property("r2.accountId").getString()
    private val accessKey  = config.property("r2.accessKeyId").getString()
    private val secretKey  = config.property("r2.secretAccessKey").getString()

    // R2 endpoint — S3-compatible
    private val endpoint = "https://$accountId.r2.cloudflarestorage.com"

    private fun buildClient(): S3Client = S3Client {
        region = "auto"
        endpointUrl = Url.parse(endpoint)
        credentialsProvider = CredentialsProvider {
            Credentials(accessKey, secretKey)
        }
    }

    /**
     * Returns a short-lived signed URL the browser/Android can PUT a file to directly.
     * The backend never touches the video bytes.
     */
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

    /**
     * Uploads a local file from the worker to R2.
     * Used by the worker after FFmpeg cuts a clip.
     */
    suspend fun uploadFile(localFile: File, fileKey: String, contentType: String): String {
        buildClient().use { s3 ->
            s3.putObject(PutObjectRequest {
                bucket = this@R2Service.bucket
                key    = fileKey
                body   = aws.smithy.kotlin.runtime.content.ByteStream.fromFile(localFile)
                this.contentType = contentType
            })
        }
        return getPublicUrl(fileKey)
    }

    /**
     * Downloads a file from R2 to a local temp file.
     * Used by the worker to fetch the raw uploaded video.
     */
    suspend fun downloadFile(fileKey: String, destination: File) {
        buildClient().use { s3 ->
            s3.getObject(GetObjectRequest {
                bucket = this@R2Service.bucket
                key    = fileKey
            }) { response ->
                response.body?.writeToFile(destination)
            }
        }
    }

    /** Returns the public CDN URL for a file already in R2. */
    fun getPublicUrl(fileKey: String): String = "$publicUrl/$fileKey"

    /** Builds the R2 object key for a raw upload. */
    fun rawFileKey(sessionToken: String, jobId: String, extension: String): String =
        "raw/$sessionToken/$jobId.$extension"

    /** Builds the R2 object key for a finished clip. */
    fun clipFileKey(sessionToken: String, jobId: String, clipNumber: Int): String =
        "clips/$sessionToken/$jobId/clip_$clipNumber.mp4"
}
