package com.hifnawy.quran.shared.managers

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.Gson
import com.hifnawy.quran.shared.R
import com.hifnawy.quran.shared.api.QuranAPI
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.Constants
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.storage.SharedPreferencesManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

class DownloadWorkManager(private val context: Context, workerParams: WorkerParameters) :
        CoroutineWorker(context, workerParams) {

    companion object {

        var chapters = mutableListOf<Chapter>()
        private val gsonParser = Gson()
        fun toReciter(reciterJSON: String): Reciter {
            return gsonParser.fromJson(reciterJSON, Reciter::class.java)
        }

        fun fromReciter(reciter: Reciter): String {
            return gsonParser.toJson(reciter)
        }

        fun toChapter(reciterJSON: String): Chapter {
            return gsonParser.fromJson(reciterJSON, Chapter::class.java)
        }

        fun fromChapter(chapter: Chapter): String {
            return gsonParser.toJson(chapter)
        }

        fun toChapters(chaptersJSON: Array<String>): List<Chapter> {
            val chapters = mutableListOf<Chapter>()

            for (chapterJSON in chaptersJSON) {
                chapters.add(gsonParser.fromJson(chapterJSON, Chapter::class.java))
            }

            return chapters
        }

        fun fromChapters(chapters: List<Chapter>): Array<String> {
            val chaptersJSON = Array(chapters.size) { "" }

            for (chapterIndex in chapters.indices) {
                chaptersJSON[chapterIndex] = gsonParser.toJson(chapters[chapterIndex])
            }

            return chaptersJSON
        }
    }

    enum class DownloadStatus { FILE_EXISTS, STARTING_DOWNLOAD, DOWNLOADING, FINISHED_DOWNLOAD, DOWNLOAD_ERROR, DOWNLOAD_INTERRUPTED
    }

    enum class DownloadWorkerInfo { CURRENT_CHAPTER_NUMBER, DOWNLOAD_STATUS, BYTES_DOWNLOADED, FILE_SIZE, FILE_PATH, PROGRESS
    }

    override suspend fun doWork(): Result {
        val reciterJSON =
            inputData.getString(Constants.IntentDataKeys.RECITER.name) ?: return Result.failure(
                    workDataOf(DownloadStatus.DOWNLOAD_ERROR.name to "invalid reciter")
            )
        val singleFileDownload =
            inputData.getBoolean(Constants.IntentDataKeys.SINGLE_DOWNLOAD_TYPE.name, false)
        val reciter = toReciter(reciterJSON)

        if (singleFileDownload) {
            val chapterJSON =
                inputData.getString(Constants.IntentDataKeys.CHAPTER.name) ?: return Result.failure(
                        workDataOf(DownloadStatus.DOWNLOAD_ERROR.name to "invalid chapter")
                )
            val urlString =
                inputData.getString(Constants.IntentDataKeys.CHAPTER_URL.name) ?: return Result.failure(
                        workDataOf(DownloadStatus.DOWNLOAD_ERROR.name to "invalid url")
                )
            val chapter = toChapter(chapterJSON)
            val url = URL(urlString)

            return downloadFile(url, reciter, chapter, true)
        }

        if (chapters.isEmpty()) return Result.failure(workDataOf(DownloadStatus.DOWNLOAD_ERROR.name to "invalid chapters list"))
        val chapterAudioFiles = QuranAPI.getReciterChaptersAudioFiles(reciter.id)

        for (currentChapter in chapters) {
            val chapterAudioFile =
                chapterAudioFiles.find { chapterAudioFile -> chapterAudioFile.chapter_id == currentChapter.id }
                    ?: continue
            downloadFile(URL(chapterAudioFile.audio_url), reciter, currentChapter, false)
        }

        return Result.success()
    }

    private suspend fun setProgress(
            downloadStatus: DownloadStatus,
            bytesDownloaded: Long,
            fileSize: Int,
            filePath: String?,
            progress: Float,
            currentChapter: Chapter? = null
    ) {
        setProgress(
                if (currentChapter == null) {
                    workDataOf(
                            DownloadWorkerInfo.DOWNLOAD_STATUS.name to downloadStatus.name,
                            DownloadWorkerInfo.BYTES_DOWNLOADED.name to bytesDownloaded,
                            DownloadWorkerInfo.FILE_SIZE.name to fileSize,
                            DownloadWorkerInfo.FILE_PATH.name to filePath,
                            DownloadWorkerInfo.PROGRESS.name to progress
                    )
                } else {
                    workDataOf(
                            DownloadWorkerInfo.CURRENT_CHAPTER_NUMBER.name to fromChapter(currentChapter),
                            DownloadWorkerInfo.DOWNLOAD_STATUS.name to downloadStatus.name,
                            DownloadWorkerInfo.BYTES_DOWNLOADED.name to bytesDownloaded,
                            DownloadWorkerInfo.FILE_SIZE.name to fileSize,
                            DownloadWorkerInfo.FILE_PATH.name to filePath,
                            DownloadWorkerInfo.PROGRESS.name to progress
                    )
                }
        )
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun downloadFile(
            url: URL, reciter: Reciter, chapter: Chapter, singleFileDownload: Boolean = true
    ): Result {
        val decimalFormat = DecimalFormat("#.#", DecimalFormatSymbols.getInstance(Locale("ar", "EG")))
        var serviceForegroundNotification = NotificationCompat.Builder(
                context, "${context.getString(R.string.quran_recitation_notification_name)} Service"
        ).setOngoing(true).setPriority(NotificationManager.IMPORTANCE_MAX)
            .setSmallIcon(R.drawable.quran_icon_monochrome_black_64).setSilent(true).setContentTitle(
                    context.getString(R.string.loading_chapter, chapter.name_arabic)
            ).setContentText(
                    "${decimalFormat.format(0 / (1024 * 1024))} مب. \\ ${decimalFormat.format(0 / (1024 * 1024))} مب. (${
                        decimalFormat.format(0)
                    }٪)"
            ).setSubText(reciter.name_ar).build()
        var newDownload = false
        var chapterAudioFileSize: Int
        val chapterFile = getChapterPath(context, reciter, chapter)

        chapterFile.parentFile?.run {
            if (!exists()) {
                mkdirs()
            }
        }

        (url.openConnection() as HttpURLConnection).apply {
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
                        setProgress(
                                DownloadStatus.STARTING_DOWNLOAD,
                                0,
                                chapterAudioFileSize,
                                null,
                                0f,
                                if (singleFileDownload) null else chapter
                        )
                        val inputStream = inputStream
                        val outputStream = chapterFile.outputStream()
                        var bytes = 0
                        var bytesDownloaded = 0L
                        val buffer = ByteArray(1024)
                        while (!isStopped && (bytes >= 0)) {
                            bytesDownloaded += bytes
                            val percentage =
                                (bytesDownloaded.toFloat() / chapterAudioFileSize.toFloat() * 100)

                            Log.d(
                                    DownloadWorkManager::class.simpleName,
                                    "downloading ${chapterFile.name} $bytesDownloaded \\ $chapterAudioFileSize ($percentage%)"
                            )

                            setProgress(
                                    DownloadStatus.DOWNLOADING,
                                    bytesDownloaded,
                                    chapterAudioFileSize,
                                    null,
                                    percentage,
                                    if (singleFileDownload) null else chapter
                            )

                            serviceForegroundNotification = NotificationCompat.Builder(
                                    context,
                                    "${context.getString(R.string.quran_recitation_notification_name)} Service"
                            ).setOngoing(true).setPriority(NotificationManager.IMPORTANCE_MAX)
                                .setSmallIcon(R.drawable.quran_icon_monochrome_black_64).setSilent(true)
                                .setContentTitle(
                                        context.getString(R.string.loading_chapter, chapter.name_arabic)
                                ).setContentText(
                                        "${decimalFormat.format(bytesDownloaded / (1024 * 1024))} مب. \\ ${
                                            decimalFormat.format(
                                                    chapterAudioFileSize / (1024 * 1024)
                                            )
                                        } مب. (${
                                            decimalFormat.format(
                                                    percentage
                                            )
                                        }٪)"
                                ).setSubText(reciter.name_ar).build()

                            setForeground(ForegroundInfo(230893, serviceForegroundNotification))

                            outputStream.write(buffer, 0, bytes)
                            bytes = inputStream.read(buffer)
                        }
                        inputStream.close()
                        outputStream.close()
                        disconnect()

                        if (isStopped) {
                            setProgress(
                                    DownloadStatus.DOWNLOAD_INTERRUPTED,
                                    chapterAudioFileSize.toLong(),
                                    chapterAudioFileSize,
                                    chapterFile.absolutePath,
                                    100f,
                                    if (singleFileDownload) null else chapter
                            )

                            return Result.failure(
                                    workDataOf(
                                            DownloadWorkerInfo.DOWNLOAD_STATUS.name to DownloadStatus.DOWNLOAD_INTERRUPTED.name,
                                            DownloadWorkerInfo.BYTES_DOWNLOADED.name to chapterAudioFileSize.toLong(),
                                            DownloadWorkerInfo.FILE_SIZE.name to chapterAudioFileSize,
                                            DownloadWorkerInfo.FILE_PATH.name to chapterFile.absolutePath,
                                            DownloadWorkerInfo.PROGRESS.name to 100f
                                    )
                            )
                        } else {
                            SharedPreferencesManager(context).setChapterPath(reciter, chapter)

                            setProgress(
                                    DownloadStatus.FINISHED_DOWNLOAD,
                                    chapterAudioFileSize.toLong(),
                                    chapterAudioFileSize,
                                    chapterFile.absolutePath,
                                    100f,
                                    if (singleFileDownload) null else chapter
                            )

                            return Result.success(
                                    workDataOf(
                                            DownloadWorkerInfo.DOWNLOAD_STATUS.name to DownloadStatus.FINISHED_DOWNLOAD.name,
                                            DownloadWorkerInfo.BYTES_DOWNLOADED.name to chapterAudioFileSize.toLong(),
                                            DownloadWorkerInfo.FILE_SIZE.name to chapterAudioFileSize,
                                            DownloadWorkerInfo.FILE_PATH.name to chapterFile.absolutePath,
                                            DownloadWorkerInfo.PROGRESS.name to 100f
                                    )
                            )
                        }
                    } else {
                        SharedPreferencesManager(context).setChapterPath(reciter, chapter)

                        setProgress(
                                DownloadStatus.FILE_EXISTS,
                                chapterAudioFileSize.toLong(),
                                chapterAudioFileSize,
                                chapterFile.absolutePath,
                                100f,
                                if (singleFileDownload) null else chapter
                        )

                        serviceForegroundNotification = NotificationCompat.Builder(
                                context,
                                "${context.getString(R.string.quran_recitation_notification_name)} Service"
                        ).setOngoing(true).setPriority(NotificationManager.IMPORTANCE_MAX)
                            .setSmallIcon(R.drawable.quran_icon_monochrome_black_64).setSilent(true)
                            .setContentTitle(
                                    context.getString(R.string.loading_chapter, chapter.name_arabic)
                            ).setContentText(
                                    "${decimalFormat.format(chapterAudioFileSize.toLong() / (1024 * 1024))} مب. \\ ${
                                        decimalFormat.format(
                                                chapterAudioFileSize / (1024 * 1024)
                                        )
                                    } مب. (${
                                        decimalFormat.format(100f)
                                    }٪)"
                            ).setSubText(reciter.name_ar).build()

                        setForeground(ForegroundInfo(230893, serviceForegroundNotification))

                        return Result.success(
                                workDataOf(
                                        DownloadWorkerInfo.DOWNLOAD_STATUS.name to DownloadStatus.FILE_EXISTS.name,
                                        DownloadWorkerInfo.BYTES_DOWNLOADED.name to chapterAudioFileSize.toLong(),
                                        DownloadWorkerInfo.FILE_SIZE.name to chapterAudioFileSize,
                                        DownloadWorkerInfo.FILE_PATH.name to chapterFile.absolutePath,
                                        DownloadWorkerInfo.PROGRESS.name to 100f
                                )
                        )
                    }
                }

                else -> {
                    setProgress(
                            DownloadStatus.DOWNLOAD_ERROR,
                            -1L,
                            -1,
                            null,
                            0f,
                            if (singleFileDownload) null else chapter
                    )
                    return Result.failure(
                            workDataOf(
                                    DownloadWorkerInfo.DOWNLOAD_STATUS.name to "connection to $url returned a $responseCode response code"
                            )
                    )
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