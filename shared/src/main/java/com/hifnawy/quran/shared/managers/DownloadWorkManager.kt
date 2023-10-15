package com.hifnawy.quran.shared.managers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.Gson
import com.hifnawy.quran.shared.R
import com.hifnawy.quran.shared.api.QuranAPI
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.Constants
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.storage.SharedPreferencesManager
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale

@Suppress("PrivatePropertyName")
private var TAG = DownloadWorkManager::class.simpleName

class DownloadWorkManager(private val context: Context, workerParams: WorkerParameters) :
        CoroutineWorker(context, workerParams) {

    companion object {

        var chapters = mutableListOf<Chapter>()
        private val gsonParser = Gson()

        fun toReciter(reciterJSON: String?): Reciter {
            return gsonParser.fromJson(reciterJSON ?: "{}", Reciter::class.java)
        }

        fun fromReciter(reciter: Reciter): String {
            return gsonParser.toJson(reciter)
        }

        fun toChapter(chapterJSON: String?): Chapter {
            return gsonParser.fromJson(chapterJSON ?: "{}", Chapter::class.java)
        }

        fun fromChapter(chapter: Chapter): String {
            return gsonParser.toJson(chapter)
        }
    }

    enum class DownloadStatus {
        FILE_EXISTS, STARTING_DOWNLOAD, DOWNLOADING, FINISHED_DOWNLOAD, DOWNLOAD_ERROR, DOWNLOAD_INTERRUPTED
    }

    enum class DownloadWorkerInfo {
        DOWNLOADED_CHAPTER_COUNT, DOWNLOAD_CHAPTER, DOWNLOAD_STATUS, BYTES_DOWNLOADED, FILE_SIZE, FILE_PATH, PROGRESS, ERROR_MESSAGE
    }

    private val sharedPrefsManager: SharedPreferencesManager by lazy { SharedPreferencesManager(context) }
    private val numberFormat = NumberFormat.getNumberInstance(Locale.ENGLISH)

    override suspend fun doWork(): Result = coroutineScope {
        val reciterJSON =
            inputData.getString(Constants.IntentDataKeys.RECITER.name)
                ?: return@coroutineScope Result.failure(
                        workDataOf(
                                DownloadWorkerInfo.DOWNLOAD_STATUS.name to DownloadStatus.DOWNLOAD_ERROR.name,
                                DownloadWorkerInfo.ERROR_MESSAGE.name to "invalid reciter"
                        )
                )
        val singleFileDownload =
            inputData.getBoolean(Constants.IntentDataKeys.SINGLE_DOWNLOAD_TYPE.name, false)
        val reciter = toReciter(reciterJSON)

        if (singleFileDownload) {
            val chapterJSON =
                inputData.getString(Constants.IntentDataKeys.CHAPTER.name)
                    ?: return@coroutineScope Result.failure(
                            workDataOf(
                                    DownloadWorkerInfo.DOWNLOAD_STATUS.name to DownloadStatus.DOWNLOAD_ERROR.name,
                                    DownloadWorkerInfo.ERROR_MESSAGE.name to "invalid chapter"
                            )
                    )
            val chapter = toChapter(chapterJSON)
            val urlString =
                inputData.getString(Constants.IntentDataKeys.CHAPTER_URL.name)
                    ?: QuranAPI.getChapterAudioFile(reciter.id, chapter.id)?.audio_url
                    ?: return@coroutineScope Result.failure(
                            workDataOf(
                                    DownloadWorkerInfo.DOWNLOAD_STATUS.name to DownloadStatus.DOWNLOAD_ERROR.name,
                                    DownloadWorkerInfo.ERROR_MESSAGE.name to "invalid URL"
                            )
                    )
            val url = URL(urlString)

            sharedPrefsManager.getChapterPath(reciter, chapter)?.let { chapterFilePath ->
                val chapterFile = File(chapterFilePath)
                val chapterFileSize = Files.readAttributes(
                        chapterFile.toPath(), BasicFileAttributes::class.java
                ).size()

                return@coroutineScope Result.success(
                        workDataOf(
                                DownloadWorkerInfo.DOWNLOAD_STATUS.name to DownloadStatus.FILE_EXISTS.name,
                                DownloadWorkerInfo.BYTES_DOWNLOADED.name to chapterFileSize,
                                DownloadWorkerInfo.FILE_SIZE.name to chapterFileSize.toInt(),
                                DownloadWorkerInfo.FILE_PATH.name to chapterFile.absolutePath,
                                DownloadWorkerInfo.PROGRESS.name to 100f
                        )
                )
            } ?: return@coroutineScope downloadFile(url, reciter, chapter, true)
        }

        if (chapters.isEmpty()) return@coroutineScope Result.failure(
                workDataOf(
                        DownloadWorkerInfo.DOWNLOAD_STATUS.name to DownloadStatus.DOWNLOAD_ERROR.name,
                        DownloadWorkerInfo.ERROR_MESSAGE.name to "invalid chapters list"
                )
        )
        val chapterAudioFiles = QuranAPI.getReciterChaptersAudioFiles(reciter.id)
        var downloadedChapterCount = 0
        setProgress(
                getWorkData(
                        DownloadStatus.STARTING_DOWNLOAD,
                        0,
                        0,
                        null,
                        0f,
                        null
                )
        )
        for (currentChapter in chapters) {
            sharedPrefsManager.getChapterPath(reciter, currentChapter)
                ?.let { chapterFilePath ->
                    val chapterFile = File(chapterFilePath)
                    val chapterFileSize = Files.readAttributes(
                            chapterFile.toPath(), BasicFileAttributes::class.java
                    ).size()

                    Log.d(
                            TAG,
                            "file ${chapterFile.name} ${numberFormat.format(chapterFileSize)} bytes / ${
                                numberFormat.format(chapterFileSize)
                            } bytes (100%) exists and is complete, will not download!"
                    )

                    setProgress(
                            getWorkData(
                                    DownloadStatus.FILE_EXISTS,
                                    chapterFileSize,
                                    chapterFileSize.toInt(),
                                    chapterFile.absolutePath,
                                    100f,
                                    currentChapter
                            )
                    )
                } ?: chapterAudioFiles.find { chapterAudioFile ->
                chapterAudioFile.chapter_id == currentChapter.id
            }?.let { chapterAudioFile ->
                val result =
                    downloadFile(URL(chapterAudioFile.audio_url), reciter, currentChapter, false)
                if (result.outputData.getString(DownloadWorkerInfo.DOWNLOAD_STATUS.name) == DownloadStatus.DOWNLOAD_ERROR.name) {
                    downloadedChapterCount--
                }
            }

            downloadedChapterCount++
            delay(50)
        }

        if (downloadedChapterCount < context.resources.getInteger(R.integer.quran_chapter_count)) {
            return@coroutineScope Result.failure(
                    workDataOf(
                            DownloadWorkerInfo.DOWNLOADED_CHAPTER_COUNT.name to downloadedChapterCount,
                            DownloadWorkerInfo.DOWNLOAD_STATUS.name to DownloadStatus.FINISHED_DOWNLOAD.name,
                            DownloadWorkerInfo.PROGRESS.name to 100f
                    )
            )
        }

        return@coroutineScope Result.success(
                workDataOf(
                        DownloadWorkerInfo.DOWNLOADED_CHAPTER_COUNT.name to downloadedChapterCount,
                        DownloadWorkerInfo.DOWNLOAD_STATUS.name to DownloadStatus.FINISHED_DOWNLOAD.name,
                        DownloadWorkerInfo.PROGRESS.name to 100f
                )
        )
    }

    private fun getWorkData(
            downloadStatus: DownloadStatus,
            bytesDownloaded: Long,
            fileSize: Int,
            filePath: String?,
            progress: Float,
            currentChapter: Chapter? = null
    ): Data {
        return workDataOf(
                DownloadWorkerInfo.DOWNLOAD_CHAPTER.name to if (currentChapter != null) fromChapter(
                        currentChapter
                ) else null,
                DownloadWorkerInfo.DOWNLOAD_STATUS.name to downloadStatus.name,
                DownloadWorkerInfo.BYTES_DOWNLOADED.name to bytesDownloaded,
                DownloadWorkerInfo.FILE_SIZE.name to fileSize,
                DownloadWorkerInfo.FILE_PATH.name to filePath,
                DownloadWorkerInfo.PROGRESS.name to progress
        )
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun downloadFile(
            url: URL, reciter: Reciter, chapter: Chapter, singleFileDownload: Boolean = true
    ): Result {
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

            setProgress(
                    getWorkData(
                            DownloadStatus.STARTING_DOWNLOAD,
                            0L,
                            0,
                            null,
                            0f,
                            if (singleFileDownload) null else chapter
                    )
            )

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
                                "file ${chapterFile.name} ${numberFormat.format(offset)} bytes / ${
                                    numberFormat.format(chapterAudioFileSize)
                                } bytes (${DecimalFormat("000.000").format(progress)}%) exists but is not complete, resuming download..."
                        )
                    } else {
                        Log.d(
                                TAG,
                                "file ${chapterFile.name} 0 / ${numberFormat.format(chapterAudioFileSize)} bytes (0%) does not exist, starting download..."
                        )
                    }

                    if (offset != chapterAudioFileSize.toLong()) {
                        Log.d(TAG, "skipping ${numberFormat.format(offset)} bytes from $url...")
                        disconnect()

                        setProgress(
                                getWorkData(
                                        DownloadStatus.STARTING_DOWNLOAD,
                                        offset,
                                        chapterAudioFileSize,
                                        null,
                                        offset.toFloat() / chapterAudioFileSize.toFloat() * 100f,
                                        if (singleFileDownload) null else chapter
                                )
                        ).also {
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
                    }

                    sharedPrefsManager.setChapterPath(reciter, chapter)

                    setProgress(
                            getWorkData(
                                    DownloadStatus.FILE_EXISTS,
                                    chapterAudioFileSize.toLong(),
                                    chapterAudioFileSize,
                                    chapterFile.absolutePath,
                                    100f,
                                    if (singleFileDownload) null else chapter
                            )
                    ).also {
                        return Result.success(
                                getWorkData(
                                        DownloadStatus.FILE_EXISTS,
                                        chapterAudioFileSize.toLong(),
                                        chapterAudioFileSize,
                                        chapterFile.absolutePath,
                                        100f,
                                        if (singleFileDownload) null else chapter
                                )
                        )
                    }
                }

                else -> {
                    setProgress(
                            workDataOf(
                                    DownloadWorkerInfo.DOWNLOAD_STATUS.name to DownloadStatus.DOWNLOAD_ERROR.name,
                                    DownloadWorkerInfo.ERROR_MESSAGE.name to "connection to $url returned a $responseCode response code",
                                    DownloadWorkerInfo.DOWNLOAD_CHAPTER.name to fromChapter(chapter)
                            )
                    ).also {
                        return Result.failure(
                                workDataOf(
                                        DownloadWorkerInfo.DOWNLOAD_STATUS.name to DownloadStatus.DOWNLOAD_ERROR.name,
                                        DownloadWorkerInfo.ERROR_MESSAGE.name to "connection to $url returned a $responseCode response code",
                                        DownloadWorkerInfo.DOWNLOAD_CHAPTER.name to fromChapter(chapter)
                                )
                        )
                    }
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
            val outputStream = FileOutputStream(chapterFile, true)
            var bytesDownloaded = offset
            val buffer = ByteArray(8_192) // 8KB buffer size
            var progress = (bytesDownloaded.toFloat() / chapterAudioFileSize.toFloat() * 100)

            setProgress(
                    getWorkData(
                            DownloadStatus.DOWNLOADING,
                            bytesDownloaded,
                            chapterAudioFileSize,
                            null,
                            progress,
                            if (singleFileDownload) null else chapter
                    )
            )

            if (responseCode !in 200..299) {
                setProgress(
                        getWorkData(
                                DownloadStatus.DOWNLOAD_ERROR,
                                -1L,
                                -1,
                                null,
                                0f,
                                if (singleFileDownload) null else chapter
                        )
                ).also {
                    return Result.failure(
                            workDataOf(
                                    DownloadWorkerInfo.DOWNLOAD_STATUS.name to DownloadStatus.DOWNLOAD_ERROR.name,
                                    DownloadWorkerInfo.ERROR_MESSAGE.name to "connection to $url returned a $responseCode response code",
                                    DownloadWorkerInfo.DOWNLOAD_CHAPTER.name to if (singleFileDownload) null else fromChapter(
                                            chapter
                                    )
                            )
                    )
                }
            }
            val pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, Constants.MainActivityClass).apply {
                        addCategory(Constants.MAIN_ACTIVITY_INTENT_CATEGORY)
                        putExtra(Constants.IntentDataKeys.RECITER.name, reciter)
                        putExtra(Constants.IntentDataKeys.CHAPTER.name, chapter)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notificationBuilder = NotificationCompat.Builder(
                    context,
                    context.getString(R.string.quran_download_notification_name)
            )
                .setSilent(true)
                .setOngoing(true)
                .setPriority(NotificationManager.IMPORTANCE_MAX)
                .setContentIntent(pendingIntent)
                .setContentInfo(context.getString(R.string.app_name))
                .setSubText("${reciter.name_ar} \\ ${chapter.name_arabic}")
                .setProgress(100, progress.toInt(), false)
                .setSmallIcon(R.drawable.quran_icon_monochrome_black_64)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            val channel = NotificationChannel(
                    context.getString(R.string.quran_download_notification_name),
                    context.getString(R.string.quran_download_notification_name),
                    NotificationManager.IMPORTANCE_HIGH
            ).apply { description = chapter.name_arabic }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            var bytes: Int
            do {
                bytes = inputStream.read(buffer)
                bytesDownloaded += bytes
                progress = (bytesDownloaded.toFloat() / chapterAudioFileSize.toFloat() * 100)
                outputStream.write(buffer, 0, bytes)

                setProgress(
                        getWorkData(
                                DownloadStatus.DOWNLOADING,
                                bytesDownloaded,
                                chapterAudioFileSize,
                                null,
                                progress,
                                if (singleFileDownload) null else chapter
                        )
                )

                Log.d(
                        TAG,
                        "downloading ${chapterFile.name} ${
                            numberFormat.format(bytesDownloaded)
                        } bytes / ${numberFormat.format(chapterAudioFileSize)} bytes (${
                            DecimalFormat("000.000").format(
                                    progress
                            )
                        }%)..."
                )

                notificationBuilder
                    .setContentTitle(
                            context.getString(
                                    R.string.loading_chapter,
                                    chapter.name_arabic
                            )
                    )
                    .setContentText(
                            "${decimalFormat.format(bytesDownloaded.toFloat() / (1024f * 1024f))} مب. \\ ${
                                decimalFormat.format(chapterAudioFileSize.toFloat() / (1024f * 1024f))
                            } مب. (${
                                decimalFormat.format(progress)
                            }٪)"
                    )
                    .setProgress(100, progress.toInt(), false)

                notificationManager.notify(
                        R.integer.quran_chapter_download_notification_channel_id,
                        notificationBuilder.build()
                )
            } while (!isStopped && bytesDownloaded < chapterAudioFileSize)

            Log.d(
                    TAG,
                    "${if (isStopped) "DOWNLOAD INTERRUPTED!!! " else "DOWNLOAD COMPLETE! "}closing input stream..."
            )
            inputStream.close()
            Log.d(
                    TAG,
                    "${if (isStopped) "DOWNLOAD INTERRUPTED!!! " else "DOWNLOAD COMPLETE! "}closing output stream..."
            )
            outputStream.close()
            Log.d(
                    TAG,
                    "${if (isStopped) "DOWNLOAD INTERRUPTED!!! " else "DOWNLOAD COMPLETE! "}disconnecting from $url..."
            )
            disconnect()
            Log.d(
                    TAG,
                    "${if (isStopped) "DOWNLOAD INTERRUPTED!!! " else "DOWNLOAD COMPLETE! "}cancelling download notification ${
                        notificationManager.getNotificationChannel(context.getString(R.string.quran_download_notification_name)).name
                    }..."
            )
            notificationManager.cancel(R.integer.quran_chapter_download_notification_channel_id)

            if (isStopped) {
                Log.d(
                        TAG,
                        "download is interrupted with ${numberFormat.format(chapterAudioFileSize - bytesDownloaded)} bytes left to download!!!"
                )
                setProgress(
                        getWorkData(
                                DownloadStatus.DOWNLOAD_INTERRUPTED,
                                chapterAudioFileSize.toLong(),
                                chapterAudioFileSize,
                                chapterFile.absolutePath,
                                100f,
                                if (singleFileDownload) null else chapter
                        )
                ).also {
                    return Result.failure(
                            getWorkData(
                                    DownloadStatus.DOWNLOAD_INTERRUPTED,
                                    chapterAudioFileSize.toLong(),
                                    chapterAudioFileSize,
                                    chapterFile.absolutePath,
                                    100f,
                                    if (singleFileDownload) null else chapter
                            )
                    )
                }
            } else {
                Log.d(
                        TAG,
                        "downloaded ${chapterFile.name} ${numberFormat.format(bytesDownloaded)} bytes / ${
                            numberFormat.format(chapterAudioFileSize)
                        } bytes (${
                            DecimalFormat("000.000").format(progress)
                        }%)"
                )

                Log.d(
                        TAG,
                        "saving ${chapter.name_simple} for ${reciter.reciter_name} in ${
                            chapterFile.absolutePath
                        } with size of ${
                            numberFormat.format(bytesDownloaded)
                        } bytes"
                )
                sharedPrefsManager.setChapterPath(reciter, chapter)

                setProgress(
                        getWorkData(
                                DownloadStatus.FINISHED_DOWNLOAD,
                                chapterAudioFileSize.toLong(),
                                chapterAudioFileSize,
                                chapterFile.absolutePath,
                                100f,
                                if (singleFileDownload) null else chapter
                        )
                ).also {
                    return Result.success(
                            getWorkData(
                                    DownloadStatus.FINISHED_DOWNLOAD,
                                    chapterAudioFileSize.toLong(),
                                    chapterAudioFileSize,
                                    chapterFile.absolutePath,
                                    100f,
                                    if (singleFileDownload) null else chapter
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