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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
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
    }

    enum class DownloadStatus { FILE_EXISTS, STARTING_DOWNLOAD, DOWNLOADING, FINISHED_DOWNLOAD, DOWNLOAD_ERROR, DOWNLOAD_INTERRUPTED
    }

    enum class DownloadWorkerInfo { CURRENT_CHAPTER_NUMBER, DOWNLOAD_STATUS, BYTES_DOWNLOADED, FILE_SIZE, FILE_PATH, PROGRESS
    }

    @Suppress("PrivatePropertyName")
    private var TAG = DownloadWorkManager::class.simpleName
    private val sharedPrefsManager: SharedPreferencesManager by lazy { SharedPreferencesManager(context) }

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
            val chapter = toChapter(chapterJSON)
            val urlString =
                inputData.getString(Constants.IntentDataKeys.CHAPTER_URL.name)
                    ?: QuranAPI.getChapterAudioFile(reciter.id, chapter.id)?.audio_url
                    ?: return Result.failure(
                            workDataOf(DownloadStatus.DOWNLOAD_ERROR.name to "invalid url")
                    )
            val url = URL(urlString)

            sharedPrefsManager.getChapterPath(reciter, chapter)?.let { chapterFilePath ->
                val chapterFile = File(chapterFilePath)
                val chapterFileSize = Files.readAttributes(
                        chapterFile.toPath(), BasicFileAttributes::class.java
                ).size()

                setProgress(
                        DownloadStatus.FILE_EXISTS,
                        chapterFileSize,
                        chapterFileSize.toInt(),
                        chapterFile.absolutePath,
                        100f,
                        null
                )

                return Result.success(
                        workDataOf(
                                DownloadWorkerInfo.DOWNLOAD_STATUS.name to DownloadStatus.FILE_EXISTS.name,
                                DownloadWorkerInfo.BYTES_DOWNLOADED.name to chapterFileSize,
                                DownloadWorkerInfo.FILE_SIZE.name to chapterFileSize.toInt(),
                                DownloadWorkerInfo.FILE_PATH.name to chapterFile.absolutePath,
                                DownloadWorkerInfo.PROGRESS.name to 100f
                        )
                )
            } ?: return downloadFile(url, reciter, chapter, true)
        }

        if (chapters.isEmpty()) return Result.failure(workDataOf(DownloadStatus.DOWNLOAD_ERROR.name to "invalid chapters list"))
        val chapterAudioFiles = QuranAPI.getReciterChaptersAudioFiles(reciter.id)

        for (currentChapter in chapters) {
            sharedPrefsManager.getChapterPath(reciter, currentChapter)
                ?.let { chapterFilePath ->
                    val chapterFile = File(chapterFilePath)
                    val chapterFileSize = Files.readAttributes(
                            chapterFile.toPath(), BasicFileAttributes::class.java
                    ).size()

                    Log.d(
                            TAG,
                            "file ${chapterFile.name} $chapterFileSize \\ $chapterFileSize (100%) exists and is complete, will not download!"
                    )

                    setProgress(
                            DownloadStatus.FILE_EXISTS,
                            chapterFileSize,
                            chapterFileSize.toInt(),
                            chapterFile.absolutePath,
                            100f,
                            currentChapter
                    )
                } ?: chapterAudioFiles.find { chapterAudioFile ->
                chapterAudioFile.chapter_id == currentChapter.id
            }?.let { chapterAudioFile ->
                downloadFile(URL(chapterAudioFile.audio_url), reciter, currentChapter, false)
            }
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
        runBlocking {
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
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun downloadFile(
            url: URL, reciter: Reciter, chapter: Chapter, singleFileDownload: Boolean = true
    ): Result {
        val decimalFormat = DecimalFormat("#.#", DecimalFormatSymbols.getInstance(Locale("ar", "EG")))
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
                    val chapterAudioFileSize = contentLength
                    var offset = 0L

                    if (chapterFile.exists()) {
                        val chapterFileSize = Files.readAttributes(
                                chapterFile.toPath(), BasicFileAttributes::class.java
                        ).size()

                        offset = chapterFileSize
                        val progress = (offset.toFloat() / chapterAudioFileSize.toFloat() * 100)

                        Log.d(
                                TAG,
                                "file ${chapterFile.name} $offset \\ $chapterAudioFileSize ($progress%) exists but is not complete, resuming download..."
                        )
                    } else {
                        Log.d(
                                TAG,
                                "file ${chapterFile.name} 0 \\ $chapterAudioFileSize (0%) does not exist, starting download..."
                        )
                    }

                    if (offset != chapterAudioFileSize.toLong()) {
                        Log.d(TAG, "skipping $offset bytes from $url...")
                        disconnect()
                        return executeDownload(
                                url,
                                reciter,
                                chapter,
                                chapterFile,
                                chapterAudioFileSize,
                                offset,
                                singleFileDownload
                        )
                    }

                    sharedPrefsManager.setChapterPath(reciter, chapter)

                    setProgress(
                            DownloadStatus.FILE_EXISTS,
                            chapterAudioFileSize.toLong(),
                            chapterAudioFileSize,
                            chapterFile.absolutePath,
                            100f,
                            if (singleFileDownload) null else chapter
                    )
                    val serviceForegroundNotification = NotificationCompat.Builder(
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

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun executeDownload(
            url: URL,
            reciter: Reciter,
            chapter: Chapter,
            chapterFile: File,
            chapterAudioFileSize: Int,
            offset: Long,
            singleFileDownload: Boolean
    ): Result {
        (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Range", "bytes=$offset-")
            connect()
            val decimalFormat =
                DecimalFormat("#.#", DecimalFormatSymbols.getInstance(Locale("ar", "EG")))
            setProgress(
                    DownloadStatus.STARTING_DOWNLOAD,
                    0,
                    chapterAudioFileSize,
                    null,
                    0f,
                    if (singleFileDownload) null else chapter
            )

            val outputStream = FileOutputStream(chapterFile, true)
            var bytes = 0
            var bytesDownloaded = offset
            val buffer = ByteArray(1024)
            var progress = (bytesDownloaded.toFloat() / chapterAudioFileSize.toFloat() * 100)

            delay(300)
            setProgress(
                    DownloadStatus.DOWNLOADING,
                    bytesDownloaded,
                    chapterAudioFileSize,
                    null,
                    progress,
                    if (singleFileDownload) null else chapter
            )

            if (responseCode !in 200..299) {
                delay(300)
                setProgress(
                        DownloadStatus.DOWNLOAD_ERROR,
                        -1L,
                        -1,
                        null,
                        0f,
                        if (singleFileDownload) null else chapter
                )
                return Result.failure(workDataOf(DownloadWorkerInfo.DOWNLOAD_STATUS.name to "connection to $url returned a $responseCode response code"))

            }
            while (!isStopped && (bytes >= 0)) {
                bytesDownloaded += bytes
                progress = (bytesDownloaded.toFloat() / chapterAudioFileSize.toFloat() * 100)

                Log.d(
                        TAG,
                        "downloading ${chapterFile.name} $bytesDownloaded \\ $chapterAudioFileSize ($progress%)"
                )

                setProgress(
                        DownloadStatus.DOWNLOADING,
                        bytesDownloaded,
                        chapterAudioFileSize,
                        null,
                        progress,
                        if (singleFileDownload) null else chapter
                )
                val notification = NotificationCompat.Builder(
                        context,
                        "${context.getString(R.string.quran_recitation_notification_name)} Service"
                ).setOngoing(true).setPriority(NotificationManager.IMPORTANCE_MAX)
                    .setSmallIcon(R.drawable.quran_icon_monochrome_black_64).setSilent(true)
                    .setContentTitle(
                            context.getString(
                                    R.string.loading_chapter, chapter.name_arabic
                            )
                    ).setContentText(
                            "${decimalFormat.format(bytesDownloaded / (1024 * 1024))} مب. \\ ${
                                decimalFormat.format(
                                        chapterAudioFileSize / (1024 * 1024)
                                )
                            } مب. (${
                                decimalFormat.format(
                                        progress
                                )
                            }٪)"
                    ).setSubText(reciter.name_ar).build()

                setForeground(ForegroundInfo(230893, notification))

                outputStream.write(buffer, 0, bytes)
                bytes = inputStream.read(buffer)
            }
            inputStream.close()
            outputStream.close()
            disconnect()

            if (isStopped) {
                delay(300)
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
                sharedPrefsManager.setChapterPath(reciter, chapter)

                delay(300)
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