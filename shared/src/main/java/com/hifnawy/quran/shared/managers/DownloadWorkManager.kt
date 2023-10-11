package com.hifnawy.quran.shared.managers

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
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
        CURRENT_CHAPTER_NUMBER, DOWNLOAD_STATUS, BYTES_DOWNLOADED, FILE_SIZE, FILE_PATH, PROGRESS
    }

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

        return Result.success(
                workDataOf(
                        DownloadWorkerInfo.DOWNLOAD_STATUS.name to DownloadStatus.FINISHED_DOWNLOAD.name,
                        DownloadWorkerInfo.PROGRESS.name to 100f
                )
        )
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
                            DownloadWorkerInfo.CURRENT_CHAPTER_NUMBER.name to fromChapter(
                                    currentChapter
                            ),
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

                        setProgress(
                                DownloadStatus.STARTING_DOWNLOAD,
                                offset,
                                chapterAudioFileSize,
                                null,
                                offset.toFloat() / chapterAudioFileSize.toFloat() * 100f,
                                if (singleFileDownload) null else chapter
                        )
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
            val outputStream = FileOutputStream(chapterFile, true)
            var bytes = 0
            var bytesDownloaded = offset
            val buffer = ByteArray(1024)
            var progress = (bytesDownloaded.toFloat() / chapterAudioFileSize.toFloat() * 100)

            setProgress(
                    DownloadStatus.DOWNLOADING,
                    bytesDownloaded,
                    chapterAudioFileSize,
                    null,
                    progress,
                    if (singleFileDownload) null else chapter
            )

            if (responseCode !in 200..299) {
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
                val notification = NotificationCompat.Builder(
                        context,
                        "${context.getString(R.string.quran_recitation_notification_name)} Service"
                )
                    .setSilent(true)
                    .setOngoing(true)
                    .setPriority(NotificationManager.IMPORTANCE_MAX)
                    .setSmallIcon(R.drawable.quran_icon_monochrome_black_64)
                    .setContentTitle(context.getString(R.string.loading_chapter, chapter.name_arabic))
                    .setContentText(
                            "${decimalFormat.format(bytesDownloaded.toFloat() / (1024f * 1024f))} مب. \\ ${
                                decimalFormat.format(chapterAudioFileSize.toFloat() / (1024f * 1024f))
                            } مب. (${
                                decimalFormat.format(progress)
                            }٪)"
                    )
                    .setContentInfo(context.getString(R.string.app_name))
                    .setSubText("${reciter.name_ar} \\ ${chapter.name_arabic}")
                    .setContentIntent(pendingIntent)
                    .build()

                setForegroundAsync(
                        ForegroundInfo(
                                R.integer.quran_chapter_recitation_notification_channel_id,
                                notification
                        )
                )

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
                sharedPrefsManager.setChapterPath(reciter, chapter)

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