package com.reelo.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File

class YtDlpService {
    private val log = LoggerFactory.getLogger(YtDlpService::class.java)

    suspend fun download(url: String, outputFile: File): File = withContext(Dispatchers.IO) {
        log.info("Downloading video from URL: $url")

        val process = ProcessBuilder(
            "/root/.local/bin/yt-dlp",  // full path
            "--format", "bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/best[height<=720][ext=mp4]/best[height<=720]",
            "--merge-output-format", "mp4",
            "--no-playlist",
            "--max-filesize", "1G",
            "--output", outputFile.absolutePath,
            url
        )
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        log.info("yt-dlp output: $output")

        if (exitCode != 0) {
            throw Exception("Could not download video. Make sure the URL is public and try again.")
        }

        log.info("Downloaded ${outputFile.length() / 1024}KB from $url")
        outputFile
    }

    suspend fun isValidUrl(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(
                "yt-dlp",
                "--simulate",
                "--quiet",
                url
            )
                .redirectErrorStream(true)
                .start()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }
}