package com.hifnawy.quran.shared.managers

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.hifnawy.quran.shared.R
import com.hifnawy.quran.shared.api.QuranAPI.getChaptersList
import com.hifnawy.quran.shared.api.QuranAPI.getRecitersList
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.tools.Constants
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.storage.SharedPreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture.supplyAsync
import java.util.concurrent.Future
import kotlin.reflect.KProperty

@Suppress("PrivatePropertyName")
private val TAG = MediaManager::class.simpleName

@SuppressLint("StaticFieldLeak")
object MediaManager : LifecycleOwner {

    fun interface DownloadListener {

        fun onProgressChanged(
                reciter: Reciter,
                chapter: Chapter,
                downloadStatus: DownloadWorkManager.DownloadStatus,
                bytesDownloaded: Long,
                fileSize: Int,
                percentage: Float,
                chapterAudioFile: File?
        )

    }

    fun interface MediaStateListener {

        fun onMediaReady(
                reciter: Reciter,
                chapter: Chapter,
                chapterAudioFile: File,
                chapterDrawable: Drawable?
        )

    }

    var downloadListener: DownloadListener? = null
    var mediaStateListener: MediaStateListener? = null
    var reciters: List<Reciter> = mutableListOf()
    var chapters: List<Chapter> = mutableListOf()
    val recitersDeferred: Deferred<List<Reciter>> by supplyAsync {
        ioCoroutineScope.async {
            reciters.ifEmpty {
                reciters = getRecitersList()
                reciters
            }
        }
    }

    val chaptersDeferred: Deferred<List<Chapter>> by supplyAsync { ioCoroutineScope.async { getChaptersList() } }
    private lateinit var context: Context
    private var currentReciter: Reciter? = null
    private var currentChapter: Chapter? = null
    private val ioCoroutineScope by lazy { CoroutineScope(Dispatchers.IO) }
    private val lifecycleRegistry: LifecycleRegistry by lazy { LifecycleRegistry(this) }
    private val sharedPrefsManager by lazy { SharedPreferencesManager(context) }
    private val downloadRequestID by lazy { UUID.fromString(context.getString(R.string.SINGLE_DOWNLOAD_WORK_REQUEST_ID)) }
    private val workManager by lazy { WorkManager.getInstance(context) }
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    init {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    @Synchronized
    fun getInstance(context: Context): MediaManager = this.also {
        this.context = context.applicationContext
    }

    fun stopLifecycle() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        workManager.cancelWorkById(downloadRequestID)
    }

    suspend fun initializeData(onDataFetched: (suspend () -> Unit)? = null) {
        if (reciters.isEmpty()) reciters = ioCoroutineScope.async { getRecitersList() }.await()
        if (chapters.isEmpty()) chapters = ioCoroutineScope.async { getChaptersList() }.await()

        onDataFetched?.invoke()
    }

    suspend fun processChapter(reciter: Reciter, chapter: Chapter) {
        @SuppressLint("DiscouragedApi")
        val drawableId = context.resources.getIdentifier(
                "chapter_${chapter.id.toString().padStart(3, '0')}",
                "drawable",
                context.packageName
        )

        currentReciter = reciter
        currentChapter = chapter
        sharedPrefsManager.lastReciter = reciter
        sharedPrefsManager.lastChapter = chapter

        sharedPrefsManager.getChapterPath(reciter, chapter)?.let { chapterAudioFile ->
            withContext(Dispatchers.Main) {
                mediaStateListener?.onMediaReady(
                        reciter,
                        chapter,
                        File(chapterAudioFile),
                        AppCompatResources.getDrawable(context, drawableId)
                )
            }
        } ?: withContext(Dispatchers.Main) { downloadChapter(reciter, chapter) }
    }

    suspend fun processNextChapter() {
        currentChapter =
            chapters.find { chapter ->
                chapter.id == (if (currentChapter!!.id == context.resources.getInteger(
                            R.integer.quran_chapter_count
                    )
                ) 1 else currentChapter!!.id + 1)
            }
                ?: sharedPrefsManager.lastChapter

        Log.d(TAG, "Skipping to next Chapter...")

        processChapter(currentReciter!!, currentChapter!!)
    }

