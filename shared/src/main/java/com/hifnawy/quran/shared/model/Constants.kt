package com.hifnawy.quran.shared.model

import android.content.Context
import java.io.File

object Constants {

    const val MAIN_ACTIVITY_INTENT_CATEGORY = "com.hifnawy.quran.ui.activities.MainActivity"
    val NowPlayingClass: Class<*> = Class.forName("com.hifnawy.quran.ui.widgets.NowPlaying")
    val MainActivityClass: Class<*> = Class.forName(MAIN_ACTIVITY_INTENT_CATEGORY)

    enum class ServiceUpdates {
        MEDIA_PLAYBACK_UPDATES,
        ALL_CHAPTERS_DOWNLOAD_PROGRESS,
        ALL_CHAPTERS_DOWNLOAD_SUCCEED,
        ALL_CHAPTERS_DOWNLOAD_FAILED
    }

    enum class MediaServiceActions {
        DOWNLOAD_CHAPTERS,
        CANCEL_DOWNLOADS,
        PLAY_MEDIA,
        PAUSE_MEDIA,
        TOGGLE_MEDIA,
        STOP_MEDIA,
        SKIP_TO_NEXT_MEDIA,
        SKIP_TO_PREVIOUS_MEDIA,
        SEEK_MEDIA
    }

    enum class IntentDataKeys {
        RECITER,
        CHAPTER,
        CHAPTER_INDEX,
        CHAPTER_URL,
        CHAPTER_DURATION,
        CHAPTER_POSITION,
        CHAPTER_DOWNLOAD_STATUS,
        CHAPTER_DOWNLOADED_BYTES,
        CHAPTER_DOWNLOAD_SIZE,
        CHAPTER_DOWNLOAD_PROGRESS,
        CHAPTERS_DOWNLOAD_PROGRESS,
        IS_MEDIA_PLAYING,
        IS_SINGLE_DOWNLOAD
    }

    fun getChapterPath(context: Context, reciter: Reciter, chapter: Chapter): String =
            ("${context.filesDir.absolutePath}/${reciter.name}/${reciter.recitationStyle ?: ""}/" +
             "${chapter.id.toString().padStart(3, '0')}_${chapter.nameSimple}.mp3")
                .replace("`", "'")

    fun getChapterFile(context: Context, reciter: Reciter, chapter: Chapter): File =
            File(getChapterPath(context, reciter, chapter))
}