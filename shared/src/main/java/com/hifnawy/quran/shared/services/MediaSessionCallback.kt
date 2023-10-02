package com.hifnawy.quran.shared.services

import android.content.SharedPreferences
import android.os.Bundle
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.tools.Utilities.Companion.getSerializable
import com.hifnawy.quran.shared.tools.Utilities.Companion.getSerializableExtra

class MediaSessionCallback(
    private val mediaService: MediaService, private val sharedPrefs: SharedPreferences
) : MediaSessionCompat.Callback() {

    override fun onPlay() {
        Log.d("AndroidAuto", "Playing...")

        val reciter = sharedPrefs.getSerializableExtra<Reciter>("LAST_RECITER")
        val chapter = sharedPrefs.getSerializableExtra<Chapter>("LAST_CHAPTER")

        if ((chapter != null) and (reciter != null)) {
            mediaService.playMedia(reciter, chapter, 0L)
        }
    }

    override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
        extras?.run {
            val reciter = getSerializable<Reciter>(MediaService.IntentDataKeys.RECITER.name)
            val chapter = getSerializable<Chapter>(MediaService.IntentDataKeys.CHAPTER.name)

            Log.d("AndroidAuto", "Playing ${reciter?.reciter_name} / ${chapter?.name_simple}...")
            mediaService.playMedia(reciter, chapter)
        }
    }

    override fun onPlayFromSearch(query: String?, extras: Bundle?) {
        Log.d("AndroidAuto", "Playing $query from search...")
    }

    override fun onPause() {
        Log.d("AndroidAuto", "Pausing...")

        mediaService.pauseMedia()
    }

    override fun onStop() {
        mediaService.stopSelf()
    }

    override fun onSkipToQueueItem(queueId: Long) {
        Log.d("AndroidAuto", "Skipped to $queueId")
    }

    override fun onSeekTo(position: Long) {
        Log.d("AndroidAuto", "Seeking to $position...")

        mediaService.seekChapterToPosition(position)
    }

    override fun onSkipToNext() {
        Log.d("AndroidAuto", "Skipping to next audio track...")

        mediaService.skipToNextChapter()
    }

    override fun onSkipToPrevious() {
        Log.d("AndroidAuto", "Skipping to previous audio track...")

        mediaService.skipToPreviousChapter()
    }

    override fun onCustomAction(action: String?, extras: Bundle?) {
        Log.d("AndroidAuto", "Custom action...")
    }
}