    suspend fun processPreviousChapter() {
        currentChapter =
            chapters.find { chapter ->
                chapter.id == (if (currentChapter!!.id == 1) context.resources.getInteger(
                        R.integer.quran_chapter_count
                ) else currentChapter!!.id - 1)
            }
                ?: sharedPrefsManager.lastChapter

        Log.d(TAG, "Skipping to previous Chapter...")

        processChapter(currentReciter!!, currentChapter!!)
    }

    fun cancelPendingDownloads() = workManager.cancelWorkById(downloadRequestID)

    private fun downloadChapter(reciter: Reciter, chapter: Chapter) {
        val downloadWorkRequest = OneTimeWorkRequestBuilder<DownloadWorkManager>()
            .setInputData(
                    workDataOf(
                            Constants.IntentDataKeys.SINGLE_DOWNLOAD_TYPE.name to true,
                            Constants.IntentDataKeys.RECITER.name to DownloadWorkManager.fromReciter(
                                    reciter
                            ),
                            Constants.IntentDataKeys.CHAPTER.name to DownloadWorkManager.fromChapter(
                                    chapter
                            )
                    )
            )
            .setId(downloadRequestID)
            .build()

        observeWorker(downloadRequestID, reciter, chapter)

        workManager.enqueueUniqueWork(
                context.getString(R.string.singleDownloadWorkManagerUniqueWorkName),
                ExistingWorkPolicy.REPLACE,
                downloadWorkRequest
        )
    }

    private fun observeWorker(requestID: UUID, reciter: Reciter, chapter: Chapter) {
        val workManager = WorkManager.getInstance(context)
        workManager.getWorkInfoByIdLiveData(requestID)
            .observe(this) { workInfo ->
                if (workInfo == null) return@observe
                if ((workInfo.state != WorkInfo.State.RUNNING) && (workInfo.state != WorkInfo.State.SUCCEEDED)) return@observe
                val dataSource =
                    if (workInfo.state == WorkInfo.State.SUCCEEDED) workInfo.outputData else workInfo.progress

                Log.d(TAG, "${workInfo.state} - $dataSource")
                val downloadStatus = DownloadWorkManager.DownloadStatus.valueOf(
                        (dataSource.getString(DownloadWorkManager.DownloadWorkerInfo.DOWNLOAD_STATUS.name))
                            ?: return@observe
                )
                val fileSize =
                    dataSource.getInt(DownloadWorkManager.DownloadWorkerInfo.FILE_SIZE.name, -1)
                val chapterFilePath =
                    dataSource.getString(DownloadWorkManager.DownloadWorkerInfo.FILE_PATH.name)
                val bytesDownloaded =
                    dataSource.getLong(DownloadWorkManager.DownloadWorkerInfo.BYTES_DOWNLOADED.name, -1L)
                val progress =
                    dataSource.getFloat(DownloadWorkManager.DownloadWorkerInfo.PROGRESS.name, -1f)
                @SuppressLint("DiscouragedApi") val drawableId = context.resources.getIdentifier(
                        "chapter_${chapter.id.toString().padStart(3, '0')}",
                        "drawable",
                        context.packageName
                )
                when (downloadStatus) {
                    DownloadWorkManager.DownloadStatus.STARTING_DOWNLOAD,
                    DownloadWorkManager.DownloadStatus.DOWNLOAD_INTERRUPTED,
                    DownloadWorkManager.DownloadStatus.DOWNLOADING -> downloadListener?.onProgressChanged(
                            reciter,
                            chapter,
                            downloadStatus,
                            bytesDownloaded,
                            fileSize,
                            progress,
                            null
                    )

                    DownloadWorkManager.DownloadStatus.FILE_EXISTS,
                    DownloadWorkManager.DownloadStatus.FINISHED_DOWNLOAD -> {
                        downloadListener?.onProgressChanged(
                                reciter,
                                chapter,
                                downloadStatus,
                                bytesDownloaded,
                                fileSize,
                                progress,
                                File(chapterFilePath!!)
                        )

                        mediaStateListener?.onMediaReady(
                                reciter,
                                chapter,
                                File(chapterFilePath!!),
                                AppCompatResources.getDrawable(context, drawableId)
                        )
                    }

                    DownloadWorkManager.DownloadStatus.DOWNLOAD_ERROR -> Unit
                }
            }
    }

    private operator fun <V> Future<V>.getValue(thisRef: Any?, property: KProperty<*>) = get()
}
