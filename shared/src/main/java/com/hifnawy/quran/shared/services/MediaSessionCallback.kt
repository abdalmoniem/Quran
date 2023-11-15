package com.hifnawy.quran.shared.services

import android.os.Bundle
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import com.hifnawy.quran.shared.extensions.BundleExt.Companion.getTypedSerializable
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.Constants
import com.hifnawy.quran.shared.model.Reciter

private val TAG = MediaSessionCallback::class.java.simpleName

class MediaSessionCallback(private val mediaService: MediaService) : MediaSessionCompat.Callback() {

    override fun onPlay() {
        Log.d(TAG, "Playing...")

        mediaService.resumeMedia()
    }

    override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
        extras?.run {
            val reciter = getTypedSerializable<Reciter>(Constants.IntentDataKeys.RECITER.name)
            val chapter = getTypedSerializable<Chapter>(Constants.IntentDataKeys.CHAPTER.name)

            Log.d(TAG, "Playing ${reciter?.nameArabic} / ${chapter?.nameSimple}...")
            mediaService.prepareMedia(reciter, chapter)
        }
    }

    override fun onPlayFromSearch(query: String?, extras: Bundle?) {
        Log.d(TAG, "Playing $query from search...")
    }

    override fun onPause() {
        Log.d(TAG, "Pausing...")

        mediaService.pauseMedia()
    }

    override fun onStop() {
        mediaService.stop()
        // mediaService.stopSelf()
    }

    override fun onSkipToQueueItem(queueId: Long) {
        Log.d(TAG, "Skipped to $queueId")
    }

    override fun onSeekTo(position: Long) {
        Log.d(TAG, "Seeking to $position...")

        mediaService.seekChapterToPosition(position)
    }

    override fun onSkipToNext() {
        Log.d(TAG, "Skipping to next audio track...")

        mediaService.skipToNextChapter()
    }

    override fun onSkipToPrevious() {
        Log.d(TAG, "Skipping to previous audio track...")

        mediaService.skipToPreviousChapter()
    }

    override fun onCustomAction(action: String?, extras: Bundle?) {
        Log.d(TAG, "Custom action...")
    }
}