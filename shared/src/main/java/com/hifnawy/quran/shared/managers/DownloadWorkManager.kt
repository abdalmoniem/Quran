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
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.io.path.createParentDirectories
import kotlin.coroutines.coroutineContext as currentCoroutineContext

private var TAG = DownloadWorkManager::class.simpleName

class DownloadWorkManager(private val context: Context, workerParams: WorkerParameters) :
        CoroutineWorker(context, workerParams) {

    companion object {

        var chapters = listOf<Chapter>()
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
        CONNECTION_FAILURE,
        STARTING_DOWNLOAD,
        FILE_EXISTS,
        DOWNLOADING,
        FINISHED_DOWNLOAD,
        DOWNLOAD_ERROR,
        DOWNLOAD_INTERRUPTED
    }

    enum class DownloadWorkerInfo {
        DOWNLOADED_CHAPTER_COUNT,
        DOWNLOAD_CHAPTER,
        DOWNLOAD_STATUS,
        BYTES_DOWNLOADED,
        FILE_SIZE,
        FILE_PATH,
        PROGRESS,
        ERROR_MESSAGE
    }

    private val sharedPrefsManager: SharedPreferencesManager by lazy { SharedPreferencesManager(context) }
    private val numberFormat = NumberFormat.getNumberInstance(Locale.ENGLISH)
    private val decimalFormat =
            DecimalFormat("#.#", DecimalFormatSymbols.getInstance(Locale("ar", "EG")))

    // private val mutex = ReentrantLock(true)
    private val mutex = Mutex(false)
    private val timestamp
        get() = SimpleDateFormat("EE, dd/MM/yyyy hh:mm:ss.SSS a", Locale.ENGLISH).format(Date())

    override suspend fun doWork(): Result {
        val reciterJSON =
                inputData.getString(Constants.IntentDataKeys.RECITER.name)
                ?: return Result.failure(
                        workDataOf(
                                DownloadWorkerInfo.DOWNLOAD_STATUS.name to DownloadStatus.DOWNLOAD_ERROR.name,
                                DownloadWorkerInfo.ERROR_MESSAGE.name to "invalid reciter"
                        )
                )
        val singleFileDownload =
                inputData.getBoolean(Constants.IntentDataKeys.IS_SINGLE_DOWNLOAD.name, false)
        val reciter = toReciter(reciterJSON)

        if (singleFileDownload) {
            val chapterJSON =
                    inputData.getString(Constants.IntentDataKeys.CHAPTER.name)
                    ?: return Result.failure(
                            workDataOf(
                                    DownloadWorkerInfo.DOWNLOAD_STATUS.name to DownloadStatus.DOWNLOAD_ERROR.name,
                                    DownloadWorkerInfo.ERROR_MESSAGE.name to "invalid chapter"
                            )
                    )
            val chapter = toChapter(chapterJSON)
            val urlString =
                    inputData.getString(Constants.IntentDataKeys.CHAPTER_URL.name)
                    ?: QuranAPI.getChapterAudioFile(reciter.id, chapter.id)?.url
                    ?: return Result.failure(
                            workDataOf(
                                    DownloadWorkerInfo.DOWNLOAD_STATUS.name to DownloadStatus.DOWNLOAD_ERROR.name,
                                    DownloadWorkerInfo.ERROR_MESSAGE.name to "invalid URL"
                            )
                    )
            val url = URL(urlString)

            sharedPrefsManager.getChapterFile(reciter, chapter)?.let { chapterFile ->
                val chapterFileSize = Files.readAttributes(
                        chapterFile.toPath(), BasicFileAttributes::class.java
                ).size()

                Log.d(TAG, "[$timestamp] ${chapter.nameSimple} audio file exists in ${chapterFile.absoluteFile}")

                return Result.success(
                        workDataOf(
                                DownloadWorkerInfo.DOWNLOAD_STATUS.name to DownloadStatus.FILE_EXISTS.name,
                                DownloadWorkerInfo.BYTES_DOWNLOADED.name to chapterFileSize,
                                DownloadWorkerInfo.FILE_SIZE.name to chapterFileSize.toInt(),
                                DownloadWorkerInfo.FILE_PATH.name to chapterFile.absolutePath,
                                DownloadWorkerInfo.PROGRESS.name to 100f
                        )
                )
            } ?: run {
                Log.d(TAG, "[$timestamp] ${chapter.nameSimple} audio file does not exist, downloading...")

                return withContext(NonCancellable) { downloadFile(url, reciter, chapter, true) }
            }
        }

        if (chapters.isEmpty()) {
            return Result.failure(
                    workDataOf(
                            DownloadWorkerInfo.DOWNLOAD_STATUS.name to DownloadStatus.DOWNLOAD_ERROR.name,
                            DownloadWorkerInfo.ERROR_MESSAGE.name to "invalid chapters list"
                    )
            )
        }
        val chapterAudioFiles = QuranAPI.getReciterChaptersAudioFiles(reciterID = reciter.id)

        if (chapterAudioFiles.isEmpty()) {
            Log.d(TAG, "[$timestamp] cannot fetch chapter audio files for reciter #${reciter.id}: ${reciter.name}")

            return Result.failure(
                    workDataOf(
                            DownloadWorkerInfo.DOWNLOAD_STATUS.name to DownloadStatus.CONNECTION_FAILURE.name,
                            DownloadWorkerInfo.ERROR_MESSAGE.name to
                                    "cannot fetch chapter audio files for reciter #${reciter.id}: ${reciter.name}"
                    )
            )
        } else {
            var downloadedChapterCount = 0
            for (currentChapter in chapters) {
                if (isStopped) break

                sharedPrefsManager.getChapterFile(reciter, currentChapter)
                    ?.let { chapterFile ->
                        val chapterFileSize = Files.readAttributes(
                                chapterFile.toPath(), BasicFileAttributes::class.java
                        ).size()

                        Log.d(
                                TAG,
                                "[$timestamp] file ${chapterFile.name} ${chapterFileSize.formatted} bytes / " +
                                "${chapterFileSize.formatted} bytes (100%) exists and is complete, will not download!"
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
                    chapterAudioFile.chapterID == currentChapter.id
                }?.let { chapterAudioFile ->
                    val result =
                            withContext(NonCancellable) {
                                downloadFile(
                                        URL(chapterAudioFile.url),
                                        reciter,
                                        currentChapter,
                                        false
                                )
                            }
                    val downloadStatus =
                            result.outputData.getString(DownloadWorkerInfo.DOWNLOAD_STATUS.name)

                    if (downloadStatus == DownloadStatus.DOWNLOAD_ERROR.name) {
                        Log.d(TAG, "[$timestamp] failed to download ${chapterAudioFile.url}")
                        downloadedChapterCount--
                    }

                    setProgress(result.outputData)
                }

                downloadedChapterCount++
            }

            delay(150)

            if (downloadedChapterCount < context.resources.getInteger(R.integer.quran_chapter_count)) {
                return Result.failure(
                        workDataOf(
                                DownloadWorkerInfo.DOWNLOADED_CHAPTER_COUNT.name to downloadedChapterCount,
                                DownloadWorkerInfo.DOWNLOAD_STATUS.name to DownloadStatus.DOWNLOAD_ERROR.name,
                                DownloadWorkerInfo.PROGRESS.name to 100f
                        )
                )
            }

            return Result.success(
                    workDataOf(
                            DownloadWorkerInfo.DOWNLOADED_CHAPTER_COUNT.name to downloadedChapterCount,
                            DownloadWorkerInfo.DOWNLOAD_STATUS.name to DownloadStatus.FINISHED_DOWNLOAD.name,
                            DownloadWorkerInfo.PROGRESS.name to 100f
                    )
            )
        }
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

    private suspend fun downloadFile(
            url: URL,
            reciter: Reciter,
            chapter: Chapter,
            isSingleFileDownload: Boolean = true,
            threadCount: Int = 32
    ): Result {
        val chapterFile = Constants.getChapterFile(context, reciter, chapter)

        (withContext(Dispatchers.IO) { url.openConnection() } as HttpURLConnection).apply {
            connectTimeout = 0 // set connection timeout to infinity
            readTimeout = 0 // set read timeout to infinity
            requestMethod = "GET"
            setRequestProperty("Accept-Encoding", "identity")
            connect()

            if (responseCode !in 200..299) {
                return Result.failure(
                        workDataOf(
                                DownloadWorkerInfo.DOWNLOAD_STATUS.name to DownloadStatus.DOWNLOAD_ERROR.name,
                                DownloadWorkerInfo.ERROR_MESSAGE.name to "connection to $url returned a $responseCode response code",
                                DownloadWorkerInfo.DOWNLOAD_CHAPTER.name to fromChapter(chapter)
                        )
                )
            }

            setProgress(
                    getWorkData(
                            DownloadStatus.STARTING_DOWNLOAD,
                            0L,
                            0,
                            null,
                            0f,
                            if (isSingleFileDownload) null else chapter
                    )
            )
            val chapterAudioFileSize = contentLength

            val (notificationBuilder, notificationManager) =
                    createDownloadNotification(reciter, chapter)
            val qcDownloadFile = File("${chapterFile.absolutePath}.qcdownload")
            var offset = 0L
            if (chapterFile.exists()) {
                offset =
                        Files.readAttributes(
                                chapterFile.toPath(),
                                BasicFileAttributes::class.java
                        ).size()
                val progress = (offset.toFloat() / chapterAudioFileSize.toFloat() * 100)

                Log.d(
                        TAG,
                        "[$timestamp] file ${chapterFile.name} ${offset.formatted} bytes / ${chapterAudioFileSize.formatted} bytes " +
                        "(${DecimalFormat("000.000").format(progress)}%) exists but is not complete, resuming download..."
                )

                chapterFile.copyTo(qcDownloadFile, true)
                chapterFile.delete()
            } else {
                chapterFile.toPath().createParentDirectories()

                Log.d(
                        TAG,
                        "[$timestamp] file ${chapterFile.name} 0 / ${(chapterAudioFileSize - 1).formatted} " +
                        "bytes (0%) does not exist, starting download..."
                )
            }

            if (offset == chapterAudioFileSize.toLong()) {
                return Result.success(
                        getWorkData(
                                DownloadStatus.FILE_EXISTS,
                                chapterAudioFileSize.toLong(),
                                chapterAudioFileSize,
                                chapterFile.absolutePath,
                                100f,
                                if (isSingleFileDownload) null else chapter
                        )
                )
            }

            Log.d(TAG, "[$timestamp] skipping ${offset.formatted} bytes from $url...")

            disconnect()
            val totalDownloadSize = chapterAudioFileSize - offset
            val quantumSize = totalDownloadSize / threadCount
            var chapterDownloadedBytes = 0L
            var chapterProgress: Float
            val qcDownloadFileStream =
                    RandomAccessFile(qcDownloadFile, "rw")
            val jobs = mutableListOf<Deferred<Unit>>()
            val jobDownloadedBytesArray = LongArray(threadCount) { 0L }
            val jobOffsets = Array(threadCount) { JobOffset(0, 0, 0, 0) }

            Log.d(
                    TAG,
                    "[$timestamp] download of ${(totalDownloadSize - 1).formatted} bytes is scheduled on $threadCount threads, " +
                    "quantumSize: ${quantumSize.formatted} bytes"
            )

            for (jobID in 0..<threadCount) {
                val startOffset = offset + (quantumSize * jobID)
                val endOffset = startOffset + quantumSize - 1

                jobOffsets[jobID] = JobOffset(jobID, startOffset, endOffset, 0)
                val job = CoroutineScope(context = Dispatchers.IO).async(
                        context = CoroutineName(
                                "Thread #" +
                                jobID.toString().padStart(3, '0')
                        ),
                        start = CoroutineStart.LAZY
                ) {
                    val threadName = "${coroutineContext[CoroutineName.Key]?.name}"

                    Log.d(
                            TAG,
                            "[$timestamp] $threadName: starting download from offset ${startOffset.formatted} " +
                            "to offset ${endOffset.formatted}..."
                    )

                    executeDownload(
                            jobID,
                            url,
                            qcDownloadFileStream,
                            startOffset,
                            endOffset
                    ) { jobID, jobName, downloadedBytes, currentOffset ->
                        jobOffsets[jobID].currentOffset = currentOffset

                        jobDownloadedBytesArray[jobID] = downloadedBytes
                        val totalDownloadedBytes = jobDownloadedBytesArray.sum()
                        chapterDownloadedBytes = offset + totalDownloadedBytes
                        chapterProgress =
                                (chapterDownloadedBytes.toFloat() / (chapterAudioFileSize.toFloat() - 1)) * 100f
                        // Log.d(
                        //         TAG,
                        //         "[$timestamp] $jobName: downloading ${chapterFile.name} " +
                        //         "${chapterDownloadedBytes.formatted} bytes / ${(chapterAudioFileSize - 1).formatted} " +
                        //         "bytes (${DecimalFormat("000.000").format(chapterProgress)}%)..."
                        // )
                        setProgress(
                                getWorkData(
                                        DownloadStatus.DOWNLOADING,
                                        chapterDownloadedBytes,
                                        chapterAudioFileSize,
                                        null,
                                        chapterProgress,
                                        if (isSingleFileDownload) null else chapter
                                )
                        )

                        notificationBuilder
                            .setContentTitle(
                                    context.getString(
                                            R.string.loading_chapter,
                                            chapter.nameArabic
                                    )
                            )
                            .setContentText(
                                    "${decimalFormat.format(chapterDownloadedBytes.toFloat() / (1024f * 1024f))} مب." +
                                    " \\ ${decimalFormat.format(chapterAudioFileSize.toFloat() - 1 / (1024f * 1024f))}" +
                                    " مب. (${decimalFormat.format(chapterProgress)}٪)"
                            )
                            .setProgress(100, chapterProgress.toInt(), false)

                        notificationManager.notify(
                                R.integer.quran_chapter_download_notification_channel_id,
                                notificationBuilder.build()
                        )
                    }
                }

                jobs.add(job)
            }

            return withContext(NonCancellable) {
                jobOffsets.forEach { jobOffset -> Log.d(TAG, "[$timestamp] $jobOffset") }
                jobs.forEach(Deferred<Unit>::start)
                jobs.joinAll()

                if (!isStopped) {
                    postDownloadCleanup(
                            downloadInterrupted = false,
                            offset = offset,
                            reciter = reciter,
                            chapter = chapter,
                            chapterDownloadedBytes = chapterDownloadedBytes,
                            chapterAudioFileSize = chapterAudioFileSize,
                            chapterFile = chapterFile,
                            qcDownloadFileStream = qcDownloadFileStream,
                            jobOffsets = jobOffsets,
                            notificationManager = notificationManager,
                            cancelNotification = isSingleFileDownload
                    )

                    qcDownloadFile.renameTo(chapterFile)

                    Result.success(
                            getWorkData(
                                    DownloadStatus.FINISHED_DOWNLOAD,
                                    chapterAudioFileSize.toLong(),
                                    chapterAudioFileSize,
                                    chapterFile.absolutePath,
                                    100f,
                                    if (isSingleFileDownload) null else chapter
                            )
                    )
                } else {
                    postDownloadCleanup(
                            downloadInterrupted = true,
                            offset = offset,
                            chapterDownloadedBytes = chapterDownloadedBytes,
                            chapterAudioFileSize = chapterAudioFileSize,
                            chapterFile = chapterFile,
                            qcDownloadFileStream = qcDownloadFileStream,
                            jobOffsets = jobOffsets,
                            notificationManager = notificationManager,
                            cancelNotification = isSingleFileDownload
                    )

                    qcDownloadFile.delete()

                    Result.failure(
                            getWorkData(
                                    DownloadStatus.DOWNLOAD_INTERRUPTED,
                                    chapterAudioFileSize.toLong(),
                                    chapterAudioFileSize,
                                    chapterFile.absolutePath,
                                    100f,
                                    if (isSingleFileDownload) null else chapter
                            )
                    )
                }
            }
        }
    }

    private suspend fun executeDownload(
            jobID: Int,
            url: URL,
            chapterFileStream: RandomAccessFile,
            startOffset: Long,
            endOffset: Long,
            onProgressChanged: (suspend (jobID: Int, jobName: String, bytesDownloaded: Long, currentOffset: Long) -> Unit)? = null
    ) {
        val jobName = "${currentCoroutineContext[CoroutineName.Key]?.name}"

        (withContext(Dispatchers.IO) { url.openConnection() } as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 0 // infinite timeout
            readTimeout = 0 // infinite timeout
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Range", "bytes=$startOffset-")
            connect()
            var seekOffset = startOffset
            var bytesDownloaded = startOffset
            val buffer = ByteArray(32_768) // 32KB buffer size
            var bytes: Int

            do {
                bytes = inputStream.read(buffer)
                bytesDownloaded = (bytesDownloaded + bytes).cap(endOffset)
                // wait for the thread lock to be unlocked
                while (mutex.isLocked) Unit
                mutex.withLock {
                    chapterFileStream.seek(seekOffset)
                    chapterFileStream.write(buffer)
                    onProgressChanged?.invoke(
                            jobID,
                            jobName,
                            bytesDownloaded - startOffset,
                            chapterFileStream.filePointer - 1
                    )

                    // Log.d(TAG, "$jobName: ${bytesDownloaded.formatted} bytes / ${endOffset.formatted} bytes")
                }
                seekOffset += bytes
            } while (!isStopped && (bytesDownloaded < endOffset))

            Log.d(
                    TAG,
                    "[$timestamp] $jobName: ${
                        if (isStopped) "DOWNLOAD JOB INTERRUPTED at offset ${seekOffset.formatted}!!!"
                        else "DOWNLOAD JOB COMPLETE!"
                    } closing input stream..."
            )
            inputStream.close()

            Log.d(
                    TAG,
                    "[$timestamp] $jobName: ${
                        if (isStopped) "DOWNLOAD JOB INTERRUPTED at offset ${seekOffset.formatted}!!!"
                        else "DOWNLOAD JOB COMPLETE!"
                    } disconnecting from $url..."
            )
            disconnect()
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun postDownloadCleanup(
            downloadInterrupted: Boolean,
            reciter: Reciter? = null,
            chapter: Chapter? = null,
            offset: Long,
            chapterDownloadedBytes: Long,
            chapterAudioFileSize: Int,
            chapterFile: File,
            qcDownloadFileStream: RandomAccessFile,
            jobOffsets: Array<JobOffset>,
            notificationManager: NotificationManager,
            cancelNotification: Boolean = false
    ) {
        if (cancelNotification) notificationManager.cancel(R.integer.quran_chapter_download_notification_channel_id)

        if (!downloadInterrupted) {
            if ((reciter == null) || (chapter == null)) throw IllegalStateException("reciter or chapter parameter must not be null")

            qcDownloadFileStream.close()

            Log.d(
                    TAG,
                    "[$timestamp] downloaded ${chapterFile.name} ${chapterDownloadedBytes.formatted} bytes / ${chapterAudioFileSize.formatted} bytes " +
                    "(${DecimalFormat("000.000").format((chapterDownloadedBytes.toFloat() / chapterAudioFileSize.toFloat()) * 100f)}%)"
            )

            Log.d(
                    TAG,
                    "[$timestamp] saving ${chapter.nameSimple} for ${reciter.name} in ${chapterFile.absolutePath} with size of " +
                    "${chapterDownloadedBytes.formatted} bytes"
            )

            sharedPrefsManager.setChapterPath(reciter, chapter)
        } else {
            val hexFormat =
                    HexFormat {
                        bytes {
                            byteSeparator = " "
                            upperCase = true
                            bytePrefix = "0x"
                        }
                    }
            val fileOffsetsJsonFile = File("${chapterFile.absolutePath}.json")
            val bytesLeft = chapterAudioFileSize - chapterDownloadedBytes
            val progressLeft =
                    (bytesLeft.toFloat() / chapterAudioFileSize.toFloat()) * 100f
            val chapterFileStream = RandomAccessFile(chapterFile, "rw")
            val downloadedBytesByJobs = jobOffsets.sumOf { jobOffset ->
                jobOffset.currentOffset - jobOffset.startOffset
            }
            val totalDownloadedBytes = offset + downloadedBytesByJobs
            var totalParsedBytes = offset

            fileOffsetsJsonFile.writeText("{\n    ")
            fileOffsetsJsonFile.appendText("\"fileName\": \"${chapterFile.absolutePath}\",\n    ")

            Log.d(TAG, "=============================================================================================")
            Log.d(
                    TAG,
                    "[$timestamp] download is interrupted with ${bytesLeft.formatted} bytes left to download out of " +
                    "${chapterAudioFileSize.formatted} bytes (${DecimalFormat("000.000").format(progressLeft)}%)!!!"
            )

            Log.d(TAG, "=============================================================================================")
            Log.d(TAG, "[$timestamp] checking for data before offset: ${offset.formatted} in file: ${chapterFile.path}...")

            if (offset > 0) {
                Log.d(
                        TAG,
                        "[$timestamp] reading ${offset.formatted} bytes from the beginning of file: ${chapterFile.path}..."
                )
                val preOffsetBuffer = ByteArray(offset.toInt())
                qcDownloadFileStream.seek(0)
                qcDownloadFileStream.readFully(preOffsetBuffer, 0, offset.toInt())

                Log.d(TAG, "[$timestamp] writing ${offset.formatted} bytes to offset 0 in file: ${chapterFile.path}...")
                val preOffsetBufferFirstNSlice =
                        preOffsetBuffer.slice(0..<5).toByteArray().toHexString(hexFormat)
                val preOffsetBufferLastNSlice =
                        preOffsetBuffer.slice(preOffsetBuffer.size - 5..<preOffsetBuffer.size)
                            .toByteArray().toHexString(hexFormat)
                fileOffsetsJsonFile.appendText(
                        "\"offsetData_pre_jobs\": {\n        \"length\": " +
                        "\"${preOffsetBuffer.size}\",\n        "
                )
                fileOffsetsJsonFile.appendText(
                        "\"${chapterFileStream.filePointer}\": " +
                        "\"$preOffsetBufferFirstNSlice...$preOffsetBufferLastNSlice\"\n    "
                )
                fileOffsetsJsonFile.appendText("},\n")

                chapterFileStream.write(preOffsetBuffer)
            }

            Log.d(TAG, "=============================================================================================")
            Log.d(TAG, "[$timestamp] downloaded bytes by jobs: ${downloadedBytesByJobs.formatted} bytes")
            Log.d(TAG, "[$timestamp] total downloaded bytes: ${totalDownloadedBytes.formatted} bytes")
            Log.d(TAG, "=============================================================================================")

            chapterFileStream.seek(offset)
            jobOffsets.forEachIndexed { index, jobOffset ->
                val jobDownloadedBytes =
                        (jobOffset.currentOffset - jobOffset.startOffset).toInt()
                val buffer = ByteArray(jobDownloadedBytes)

                Log.d(TAG, "[$timestamp] $jobOffset:")

                Log.d(
                        TAG,
                        "[$timestamp] reading ${jobDownloadedBytes.formatted} bytes from offset: " +
                        "${jobOffset.startOffset.formatted} from Job #${jobOffset.jobID.formatted}..."
                )

                qcDownloadFileStream.seek(jobOffset.startOffset)
                qcDownloadFileStream.readFully(buffer, 0, jobDownloadedBytes)

                Log.d(
                        TAG,
                        "[$timestamp] writing ${buffer.size.formatted} bytes to offset: " +
                        "${chapterFileStream.filePointer.formatted} in file: ${chapterFile.path}..."
                )
                val bufferFirstNSlice =
                        buffer.slice(0..<5).toByteArray().toHexString(hexFormat)
                val bufferLastNSlice =
                        buffer.slice(buffer.size - 5..<buffer.size).toByteArray().toHexString(hexFormat)
                fileOffsetsJsonFile.appendText(
                        "\"offsetData_job_${jobOffset.jobID}\": {\n        \"length\": " +
                        "\"${buffer.size}\",\n        "
                )
                fileOffsetsJsonFile.appendText(
                        "\"${chapterFileStream.filePointer}\": " +
                        "\"$bufferFirstNSlice...$bufferLastNSlice\"\n    "
                )
                fileOffsetsJsonFile.appendText(
                        "}" +
                        if (index < jobOffsets.size - 1) ",\n    "
                        else "\n"
                )

                chapterFileStream.write(buffer)

                Log.d(TAG, "=============================================================================================")

                totalParsedBytes += buffer.size
            }

            fileOffsetsJsonFile.appendText("}")

            chapterFileStream.close()
            qcDownloadFileStream.close()

            Log.d(
                    TAG,
                    "[$timestamp] copied ${totalParsedBytes.formatted} bytes, chapter audio file size: ${chapterAudioFileSize.formatted} " +
                    "bytes (${DecimalFormat("000.000").format((totalParsedBytes.toFloat() / chapterAudioFileSize.toFloat()) * 100f)}%)!!!"
            )
        }
    }

    private fun createDownloadNotification(
            reciter: Reciter,
            chapter: Chapter
    ): Pair<NotificationCompat.Builder, NotificationManager> {
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
            .setSubText("${reciter.nameArabic} \\ ${chapter.nameArabic}")
            .setProgress(100, 0, false)
            .setSmallIcon(R.drawable.quran_icon_monochrome_black_64)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        val channel = NotificationChannel(
                context.getString(R.string.quran_download_notification_name),
                context.getString(R.string.quran_download_notification_name),
                NotificationManager.IMPORTANCE_HIGH
        ).apply { description = chapter.nameArabic }
        val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)

        return notificationBuilder to notificationManager
    }

    private infix fun Long.cap(max: Long): Long = if (this > max) max else this
    private inline val Number.formatted: String
        get() = numberFormat.format(this)

    private inner class JobOffset(
            val jobID: Int,
            val startOffset: Long,
            val endOffset: Long,
            var currentOffset: Long,
    ) {

        override fun toString(): String = "JobOffset(jobID=${jobID}, " +
                                          "startOffset=${startOffset.formatted}, " +
                                          "endOffset=${endOffset.formatted}, " +
                                          "currentOffset=${currentOffset.formatted})"
    }
}
