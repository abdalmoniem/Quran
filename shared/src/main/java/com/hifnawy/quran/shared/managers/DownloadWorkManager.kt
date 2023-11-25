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
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
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
import java.io.Serializable
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

            return sharedPrefsManager.getChapterFile(reciter, chapter)?.let { chapterFile ->
                // val chapterFileSize = Files.readAttributes(chapterFile.toPath(), BasicFileAttributes::class.java).size()
                val chapterFileSize = chapterFile.length()

                Log.d(TAG, "[$timestamp] ${chapter.nameSimple} audio file exists in ${chapterFile.absoluteFile}")

                return@let Result.success(
                        workDataOf(
                                DownloadWorkerInfo.DOWNLOAD_STATUS.name to DownloadStatus.FILE_EXISTS.name,
                                DownloadWorkerInfo.BYTES_DOWNLOADED.name to chapterFileSize,
                                DownloadWorkerInfo.FILE_SIZE.name to chapterFileSize.toInt(),
                                DownloadWorkerInfo.FILE_PATH.name to chapterFile.absolutePath,
                                DownloadWorkerInfo.PROGRESS.name to 100f
                        )
                )
            } ?: withContext(NonCancellable) { downloadFile(url, reciter, chapter, true) }
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
            chunkCount: Int = 32
    ): Result {
        val chapterFile = Constants.getChapterFile(context, reciter, chapter)

        if (chapterFile.exists()) {
            return Result.success(
                    getWorkData(
                            DownloadStatus.FILE_EXISTS,
                            chapterFile.length(),
                            chapterFile.length().toInt(),
                            chapterFile.absolutePath,
                            100f,
                            if (isSingleFileDownload) null else chapter
                    )
            )
        }

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
            var chunks = Array(chunkCount) { Chunk() }
            val qcdFile = File("${chapterFile.absolutePath}.qcd")
            val chunksFile = File("${chapterFile.absolutePath}.chunks.json")
            if (qcdFile.exists()) {
                val chunksText = chunksFile.readText()
                val chunksJsonObject = gsonParser.fromJson(chunksText, JsonObject::class.java)
                chunks = gsonParser.fromJson(chunksJsonObject.get("chunks"), Array<Chunk>::class.java)
                val downloaded = chunksJsonObject.get("totalDownloaded").asLong
                val progress = downloaded.toFloat() / chapterAudioFileSize.toFloat() * 100f

                Log.d(
                        TAG, "[$timestamp] file ${chapterFile.name} ${downloaded.formatted} bytes / " +
                             "${chapterAudioFileSize.formatted} bytes (${DecimalFormat("000.000").format(progress)}%)" +
                             "exists but is not complete, resuming download..."
                )
            } else {
                chapterFile.toPath().createParentDirectories()

                Log.d(
                        TAG, "[$timestamp] file ${chapterFile.name} 0 bytes / ${chapterAudioFileSize.formatted} bytes" +
                             "(000.000%) does not exist, starting download..."
                )
            }

            disconnect()
            val chunkSize = (chapterAudioFileSize / chunkCount).toLong()
            var chapterDownloadedBytes = 0L
            val qcdFileStream =
                    RandomAccessFile(qcdFile, "rw")
            val jobs = mutableListOf<Deferred<Unit>>()
            val jobDownloadedBytesArray = LongArray(chunkCount) { 0L }
            var chapterProgress: Float

            Log.d(
                    TAG,
                    "[$timestamp] download of ${(chapterAudioFileSize).formatted} bytes is scheduled on $chunkCount threads, " +
                    "chunkSize: ${chunkSize.formatted} bytes"
            )

            for (chunkID in 0..<chunkCount) {
                if ((chunks[chunkID].currentOffset == chunks[chunkID].endOffset) && chunks[chunkID].endOffset != -1L) continue
                val startOffset =
                        if (chunks[chunkID].startOffset == -1L) chunkSize * chunkID
                        else chunks[chunkID].currentOffset
                val endOffset =
                        if (chunks[chunkID].endOffset == -1L) startOffset + chunkSize - 1
                        else chunks[chunkID].endOffset

                chunks[chunkID].apply {
                    this.id = chunkID
                    this.startOffset = startOffset
                    this.endOffset = endOffset
                }
                val chunk = chunks[chunkID]
                val job = CoroutineScope(context = Dispatchers.IO).async(
                        context = CoroutineName(
                                "Chunk #" +
                                chunkID.toString().padStart(3, '0')
                        ),
                        start = CoroutineStart.LAZY
                ) {
                    val chunkJobName = "${coroutineContext[CoroutineName.Key]?.name}"

                    Log.d(
                            TAG,
                            "[$timestamp] $chunkJobName: starting download from offset ${chunk.startOffset.formatted} " +
                            "to offset ${chunk.endOffset.formatted}..."
                    )

                    downloadChunk(
                            url,
                            qcdFileStream,
                            chunks[chunkID]
                    ) { chunkName, chunk ->
                        jobDownloadedBytesArray[chunkID] = chunk.downloaded
                        chapterDownloadedBytes = jobDownloadedBytesArray.sum()
                        chapterProgress =
                                (chapterDownloadedBytes.toFloat() / chapterAudioFileSize.toFloat()) * 100f
                        Log.d(
                                TAG,
                                "[$timestamp] $chunkName: downloading ${chapterFile.name} " +
                                "${chapterDownloadedBytes.formatted} bytes / ${chapterAudioFileSize.formatted} " +
                                "bytes (${DecimalFormat("000.000").format(chapterProgress)}%)..."
                        )
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

                        updateDownloadNotification(
                                notificationBuilder,
                                notificationManager,
                                chapter,
                                chapterDownloadedBytes,
                                chapterAudioFileSize,
                                chapterProgress
                        )
                    }
                }

                jobs.add(job)
            }

            return withContext(NonCancellable) {
                chunks.forEach { chunk -> Log.d(TAG, "[$timestamp] $chunk") }
                jobs.forEach(Deferred<Unit>::start)
                jobs.joinAll()

                if (!isStopped) {
                    postDownloadCleanup(
                            reciter = reciter,
                            chapter = chapter,
                            chapterDownloadedBytes = chapterDownloadedBytes,
                            chapterAudioFileSize = chapterAudioFileSize,
                            chapterFile = chapterFile,
                            qcdFile = qcdFile,
                            qcdFileStream = qcdFileStream,
                            chunks = chunks,
                            notificationManager = notificationManager,
                            cancelNotification = isSingleFileDownload,
                            downloadInterrupted = false
                    )

                    qcdFile.renameTo(chapterFile)
                    chunksFile.delete()

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
                            chapterDownloadedBytes = chapterDownloadedBytes,
                            chapterAudioFileSize = chapterAudioFileSize,
                            chapterFile = chapterFile,
                            chunksFile = chunksFile,
                            qcdFile = qcdFile,
                            qcdFileStream = qcdFileStream,
                            chunks = chunks,
                            notificationManager = notificationManager,
                            cancelNotification = isSingleFileDownload,
                            downloadInterrupted = true
                    )

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

    private suspend fun downloadChunk(
            url: URL,
            chapterFileStream: RandomAccessFile,
            chunk: Chunk,
            onProgressChanged: (suspend (chunkName: String, chunk: Chunk) -> Unit)? = null
    ) {
        val chunkName = "${currentCoroutineContext[CoroutineName.Key]?.name}"

        (withContext(Dispatchers.IO) { url.openConnection() } as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 0 // infinite timeout
            readTimeout = 0 // infinite timeout
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Range", "bytes=${chunk.startOffset}-${chunk.endOffset}")
            connect()
            val buffer = ByteArray(32_768) // 32KB buffer size
            // val buffer = ByteArray(4_096) // 4KB buffer size
            val chunkDownloaded = if (chunk.downloaded == -1L) 0 else chunk.downloaded
            chunk.currentOffset = chunk.startOffset

            chunk.downloaded = chunkDownloaded
            onProgressChanged?.invoke(chunkName, chunk)

            do {
                // wait for the thread lock to be unlocked
                while (mutex.isLocked) Unit
                mutex.withLock {
                    val bytes = inputStream.read(buffer)
                    // Log.d(
                    //         TAG,
                    //         "[$timestamp] $chunkName - read ${bytes.formatted} bytes, total: ${(chunk.currentOffset + bytes).formatted} bytes!"
                    // )
                    chapterFileStream.seek(chunk.currentOffset)
                    chapterFileStream.write(buffer, 0, bytes)

                    chunk.currentOffset += bytes

                    chunk.downloaded = chunkDownloaded + chunk.currentOffset - chunk.startOffset

                    onProgressChanged?.invoke(chunkName, chunk)
                    // Log.d(
                    //         TAG,
                    //         "[$timestamp] $chunkName - ${chunk.startOffset.formatted}: " +
                    //         "${chunk.downloaded.formatted} bytes / ${chunk.endOffset.formatted} bytes"
                    // )
                }
            } while (!isStopped && (chunk.currentOffset < chunk.endOffset))

            Log.d(
                    TAG,
                    "[$timestamp] $chunkName: ${
                        if (isStopped) "DOWNLOAD JOB INTERRUPTED at offset ${chunk.currentOffset.formatted}!!!"
                        else "DOWNLOAD JOB COMPLETE!"
                    }"
            )
            Log.d(TAG, "[$timestamp] $chunkName: closing input stream...")
            inputStream.close()

            Log.d(
                    TAG,
                    "[$timestamp] $chunkName: ${
                        if (isStopped) "DOWNLOAD JOB INTERRUPTED at offset ${chunk.currentOffset.formatted}!!!"
                        else "DOWNLOAD JOB COMPLETE!"
                    }"
            )
            Log.d(TAG, "[$timestamp] $chunkName: disconnecting from $url...")
            disconnect()
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun postDownloadCleanup(
            reciter: Reciter? = null,
            chapter: Chapter? = null,
            chapterDownloadedBytes: Long,
            chapterAudioFileSize: Int,
            chapterFile: File,
            chunksFile: File? = null,
            qcdFile: File,
            qcdFileStream: RandomAccessFile,
            chunks: Array<Chunk>,
            notificationManager: NotificationManager,
            cancelNotification: Boolean = false,
            downloadInterrupted: Boolean
    ) {
        if (cancelNotification) notificationManager.cancel(R.integer.quran_chapter_download_notification_channel_id)

        if (!downloadInterrupted) {
            if ((reciter == null) || (chapter == null)) throw IllegalStateException("reciter or chapter parameter must not be null")

            qcdFileStream.close()

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
            require(chunksFile != null) { "chunksFile is null" }
            val hexFormat =
                    HexFormat {
                        bytes {
                            byteSeparator = " "
                            upperCase = true
                            bytePrefix = "0x"
                        }
                    }
            val gsonFormatter = GsonBuilder().setPrettyPrinting().create()
            val bytesLeft = chapterAudioFileSize - chapterDownloadedBytes
            val progressLeft = (bytesLeft.toFloat() / chapterAudioFileSize.toFloat()) * 100f
            val downloadedBytesByJobs =
                    chunks.sumOf { chunk ->
                        if (chunk.currentOffset >= chunk.startOffset) chunk.currentOffset - chunk.startOffset
                        else chunk.currentOffset
                    }
            var totalParsedBytes = 0L
            val fileOffsetsJsonObject = JsonObject()
            val fileOffsetsJsonArray = JsonArray()

            fileOffsetsJsonObject.addProperty("fileName", qcdFile.absolutePath)
            fileOffsetsJsonObject.addProperty("totalDownloaded", downloadedBytesByJobs)

            Log.d(TAG, "=============================================================================================")
            Log.d(
                    TAG,
                    "[$timestamp] download is interrupted with ${bytesLeft.formatted} bytes left to download out of " +
                    "${chapterAudioFileSize.formatted} bytes (${DecimalFormat("000.000").format(progressLeft)}%)!!!"
            )

            Log.d(TAG, "=============================================================================================")
            Log.d(TAG, "[$timestamp] downloaded bytes by jobs: ${downloadedBytesByJobs.formatted} bytes")
            Log.d(TAG, "[$timestamp] total downloaded bytes: ${downloadedBytesByJobs.formatted} bytes")
            Log.d(TAG, "=============================================================================================")

            for (chunk in chunks) {
                val buffer = ByteArray((chunk.currentOffset - chunk.startOffset).toInt())

                Log.d(TAG, "[$timestamp] $chunk")
                Log.d(
                        TAG,
                        "[$timestamp] reading ${chunk.downloaded.formatted} bytes from offset: " +
                        "${chunk.startOffset.formatted} from Chunk #${chunk.id.formatted}..."
                )

                qcdFileStream.seek(chunk.startOffset)
                qcdFileStream.readFully(buffer)
                val bufferFirstNSlice =
                        buffer.slice(0..<5).toByteArray().toHexString(hexFormat)
                val bufferLastNSlice =
                        buffer.slice(buffer.size - 5..<buffer.size).toByteArray().toHexString(hexFormat)

                chunk.dataPreview = "$bufferFirstNSlice...$bufferLastNSlice"

                if (chunk.downloaded == chunk.endOffset) {
                    Log.d(TAG, "[$timestamp] data from chunk #${chunk.id.formatted} is complete, skipping this chunk...")
                    Log.d(TAG, "[$timestamp] $chunk")
                    Log.d(
                            TAG,
                            "============================================================================================="
                    )
                    continue
                }

                Log.d(TAG, "[$timestamp] $chunk")
                Log.d(TAG, "[$timestamp] saving ${buffer.size.formatted} bytes to ${chunksFile.name}...")

                fileOffsetsJsonArray.add(chunk.jsonObject)

                Log.d(TAG, "=============================================================================================")

                totalParsedBytes += buffer.size
            }

            fileOffsetsJsonObject.add("chunks", fileOffsetsJsonArray)

            chunksFile.writeText(gsonFormatter.toJson(fileOffsetsJsonObject))
            qcdFileStream.close()
            Log.d(
                    TAG,
                    "[$timestamp] saved ${totalParsedBytes.formatted} bytes, chapter audio file size: ${chapterAudioFileSize.formatted} " +
                    "bytes (${DecimalFormat("000.000").format((totalParsedBytes.toFloat() / chapterAudioFileSize.toFloat()) * 100f)}%)!!!\n" +
                    "to ${chunksFile.absolutePath}"
            )
            Log.d(TAG, "fileOffsetsJsonObject.asString: ${fileOffsetsJsonObject.asJsonString}")
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

    private fun updateDownloadNotification(
            notificationBuilder: NotificationCompat.Builder,
            notificationManager: NotificationManager,
            chapter: Chapter,
            chapterDownloadedBytes: Long,
            chapterAudioFileSize: Int,
            chapterProgress: Float
    ) {
        notificationBuilder
            .setContentTitle(
                    context.getString(R.string.loading_chapter, chapter.nameArabic)
            )
            .setContentText(
                    "${decimalFormat.format(chapterDownloadedBytes.toFloat() / (1024f * 1024f))} مب." +
                    " \\ ${decimalFormat.format(chapterAudioFileSize.toFloat() / (1024f * 1024f))}" +
                    " مب. (${decimalFormat.format(chapterProgress)}٪)"
            )
            .setProgress(100, chapterProgress.toInt(), false)

        notificationManager.notify(R.integer.quran_chapter_download_notification_channel_id, notificationBuilder.build())
    }

    private val JsonObject.asJsonString
        get() = GsonBuilder().setPrettyPrinting().create().toJson(this)

    private data class Chunk(
            var id: Int = -1,
            var startOffset: Long = -1L,
            var endOffset: Long = -1L,
            var currentOffset: Long = -1L,
            var downloaded: Long = -1L,
            var dataPreview: String = ""
    ) : Serializable {

        @Transient
        private val gson = GsonBuilder().setPrettyPrinting().create()
        private val jsonString: String
            get() = gson.toJson(this)
        val jsonObject: JsonObject
            get() = gson.fromJson(jsonString, JsonObject::class.java)

        override fun toString(): String = "${this.javaClass.simpleName}(" +
                                          "${::id.name}=${id}, " +
                                          "${::startOffset.name}=${startOffset.formatted}, " +
                                          "${::endOffset.name}=${endOffset.formatted}, " +
                                          "${::currentOffset.name}=${currentOffset.formatted}, " +
                                          "${::downloaded.name}=${downloaded.formatted}, " +
                                          "${::dataPreview.name}=$dataPreview" +
                                          ")"
    }
}

private val numberFormat = NumberFormat.getNumberInstance(Locale.ENGLISH)
private inline val Number.formatted: String
    get() = numberFormat.format(this)
