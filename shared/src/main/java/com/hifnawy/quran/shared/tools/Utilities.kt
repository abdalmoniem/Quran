package com.hifnawy.quran.shared.tools

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.google.gson.Gson
import com.hifnawy.quran.shared.R
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.services.MediaService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Serializable
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

class Utilities {
    companion object {
        @Suppress("EXTENSION_SHADOWED_BY_MEMBER")
        inline fun <reified GenericType : Serializable> Bundle.getSerializable(key: String): GenericType? =
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getSerializable(
                    key, GenericType::class.java
                )

                else -> @Suppress("DEPRECATION") getSerializable(key) as? GenericType
            }

        @Suppress("EXTENSION_SHADOWED_BY_MEMBER")
        inline fun <reified GenericType : Serializable> Intent.getSerializableExtra(key: String): GenericType? =
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getSerializableExtra(
                    key, GenericType::class.java
                )

                else -> @Suppress("DEPRECATION") getSerializableExtra(key) as? GenericType
            }

        inline fun <reified GenericType : Serializable> SharedPreferences.getSerializableExtra(key: String): GenericType? =
            Gson().fromJson(getString(key, null), GenericType::class.java)

        fun SharedPreferences.Editor.putSerializableExtra(
            key: String, value: Serializable
        ): SharedPreferences.Editor = putString(key, Gson().toJson(value))

        suspend fun downloadFile(
            context: Context,
            url: URL,
            reciter: Reciter,
            chapter: Chapter,
            callback: (suspend (bytesDownloaded: Long, fileSize: Int, percentage: Float) -> Unit)? = null
        ): Pair<File, Int> {
            MediaService.downloadComplete = false

            var chapterAudioFileSize = -1

            var newDownload = false
            val reciterDirectory =
                "${context.filesDir.absolutePath}/${reciter.reciter_name}/${reciter.style ?: ""}"
            val chapterFileName =
                "$reciterDirectory/${chapter.id.toString().padStart(3, '0')}_${chapter.name_simple}.mp3"
            val reciterDirectoryFile = File(reciterDirectory)
            val chapterFile = File(chapterFileName)

            if (!reciterDirectoryFile.exists()) {
                reciterDirectoryFile.mkdirs()
            }
            // download the file if it doesn't exist
            // url.openStream().use { Files.copy(it, Paths.get(chapterFileName)) }

            (withContext(Dispatchers.IO) {
                url.openConnection()
            } as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept-Encoding", "identity")
                connect()

                when (responseCode) {
                    in 200..299 -> {
                        chapterAudioFileSize = contentLength

                        if (chapterFile.exists()) {
                            val chapterFileSize = Files.readAttributes(
                                chapterFile.toPath(), BasicFileAttributes::class.java
                            ).size()

                            if (chapterFileSize != chapterAudioFileSize.toLong()) {
                                chapterFile.delete()
                                chapterFile.createNewFile()
                                newDownload = true
                            }
                        } else {
                            newDownload = true
                        }

                        if (newDownload) {
                            val inputStream = inputStream
                            val outputStream = chapterFile.outputStream()

                            var bytes = 0
                            var bytesDownloaded = 0L
                            val buffer = ByteArray(1024)
                            while (MediaService.startDownload && (bytes >= 0)) {
                                bytesDownloaded += bytes

                                val percentage =
                                    (bytesDownloaded.toFloat() / chapterAudioFileSize.toFloat() * 100)

                                Log.d(
                                    "Quran_Media_Download",
                                    "downloading ${chapterFile.name} $bytesDownloaded \\ $chapterAudioFileSize ($percentage%)"
                                )

                                context.sendBroadcast(Intent(context.getString(R.string.quran_media_service_file_download_updates)).apply {
                                    putExtra("DOWNLOAD_STATUS", "DOWNLOADING")
                                    putExtra("BYTES_DOWNLOADED", bytesDownloaded)
                                    putExtra("FILE_SIZE", chapterAudioFileSize)
                                    putExtra("PERCENTAGE", percentage)
                                })

                                if (callback != null) {
                                    callback(bytesDownloaded, chapterAudioFileSize, percentage)
                                }

                                outputStream.write(buffer, 0, bytes)
                                bytes = inputStream.read(buffer)
                            }
                            inputStream.close()
                            outputStream.close()
                            disconnect()

                            MediaService.startDownload = false

                            if (Files.readAttributes(
                                    chapterFile.toPath(), BasicFileAttributes::class.java
                                ).size() == chapterAudioFileSize.toLong()
                            ) {
                                MediaService.downloadComplete = true
                                context.sendBroadcast(Intent(context.getString(R.string.quran_media_service_file_download_updates)).apply {
                                    putExtra("DOWNLOAD_STATUS", "DOWNLOADED")
                                    putExtra("BYTES_DOWNLOADED", bytesDownloaded)
                                    putExtra("FILE_SIZE", chapterAudioFileSize)
                                    putExtra("PERCENTAGE", 100.0f)
                                })
                            } else {
                                MediaService.downloadComplete = false
                            }

                        } else {
                            callback?.invoke(chapterAudioFileSize.toLong(), chapterAudioFileSize, 100f)
                        }
                    }

                    else -> {}
                }
            }

            return chapterFile to chapterAudioFileSize
        }
    }
}