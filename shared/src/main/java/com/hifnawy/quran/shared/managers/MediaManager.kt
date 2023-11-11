package com.hifnawy.quran.shared.managers

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkInfo.State
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.hifnawy.quran.shared.R
import com.hifnawy.quran.shared.api.QuranAPI.getChaptersList
import com.hifnawy.quran.shared.api.QuranAPI.getReciterChaptersAudioFiles
import com.hifnawy.quran.shared.api.QuranAPI.getRecitersList
import com.hifnawy.quran.shared.managers.DownloadWorkManager.DownloadStatus
import com.hifnawy.quran.shared.managers.DownloadWorkManager.DownloadWorkerInfo
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.ChapterAudioFile
import com.hifnawy.quran.shared.model.Constants
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.storage.SharedPreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private val TAG = MediaManager::class.simpleName

@SuppressLint("StaticFieldLeak")
object MediaManager : LifecycleOwner {

    var onMediaReady: ((
            reciter: Reciter,
            chapter: Chapter,
            chapterAudioFile: File,
            chapterDrawable: Drawable?
    ) -> Unit)? = null
    var onSingleDownloadProgressUpdate: ((
            reciter: Reciter,
            chapter: Chapter,
            downloadStatus: DownloadStatus,
            bytesDownloaded: Long,
            fileSize: Int,
            percentage: Float,
            chapterAudioFile: File?
    ) -> Unit)? = null
    var onBulkDownloadProgressUpdate: ((
            reciter: Reciter,
            currentChapter: Chapter?,
            currentChapterIndex: Int,
            currentChapterDownloadStatus: DownloadStatus,
            currentChapterBytesDownloaded: Long,
            currentChapterFileSize: Int,
            currentChapterProgress: Float,
            allChaptersProgress: Float
    ) -> Unit)? = null
    var onBulkDownloadSucceed: ((
            reciter: Reciter,
            currentChapter: Chapter?,
            currentChapterIndex: Int,
            currentChapterDownloadStatus: DownloadStatus,
            currentChapterBytesDownloaded: Long,
            currentChapterFileSize: Int,
            currentChapterProgress: Float,
            allChaptersProgress: Float
    ) -> Unit)? = null
    var onBulkDownloadFail: ((
            reciter: Reciter,
            currentChapter: Chapter?,
            currentChapterIndex: Int,
            currentChapterDownloadStatus: DownloadStatus,
            currentChapterBytesDownloaded: Long,
            currentChapterFileSize: Int,
            currentChapterProgress: Float,
            allChaptersProgress: Float
    ) -> Unit)? = null
    private var reciters: List<Reciter> = listOf()
    private var chapters: List<Chapter> = listOf()
    private var chapterAudioFiles: List<ChapterAudioFile> = listOf()
    private var currentReciter: Reciter? = null
    private var currentChapter: Chapter? = null
    private lateinit var context: Context
    private lateinit var singleDownloadObserver: SingleDownloadObserver
    private lateinit var bulkDownloadObserver: BulkDownloadObserver
    private val sharedPrefsManager by lazy { SharedPreferencesManager(context) }
    private val lifecycleRegistry: LifecycleRegistry by lazy { LifecycleRegistry(this) }
    private val workManager by lazy { WorkManager.getInstance(context) }
    private val singleDownloadRequestID by lazy { UUID.fromString(context.getString(R.string.SINGLE_DOWNLOAD_WORK_REQUEST_ID)) }
    private val bulkDownloadRequestID by lazy { UUID.fromString(context.getString(R.string.BULK_DOWNLOAD_WORK_REQUEST_ID)) }
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    init {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    @Synchronized
    fun getInstance(context: Context): MediaManager {
        this.context = context.applicationContext
        return this
    }

    fun stopLifecycle() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        cancelPendingDownloads()
    }

