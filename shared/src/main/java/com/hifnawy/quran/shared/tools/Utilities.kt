package com.hifnawy.quran.shared.tools

import android.content.Context
import android.util.Log
import com.hifnawy.quran.shared.api.QuranAPI
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.storage.SharedPreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

class Utilities {
    companion object {
        enum class DownloadStatus {
            STARTING_DOWNLOAD, DOWNLOADING, FINISHED_DOWNLOAD
        }

        suspend fun downloadFile(
            context: Context,
            url: URL,
            reciter: Reciter,
            chapter: Chapter,
            downloadStatusCallback: (suspend (downloadStatus: DownloadStatus, bytesDownloaded: Long, fileSize: Int, percentage: Float, audioFile: File?) -> Unit)? = null
        ) {
            var chapterAudioFileSize = -1
            var newDownload = false

            val chapterFile = getChapterPath(context, reciter, chapter)

            chapterFile.parentFile?.run {
                if (!exists()) {
                    mkdirs()
                }
            }

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
                            downloadStatusCallback?.invoke(
                                DownloadStatus.STARTING_DOWNLOAD, 0, chapterAudioFileSize, 0f, null
                            )

                            val inputStream = inputStream
                            val outputStream = chapterFile.outputStream()

                            var bytes = 0
                            var bytesDownloaded = 0L
                            val buffer = ByteArray(1024)
                            while (bytes >= 0) {
                                bytesDownloaded += bytes

                                val percentage =
                                    (bytesDownloaded.toFloat() / chapterAudioFileSize.toFloat() * 100)

                                Log.d(
                                    "Quran_Media_Download",
                                    "downloading ${chapterFile.name} $bytesDownloaded \\ $chapterAudioFileSize ($percentage%)"
                                )

                                downloadStatusCallback?.invoke(
                                    DownloadStatus.DOWNLOADING,
                                    bytesDownloaded,
                                    chapterAudioFileSize,
                                    percentage,
                                    null
                                )

                                outputStream.write(buffer, 0, bytes)
                                bytes = inputStream.read(buffer)
                            }
                            inputStream.close()
                            outputStream.close()
                            disconnect()

                            if (Files.readAttributes(
                                    chapterFile.toPath(), BasicFileAttributes::class.java
                                ).size() == chapterAudioFileSize.toLong()
                            ) {
                                SharedPreferencesManager(context).setChapterPath(reciter, chapter)

                                downloadStatusCallback?.invoke(
                                    DownloadStatus.FINISHED_DOWNLOAD,
                                    bytesDownloaded,
                                    chapterAudioFileSize,
                                    100.0f,
                                    chapterFile
                                )
                            } else {
                                // do nothing
                            }

                        } else {
                            downloadStatusCallback?.invoke(
                                DownloadStatus.FINISHED_DOWNLOAD,
                                chapterAudioFileSize.toLong(),
                                chapterAudioFileSize,
                                100f,
                                chapterFile
                            )
                        }
                    }

                    else -> Unit
                }
            }
        }

        suspend fun updateChapterPaths(
            context: Context, reciters: List<Reciter>, chapters: List<Chapter>
        ) {
            for (reciter in reciters) {
                for (chapter in chapters) {
                    val chapterFile = getChapterPath(context, reciter, chapter)
                    val chapterAudioFile = QuranAPI.getChapterAudioFile(reciter.id, chapter.id)

                    if (!chapterFile.exists()) {
                        Log.d(
                            Utilities::class.simpleName,
                            "${chapterFile.absolutePath} doesn't exist, skipping"
                        )
                        continue
                    } else {
                        Log.d(
                            Utilities::class.simpleName,
                            "${chapterFile.absolutePath} exists, checking..."
                        )
                    }

                    chapterAudioFile?.audio_url ?: continue

                    @Suppress("BlockingMethodInNonBlockingContext") (URL(chapterAudioFile.audio_url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Accept-Encoding", "identity")
                        connect()

                        if (responseCode !in 200..299) {
                            return@apply
                        }

                        val chapterFileSize = Files.readAttributes(
                            chapterFile.toPath(), BasicFileAttributes::class.java
                        ).size()

                        if (chapterFileSize != contentLength.toLong()) {
                            Log.d(
                                Utilities::class.simpleName,
                                "Chapter Audio File incomplete, Deleting chapterPath:\n" + "reciter #${reciter.id}: ${reciter.reciter_name}\n" + "chapter: ${chapter.name_simple}\n" + "path: ${chapterFile.absolutePath}\n"
                            )
                            chapterFile.delete()
                        } else {
                            Log.d(
                                Utilities::class.simpleName,
                                "Chapter Audio File complete, Updating chapterPath:\n" + "reciter #${reciter.id}: ${reciter.reciter_name}\n" + "chapter: ${chapter.name_simple}\n" + "path: ${chapterFile.absolutePath}\n" + "size: ${chapterFileSize / 1024 / 1024} MBs"
                            )
                            SharedPreferencesManager(context).setChapterPath(
                                reciter, chapter
                            )
                        }
                    }
                }
            }
        }

        private fun getChapterPath(context: Context, reciter: Reciter, chapter: Chapter): File {
            val reciterDirectory =
                "${context.filesDir.absolutePath}/${reciter.reciter_name}/${reciter.style ?: ""}"
            val chapterFileName =
                "$reciterDirectory/${chapter.id.toString().padStart(3, '0')}_${chapter.name_simple}.mp3"

            return File(chapterFileName)
        }
    }
}