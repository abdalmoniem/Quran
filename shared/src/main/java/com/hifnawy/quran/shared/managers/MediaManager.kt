package com.hifnawy.quran.shared.managers

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.appcompat.content.res.AppCompatResources
import com.hifnawy.quran.shared.api.QuranAPI.Companion.getChapterAudioFile
import com.hifnawy.quran.shared.api.QuranAPI.Companion.getChaptersList
import com.hifnawy.quran.shared.api.QuranAPI.Companion.getRecitersList
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.storage.SharedPreferencesManager
import com.hifnawy.quran.shared.tools.Utilities
import com.hifnawy.quran.shared.tools.Utilities.Companion.DownloadStatus.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

class MediaManager(private var context: Context) {
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
            downloadStatus: Utilities.Companion.DownloadStatus,
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

    private var currentReciter: Reciter? = null
    private var currentChapter: Chapter? = null
    private var ioCoroutineScope = CoroutineScope(Dispatchers.IO)
    private var sharedPrefsManager: SharedPreferencesManager = SharedPreferencesManager(context)

    fun processChapter(reciter: Reciter, chapter: Chapter) {
        ioCoroutineScope.launch {
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
            } ?: Utilities.downloadFile(
                context, URL(getChapterAudioFile(reciter.id, chapter.id)?.audio_url), reciter, chapter
            ) { downloadStatus, bytesDownloaded, fileSize, percentage, audioFile ->
                withContext(Dispatchers.Main) {
                    when (downloadStatus) {
                        STARTING_DOWNLOAD -> Unit
                        DOWNLOADING -> Unit
                        FINISHED_DOWNLOAD -> mediaStateListener?.onMediaReady(
                            reciter,
                            chapter,
                            audioFile!!,
                            AppCompatResources.getDrawable(context, drawableId)
                        )

                        DOWNLOAD_ERROR -> Unit
                    }
                    downloadListener?.onProgressChanged(
                        downloadStatus, bytesDownloaded, fileSize, percentage, audioFile
                    )
                }
            }
        }
    }

    fun processNextChapter() {
        currentChapter =
            chapters.single { chapter -> chapter.id == (if (currentChapter!!.id == 114) 1 else currentChapter!!.id + 1) }

        Log.d(this::class.simpleName, "Skipping to next Chapter...")

        processChapter(currentReciter!!, currentChapter!!)
    }

    fun processPreviousChapter() {
        currentChapter =
            chapters.single { chapter -> chapter.id == (if (currentChapter!!.id == 114) 1 else currentChapter!!.id - 1) }

        Log.d(this::class.simpleName, "Skipping to previous Chapter...")

        processChapter(currentReciter!!, currentChapter!!)
    }
}