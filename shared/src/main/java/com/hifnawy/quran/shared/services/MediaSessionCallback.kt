package com.hifnawy.quran.shared.services

import android.os.Bundle
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.Constants
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.tools.SharedPreferencesManager
import com.hifnawy.quran.shared.tools.Utilities.Companion.getSerializable

class MediaSessionCallback(
    private val mediaService: MediaService, private val sharedPrefsManager: SharedPreferencesManager
) : MediaSessionCompat.Callback() {

    override fun onPlay() {
        Log.d("AndroidAuto", "Playing...")

        sharedPrefsManager.apply {
            if ((lastReciter == null) || (lastChapter == null)) return@apply

            mediaService.playMedia(lastReciter, lastChapter, 0L)
        }
    }

    override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
        extras?.run {
            val reciter = getSerializable<Reciter>(Constants.IntentDataKeys.RECITER.name)
            val chapter = getSerializable<Chapter>(Constants.IntentDataKeys.CHAPTER.name)

            Log.d("AndroidAuto", "Playing ${reciter?.name_ar} / ${chapter?.name_simple}...")
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
        // mediaService.stopSelf()
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