    fun whenRecitersReady(onReady: (reciters: List<Reciter>) -> Unit) =
            if (reciters.isEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    reciters =
                            async { getRecitersList(context) }.await().sortedBy { reciter -> reciter.id }
                    withContext(Dispatchers.Main) {
                        onReady(reciters)
                    }
                }
                false
            } else {
                onReady(reciters)
                true
            }

    fun whenChaptersReady(onReady: (chapters: List<Chapter>) -> Unit): Boolean =
            if (chapters.isEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    chapters =
                            async { getChaptersList(context) }.await().sortedBy { chapter -> chapter.id }
                    withContext(Dispatchers.Main) {
                        onReady(chapters)
                    }
                }
                false
            } else {
                onReady(chapters)
                true
            }

    fun whenChapterAudioFilesReady(
            reciterID: Int,
            onReady: (chapterAudioFiles: List<ChapterAudioFile>) -> Unit
    ) =
            if (chapterAudioFiles.isEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    chapterAudioFiles =
                            getReciterChaptersAudioFiles(context, reciterID)
                                .sortedBy { chapterAudioFile -> chapterAudioFile.id }

                    withContext(Dispatchers.Main) {
                        onReady(chapterAudioFiles)
                    }
                }
                false
            } else {
                onReady(chapterAudioFiles)
                true
            }

    fun whenReady(onReady: (reciters: List<Reciter>, chapters: List<Chapter>) -> Unit) =
            if (reciters.isEmpty() || chapters.isEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    if (reciters.isEmpty()) reciters = async { getRecitersList(context) }.await()
                        .sortedBy { reciter -> reciter.id }
                    if (chapters.isEmpty()) chapters = async { getChaptersList(context) }.await()
                        .sortedBy { chapter -> chapter.id }

                    withContext(Dispatchers.Main) {
                        onReady(reciters, chapters)
                    }
                }
                false
            } else {
                onReady(reciters, chapters)
                true
            }

    fun whenReady(
            reciterID: Int,
            onReady: (reciters: List<Reciter>, chapters: List<Chapter>, chaptersAudioFiles: List<ChapterAudioFile>) -> Unit
    ) =
            if (reciters.isEmpty() || chapters.isEmpty() || chapterAudioFiles.isEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    if (reciters.isEmpty()) reciters = async { getRecitersList(context) }.await()
                        .sortedBy { reciter -> reciter.id }
                    if (chapters.isEmpty()) chapters = async { getChaptersList(context) }.await()
                        .sortedBy { chapter -> chapter.id }
                    if (chapterAudioFiles.isEmpty()) chapterAudioFiles =
                            async { getReciterChaptersAudioFiles(context, reciterID) }.await()
                                .sortedBy { chapterAudioFile -> chapterAudioFile.id }

                    withContext(Dispatchers.Main) {
                        onReady(reciters, chapters, chapterAudioFiles)
                    }
                }
                false
            } else {
                onReady(reciters, chapters, chapterAudioFiles)
                true
            }

    fun processChapter(reciter: Reciter, chapter: Chapter) {
        @SuppressLint("DiscouragedApi")
        val drawableId = context.resources.getIdentifier(
                "chapter_${chapter.id.toString().padStart(3, '0')}", "drawable", context.packageName
        )

        currentReciter = reciter
        currentChapter = chapter
        sharedPrefsManager.lastReciter = reciter
        sharedPrefsManager.lastChapter = chapter

        sharedPrefsManager.getChapterFile(reciter, chapter)?.let { chapterFile ->
            onMediaReady?.invoke(
                    reciter,
                    chapter,
                    chapterFile,
                    AppCompatResources.getDrawable(context, drawableId)
            )
        } ?: downloadChapter(reciter, chapter)
    }

    fun processNextChapter() {
        currentChapter =
                chapters.find { chapter ->
                    chapter.id == (
                            if (currentChapter!!.id == context.resources.getInteger(R.integer.quran_chapter_count)) 1
                            else currentChapter!!.id + 1)
                } ?: sharedPrefsManager.lastChapter

        Log.d(TAG, "Skipping to next Chapter: $currentChapter...")

        processChapter(currentReciter!!, currentChapter!!)
    }

    fun processPreviousChapter() {
        currentChapter =
                chapters.find { chapter ->
                    chapter.id == (
                            if (currentChapter!!.id == 1) context.resources.getInteger(
                                    R.integer.quran_chapter_count
                            )
                            else currentChapter!!.id - 1)
                } ?: sharedPrefsManager.lastChapter

        Log.d(TAG, "Skipping to previous Chapter: $currentChapter...")

        processChapter(currentReciter!!, currentChapter!!)
    }

    fun cancelPendingDownloads() {
        DownloadWorkManager.isCancelled = true
        // workManager.cancelWorkById(singleDownloadRequestID)
        // workManager.cancelWorkById(bulkDownloadRequestID)
    }

    fun downloadChapters(reciter: Reciter) {
        DownloadWorkManager.chapters = chapters
        val bulkDownloadWorkRequest = OneTimeWorkRequestBuilder<DownloadWorkManager>()
            .setInputData(
                    workDataOf(
                            Constants.IntentDataKeys.IS_SINGLE_DOWNLOAD.name to false,
                            Constants.IntentDataKeys.RECITER.name to
                                    DownloadWorkManager.fromReciter(reciter)
                    )
            )
            .setId(bulkDownloadRequestID)
            .build()

        observeBulkDownloadProgress(bulkDownloadRequestID, reciter)

        workManager.enqueueUniqueWork(
                context.getString(R.string.bulkDownloadWorkManagerUniqueWorkName),
                ExistingWorkPolicy.REPLACE,
                bulkDownloadWorkRequest
        )
    }

    private fun downloadChapter(reciter: Reciter, chapter: Chapter) {
        Log.d(TAG, "Downloading ${reciter.reciter_name} - ${chapter.name_simple}")
        val singleDownloadWorkRequest = OneTimeWorkRequestBuilder<DownloadWorkManager>()
            .setInputData(
                    workDataOf(
                            Constants.IntentDataKeys.IS_SINGLE_DOWNLOAD.name to true,
                            Constants.IntentDataKeys.RECITER.name to
                                    DownloadWorkManager.fromReciter(reciter),
                            Constants.IntentDataKeys.CHAPTER.name to
                                    DownloadWorkManager.fromChapter(chapter)
                    )
            )
            .setId(singleDownloadRequestID)
            .build()

        observeSingleDownloadProgress(singleDownloadRequestID, reciter, chapter)

        workManager.enqueueUniqueWork(
                context.getString(R.string.singleDownloadWorkManagerUniqueWorkName),
                ExistingWorkPolicy.REPLACE,
                singleDownloadWorkRequest
        )
    }

    private fun observeSingleDownloadProgress(requestID: UUID, reciter: Reciter, chapter: Chapter) {
        if (!this::singleDownloadObserver.isInitialized) {
            singleDownloadObserver = SingleDownloadObserver(reciter, chapter)
            workManager.getWorkInfoByIdLiveData(requestID).observe(this, singleDownloadObserver)
        } else {
            singleDownloadObserver.reciter = reciter
            singleDownloadObserver.chapter = chapter
        }
    }

    private fun observeBulkDownloadProgress(requestID: UUID, reciter: Reciter) {
        if (!this::bulkDownloadObserver.isInitialized) {
            bulkDownloadObserver = BulkDownloadObserver(reciter)
            workManager.getWorkInfoByIdLiveData(requestID).observe(this, bulkDownloadObserver)
        } else {
            bulkDownloadObserver.reciter = reciter
        }
    }

    internal class SingleDownloadObserver(var reciter: Reciter, var chapter: Chapter) :
            Observer<WorkInfo?> {

        override fun onChanged(value: WorkInfo?) {
            if (value == null) return
            if ((value.state != State.RUNNING) && (value.state != State.SUCCEEDED)) return
            val dataSource =
                    if (value.state == State.SUCCEEDED) value.outputData
                    else value.progress

            Log.d(
                    TAG,
                    "DownloadWorkManager: ${value.state} - ${reciter.reciter_name} - ${chapter.name_simple}\n$dataSource"
            )
            val downloadStatus = DownloadStatus.valueOf(
                    (dataSource.getString(DownloadWorkerInfo.DOWNLOAD_STATUS.name))
                    ?: return
            )
            val fileSize =
                    dataSource.getInt(DownloadWorkerInfo.FILE_SIZE.name, -1)
            val chapterFilePath =
                    dataSource.getString(DownloadWorkerInfo.FILE_PATH.name)
            val bytesDownloaded =
                    dataSource.getLong(
                            DownloadWorkerInfo.BYTES_DOWNLOADED.name,
                            -1L
                    )
            val progress =
                    dataSource.getFloat(DownloadWorkerInfo.PROGRESS.name, -1f)

            @SuppressLint("DiscouragedApi")
            val drawableId = context.resources.getIdentifier(
                    "chapter_${chapter.id.toString().padStart(3, '0')}",
                    "drawable",
                    context.packageName
            )
            when (downloadStatus) {
                DownloadStatus.STARTING_DOWNLOAD,
                DownloadStatus.DOWNLOAD_INTERRUPTED,
                DownloadStatus.DOWNLOAD_ERROR,
                DownloadStatus.DOWNLOADING       -> {
                    onSingleDownloadProgressUpdate?.invoke(
                            reciter,
                            chapter,
                            downloadStatus,
                            bytesDownloaded,
                            fileSize,
                            progress,
                            null
                    )
                }

                DownloadStatus.FILE_EXISTS,
                DownloadStatus.FINISHED_DOWNLOAD -> {
                    sharedPrefsManager.getChapterFile(reciter, chapter)?.let { chapterFile ->
                        onSingleDownloadProgressUpdate?.invoke(
                                reciter,
                                chapter,
                                downloadStatus,
                                bytesDownloaded,
                                fileSize,
                                progress,
                                chapterFile
                        )

                        onMediaReady?.invoke(
                                reciter,
                                chapter,
                                chapterFile,
                                AppCompatResources.getDrawable(context, drawableId)
                        )
                    }
                }
            }
        }
    }

    internal class BulkDownloadObserver(var reciter: Reciter) : Observer<WorkInfo?> {

        override fun onChanged(value: WorkInfo?) {
            if (value == null) return

            Log.d(TAG, "${value.state}\n$value")
            val dataSource =
                    if ((value.state == State.SUCCEEDED) || (value.state == State.FAILED)) value.outputData
                    else value.progress

            if (value.state == State.SUCCEEDED) {
                onBulkDownloadSucceed?.invoke(
                        reciter,
                        null,
                        context.resources.getInteger(R.integer.quran_chapter_count),
                        DownloadStatus.DOWNLOAD_ERROR,
                        0,
                        0,
                        0f,
                        0f
                )
                return
            }

            if (value.state == State.FAILED) {
                if (dataSource.keyValueMap.isEmpty()) {
                    onBulkDownloadFail?.invoke(
                            reciter,
                            null,
                            context.resources.getInteger(R.integer.quran_chapter_count),
                            DownloadStatus.DOWNLOAD_ERROR,
                            0,
                            0,
                            0f,
                            0f
                    )
                } else {
                    onBulkDownloadProgressUpdate?.invoke(
                            reciter,
                            null,
                            context.resources.getInteger(R.integer.quran_chapter_count),
                            DownloadStatus.DOWNLOAD_ERROR,
                            0,
                            0,
                            0f,
                            0f
                    )
                }
                return
            }

            Log.d(TAG, "${value.state}\n$dataSource")
            val currentChapterJSON = dataSource.getString(DownloadWorkerInfo.DOWNLOAD_CHAPTER.name)
            val currentChapter = DownloadWorkManager.toChapter(currentChapterJSON)
            val downloadStatus = DownloadStatus.valueOf(
                    dataSource.getString(DownloadWorkerInfo.DOWNLOAD_STATUS.name)
                    ?: return
            )
            val bytesDownloaded = dataSource.getLong(DownloadWorkerInfo.BYTES_DOWNLOADED.name, -1L)
            val fileSize = dataSource.getInt(DownloadWorkerInfo.FILE_SIZE.name, -1)
            val progress = dataSource.getFloat(DownloadWorkerInfo.PROGRESS.name, -1f)
            val currentChapterIndex =
                    chapters.indexOf(chapters.find { chapter -> chapter.id == currentChapter.id }) + 1
            val chaptersDownloadProgress =
                    (currentChapterIndex.toFloat() / chapters.size.toFloat()) * 100f

            onBulkDownloadProgressUpdate?.invoke(
                    reciter,
                    currentChapter,
                    currentChapterIndex,
                    downloadStatus,
                    bytesDownloaded,
                    fileSize,
                    progress,
                    chaptersDownloadProgress
            )
        }
    }
}
