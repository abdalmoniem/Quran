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
import com.hifnawy.quran.shared.api.QuranAPI.Companion.getChaptersList
import com.hifnawy.quran.shared.api.QuranAPI.Companion.getRecitersList
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.Constants
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.storage.SharedPreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MediaManager(private var context: Context) : LifecycleOwner {
    companion object {

        @SuppressLint("StaticFieldLeak")
        private lateinit var instance: MediaManager

        @Synchronized
        fun getInstance(context: Context): MediaManager {
            return if (this::instance.isInitialized) instance else MediaManager(context.applicationContext)
        }
    }

    fun interface DownloadListener {

        fun onProgressChanged(
                reciter: Reciter,
                chapter: Chapter,
                downloadStatus: DownloadWorkManager.DownloadStatus,
                bytesDownloaded: Long,
                fileSize: Int,
                percentage: Float,
                audioFile: File?
        )
    }

    fun interface MediaStateListener {

        fun onMediaReady(
                reciter: Reciter,
                chapter: Chapter,
                chapterFile: File,
                chapterDrawable: Drawable?
        )
    }

    var downloadListener: DownloadListener? = null
    var mediaStateListener: MediaStateListener? = null
    var reciters: List<Reciter> = mutableListOf()
    var chapters: List<Chapter> = mutableListOf()
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
    private var currentReciter: Reciter? = null
    private var currentChapter: Chapter? = null
    private var sharedPrefsManager: SharedPreferencesManager = SharedPreferencesManager(context)
    private val lifecycleRegistry: LifecycleRegistry by lazy { LifecycleRegistry(this) }
    private val workManager by lazy { WorkManager.getInstance(context) }

    @Suppress("PrivatePropertyName")
    private val TAG = MediaManager::class.simpleName

    init {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    fun stopLifecycle() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        workManager.cancelUniqueWork(context.getString(R.string.downloadWorkManagerUniqueWorkName))
    }

    suspend fun processChapter(reciter: Reciter, chapter: Chapter) {
        if (reciters.isEmpty()) {
            reciters = getRecitersList()
        }

        if (chapters.isEmpty()) {
            chapters = getChaptersList()
        }
        @SuppressLint("DiscouragedApi") val drawableId = context.resources.getIdentifier(
                "chapter_${chapter.id.toString().padStart(3, '0')}", "drawable", context.packageName
        )

        currentReciter = reciter
        currentChapter = chapter
        sharedPrefsManager.lastReciter = reciter
        sharedPrefsManager.lastChapter = chapter

        sharedPrefsManager.getChapterPath(reciter, chapter)?.let { chapterFile ->
            withContext(Dispatchers.Main) {
                mediaStateListener?.onMediaReady(
                        reciter,
                        chapter,
                        File(chapterFile),
                        AppCompatResources.getDrawable(context, drawableId)
                )
            }
        } ?: withContext(Dispatchers.Main) { downloadChapter(reciter, chapter) }
    }

    suspend fun processNextChapter() {
        currentChapter =
            chapters.find { chapter -> chapter.id == (if (currentChapter!!.id == 114) 1 else currentChapter!!.id + 1) }
                ?: sharedPrefsManager.lastChapter

        Log.d(TAG, "Skipping to next Chapter...")

        processChapter(currentReciter!!, currentChapter!!)
    }

    suspend fun processPreviousChapter() {
        currentChapter =
            chapters.find { chapter -> chapter.id == (if (currentChapter!!.id == 1) 114 else currentChapter!!.id - 1) }
                ?: sharedPrefsManager.lastChapter

        Log.d(TAG, "Skipping to previous Chapter...")

        processChapter(currentReciter!!, currentChapter!!)
    }

    fun cancelPendingDownloads() {
        workManager.cancelUniqueWork(context.getString(R.string.downloadWorkManagerUniqueWorkName))
    }

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
            .addTag(context.getString(R.string.downloadWorkManagerUniqueWorkName))
            .build()

        observeWorker(downloadWorkRequest.id, reciter, chapter)

        workManager.enqueueUniqueWork(
                context.getString(R.string.downloadWorkManagerUniqueWorkName),
                ExistingWorkPolicy.REPLACE,
                downloadWorkRequest
        )
    }

    private fun observeWorker(requestID: UUID, reciter: Reciter, chapter: Chapter) {
        val workManager = WorkManager.getInstance(context)
        workManager.getWorkInfoByIdLiveData(requestID)
            .observe(this) { workInfo ->
                if (workInfo == null) return@observe
                if (workInfo.state == WorkInfo.State.FAILED) return@observe
                if (workInfo.state == WorkInfo.State.SUCCEEDED) return@observe

                Log.d(TAG, "${workInfo.state} - ${workInfo.progress}")
                val downloadStatus = DownloadWorkManager.DownloadStatus.valueOf(
                        workInfo.progress.getString(DownloadWorkManager.DownloadWorkerInfo.DOWNLOAD_STATUS.name)
                            ?: return@observe
                )
                val fileSize = workInfo.progress.getInt(
                        DownloadWorkManager.DownloadWorkerInfo.FILE_SIZE.name,
                        -1
                )
                val filePath =
                    workInfo.progress.getString(DownloadWorkManager.DownloadWorkerInfo.FILE_PATH.name)
                val bytesDownloaded = workInfo.progress.getLong(
                        DownloadWorkManager.DownloadWorkerInfo.BYTES_DOWNLOADED.name,
                        -1L
                )
                val progress = workInfo.progress.getFloat(
                        DownloadWorkManager.DownloadWorkerInfo.PROGRESS.name,
                        -1f
                )
                
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
                    DownloadWorkManager.DownloadStatus.FINISHED_DOWNLOAD -> mediaStateListener?.onMediaReady(
                            reciter,
                            chapter,
                            File(filePath!!),
                            AppCompatResources.getDrawable(context, drawableId)
                    )

                    DownloadWorkManager.DownloadStatus.DOWNLOAD_ERROR -> Unit
                }
            }
    }
}
