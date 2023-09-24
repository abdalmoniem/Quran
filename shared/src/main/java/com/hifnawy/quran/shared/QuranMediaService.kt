package com.hifnawy.quran.shared

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media.MediaBrowserServiceCompat
import androidx.media.utils.MediaConstants
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.hifnawy.quran.shared.api.APIRequester.Companion.getChaptersList
import com.hifnawy.quran.shared.api.APIRequester.Companion.getReciterChaptersAudioFiles
import com.hifnawy.quran.shared.api.APIRequester.Companion.getRecitersList
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.ChapterAudioFile
import com.hifnawy.quran.shared.model.Reciter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * This class provides a MediaBrowser through a service. It exposes the media library to a browsing
 * client, through the onGetRoot and onLoadChildren methods. It also creates a MediaSession and
 * exposes it through its MediaSession.Token, which allows the client to create a MediaController
 * that connects to and send control commands to the MediaSession remotely. This is useful for
 * user interfaces that need to interact with your media session, like Android Auto. You can
 * (should) also use the same service from your app's UI, which gives a seamless playback
 * experience to the user.
 *
 *
 * To implement a MediaBrowserService, you need to:
 *
 *  *  Extend [MediaBrowserServiceCompat], implementing the media browsing
 * related methods [MediaBrowserServiceCompat.onGetRoot] and
 * [MediaBrowserServiceCompat.onLoadChildren];
 *
 *  *  In onCreate, start a new [MediaSessionCompat] and notify its parent
 * with the session"s token [MediaBrowserServiceCompat.setSessionToken];
 *
 *  *  Set a callback on the [MediaSessionCompat.setCallback].
 * The callback will receive all the user"s actions, like play, pause, etc;
 *
 *  *  Handle all the actual media playing using any method your app prefers (for example,
 * [android.media.MediaPlayer])
 *
 *  *  Update playbackState, "now playing" metadata and queue, using MediaSession proper methods
 * [MediaSessionCompat.setPlaybackState]
 * [MediaSessionCompat.setMetadata] and
 * [MediaSessionCompat.setQueue])
 *
 *  *  Declare and export the service in AndroidManifest with an intent receiver for the action
 * android.media.browse.MediaBrowserService
 *
 * To make your app compatible with Android Auto, you also need to:
 *
 *  *  Declare a meta-data tag in AndroidManifest.xml linking to a xml resource
 * with a &lt;automotiveApp&gt; root element. For a media app, this must include
 * an &lt;uses name="media"/&gt; element as a child.
 * For example, in AndroidManifest.xml:
 * &lt;meta-data android:name="com.google.android.gms.car.application"
 * android:resource="@xml/automotive_app_desc"/&gt;
 * And in res/values/automotive_app_desc.xml:
 * &lt;automotiveApp&gt;
 * &lt;uses name="media"/&gt;
 * &lt;/automotiveApp&gt;
 *
 */
class QuranMediaService : MediaBrowserServiceCompat() {
    companion object {
        private const val PLAY = 1
        private const val PAUSE = 2
        private const val BUFFERING = 3
        private const val CONNECTING = 4
        private const val STOPPED = 5
    }

    private enum class MediaState {
        RECITER_BROWSE,
        CHAPTER_BROWSE,
        CHAPTER_PLAY
    }

    private lateinit var notificationManager: QuranNotificationManager

    private lateinit var mediaSession: MediaSessionCompat

    private var currentReciterId: Int = -1

    private var currentChapterId: Int = -1

    private var reciters: List<Reciter> = mutableListOf()

    private var chapters: List<Chapter> = mutableListOf()

    private var chaptersAudioFiles: List<ChapterAudioFile> = mutableListOf()

    private var mediaState = MediaState.RECITER_BROWSE

    private var isForegroundService = false

    private lateinit var sharedPrefs: SharedPreferences

    private val audioAttributes = AudioAttributes.Builder()
        .setContentType(C.CONTENT_TYPE_SPEECH)
        .setUsage(C.USAGE_MEDIA)
        .build()

    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(this@QuranMediaService.audioAttributes, true)
            setHandleAudioBecomingNoisy(true)
        }
    }

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            Log.d("ExoPlayer_Audio_Player", "Playing...")

            val reciterId = sharedPrefs.getInt("LAST_RECITER_ID", -1)
            val chapterId = sharedPrefs.getInt("LAST_CHAPTER_ID", -1)

            if ((chapterId != -1) and (reciterId != -1)) {
                currentReciterId = reciterId
                currentChapterId = chapterId

                playMedia()

                setMediaPlaybackState(PLAY)
            }
        }

        override fun onSkipToQueueItem(queueId: Long) {
            Log.d("ExoPlayer_Audio_Player", "Skipped to $queueId")
        }

        override fun onSeekTo(position: Long) {
            Log.d("ExoPlayer_Audio_Player", "Seeking to $position...")
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            super.onPlayFromMediaId(mediaId, extras)

            val chapterId = mediaId?.replace(
                "chapter_".toRegex(),
                ""
            )!!.toInt()

            currentChapterId = chapterId

            Log.d("ExoPlayer_Audio_Player", "Playing chapter: $chapterId...")

            playMedia()
        }

        override fun onPause() {
            super.onPause()
            Log.d("ExoPlayer_Audio_Player", "Pausing...")

            if (exoPlayer.isPlaying) {
                exoPlayer.pause()
            }

            setMediaPlaybackState(PAUSE)
        }

        override fun onStop() {
            super.onStop()

            if (exoPlayer.isPlaying) {
                exoPlayer.stop()
            }

            Log.d("ExoPlayer_Audio_Player", "Stopping...")

            setMediaPlaybackState(STOPPED)
        }

        override fun onSkipToNext() {
            currentChapterId = if (currentChapterId == 114) 1 else currentChapterId + 1

            Log.d("ExoPlayer_Audio_Player", "Skipping to next audio track...")

            playMedia()
        }

        override fun onSkipToPrevious() {
            currentChapterId = if (currentChapterId == 1) 114 else currentChapterId - 1

            Log.d("ExoPlayer_Audio_Player", "Skipping to previous audio track...")

            playMedia()
        }

        override fun onCustomAction(action: String?, extras: Bundle?) {
            Log.d("ExoPlayer_Audio_Player", "Custom action...")
        }

        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            Log.d("ExoPlayer_Audio_Player", "Playing $query from search...")

            // setMediaPlaybackState(PLAY)
        }
    }

    private fun playMedia() {
        sharedPrefs.edit().putInt("LAST_RECITER_ID", currentReciterId).apply()
        sharedPrefs.edit().putInt("LAST_CHAPTER_ID", currentChapterId).apply()

        CoroutineScope(Dispatchers.IO).launch {
            if (reciters.isEmpty() or chapters.isEmpty() or chaptersAudioFiles.isEmpty()) {
                reciters = getRecitersList()
                chapters = getChaptersList()
                chaptersAudioFiles = getReciterChaptersAudioFiles(currentReciterId)
            }

            withContext(Dispatchers.Main) {
                val reciter = reciters.single { reciter -> reciter.id == currentReciterId }
                val chapter = chapters.single { chapter -> chapter.id == currentChapterId }
                val chapterAudioFile =
                    chaptersAudioFiles.single { chapterAudioFile -> chapterAudioFile.chapter_id == currentChapterId }

                if (exoPlayer.isPlaying) {
                    exoPlayer.stop()
                }

                setMediaPlaybackState(BUFFERING)

                mediaSession.setMetadata(
                    MediaMetadataCompat.Builder()
                        .putText(MediaMetadataCompat.METADATA_KEY_TITLE, chapter.name_arabic)
                        .putText(
                            MediaMetadataCompat.METADATA_KEY_ARTIST,
                            (if (reciter.translated_name != null) reciter.translated_name.name else reciter.reciter_name) +
                                    if (reciter.style != null) " (${reciter.style.style})" else ""
                        )
                        .putText(MediaMetadataCompat.METADATA_KEY_GENRE, "Quran")
                        // .putText(
                        //     MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI,
                        //     "http://70.38.6.72/~vivafe/web/wp-content/uploads/2016/08/01.jpg"
                        // )
                        .build()
                )

                setMediaPlaybackState(PLAY)

                val mediaItem =
                    com.google.android.exoplayer2.MediaItem.fromUri(chapterAudioFile.audio_url.toUri())
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
                exoPlayer.play()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        mediaSession = MediaSessionCompat(this, "QuranMediaService")

        sessionToken = mediaSession.sessionToken

        sharedPrefs = getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)

        // notificationManager = QuranNotificationManager(
        //     this,
        //     mediaSession.sessionToken,
        //     PlayerNotificationListener()
        // )
        //
        // notificationManager.showNotificationForPlayer(exoPlayer)

        mediaSession.setCallback(callback)

        mediaSession.isActive = true
    }

    override fun onDestroy() {
        mediaSession.release()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot(MEDIA_ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        val mediaItems: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()

        CoroutineScope(Dispatchers.IO).launch {
            // Check whether this is the root menu:
            if (MEDIA_ROOT_ID == parentId) {

                // Build the MediaItem objects for the top level
                // and put them in the mediaItems list.
                reciters = getRecitersList()
                mediaItems.add(
                    createBrowsableMediaItem(
                        "quran_reciters",
                        applicationContext.getString(R.string.quran)
                    )
                )
            } else {
                mediaState = if (parentId.startsWith("reciter_")) {
                    MediaState.CHAPTER_BROWSE
                } else if (parentId.startsWith("chapter_")) {
                    MediaState.CHAPTER_PLAY
                } else {
                    MediaState.RECITER_BROWSE
                }

                // Examine the passed parentMediaId to see which submenu we're at
                // and put the children of that menu in the mediaItems list.
                when (mediaState) {
                    MediaState.RECITER_BROWSE -> {
                        Log.d(javaClass.canonicalName, parentId)
                        reciters.forEach { reciter ->
                            mediaItems.add(
                                createBrowsableMediaItem(
                                    "reciter_${reciter.id}",
                                    (if (reciter.translated_name != null) reciter.translated_name.name else reciter.reciter_name) +
                                            if (reciter.style != null) " (${reciter.style.style})" else ""
                                )
                            )
                        }
                    }

                    MediaState.CHAPTER_BROWSE -> {
                        val reciterId = parentId.replace("reciter_", "").toInt()
                        currentReciterId = reciterId

                        Log.d(javaClass.canonicalName, "currentReciterId = $currentReciterId")
                        chapters = getChaptersList()
                        chaptersAudioFiles =
                            getReciterChaptersAudioFiles(reciterId)
                        Log.d(javaClass.canonicalName, parentId)
                        chapters.forEach { chapter ->
                            mediaItems.add(
                                createMediaItem(
                                    "chapter_${chapter.id}",
                                    chapter,
                                    chaptersAudioFiles.single { chapterAudioFile ->
                                        chapterAudioFile.chapter_id == chapter.id
                                    }
                                )
                            )
                        }
                    }

                    MediaState.CHAPTER_PLAY -> {}
                }
            }
            result.sendResult(mediaItems)
        }

        result.detach()
    }

    private fun createBrowsableMediaItem(
        mediaId: String,
        folderName: String,
        // iconUri: Uri
    ): MediaBrowserCompat.MediaItem {
        val mediaDescriptionBuilder = MediaDescriptionCompat.Builder()
        mediaDescriptionBuilder.setMediaId(mediaId)
        mediaDescriptionBuilder.setTitle(folderName)
        // mediaDescriptionBuilder.setIconUri(iconUri)
        val extras = Bundle()
        extras.putInt(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM,
            MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM
        )
        extras.putInt(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        )
        extras.putInt(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        )
        mediaDescriptionBuilder.setExtras(extras)
        return MediaBrowserCompat.MediaItem(
            mediaDescriptionBuilder.build(), MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        )
    }

    private fun createMediaItem(
        mediaId: String,
        chapter: Chapter,
        chapterAudioFile: ChapterAudioFile?
        // iconUri: Uri
    ): MediaBrowserCompat.MediaItem {
        // val mediaDescriptionBuilder = MediaDescription.Builder()
        // mediaDescriptionBuilder.setMediaId(mediaId)
        // mediaDescriptionBuilder.setTitle(chapter.name_arabic)
        //
        // if (chapterAudioFile != null) {
        //     Log.d(javaClass.canonicalName, Uri.parse(chapterAudioFile.audio_url).toString())
        //     mediaDescriptionBuilder.setMediaUri(Uri.parse(chapterAudioFile.audio_url))
        // }
        // mediaDescriptionBuilder.setIconUri(iconUri)
        // val extras = Bundle()
        // extras.putInt(
        //     MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM,
        //     MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM
        // )
        // extras.putInt(
        //     MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
        //     MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        // )
        // extras.putInt(
        //     MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
        //     MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        // )
        // mediaDescriptionBuilder.setExtras(extras)
        // return MediaBrowser.MediaItem(
        //     mediaDescriptionBuilder.build(), MediaBrowser.MediaItem.FLAG_PLAYABLE
        // )

        return MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder().setMediaId(mediaId)
                .setTitle(chapter.name_arabic)
                .setMediaUri(Uri.parse(chapterAudioFile?.audio_url))
                .build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        )
    }

    private fun setMediaPlaybackState(state: Int) {
        var playbackState: PlaybackStateCompat? = null
        when (state) {
            PLAY -> playbackState = PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1f)
                .build()

            PAUSE -> playbackState = PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE)
                .setState(PlaybackStateCompat.STATE_PAUSED, 0, 1f)
                .build()

            BUFFERING -> playbackState = PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_STOP)
                .setState(PlaybackStateCompat.STATE_BUFFERING, 0, 1f)
                .build()

            CONNECTING -> playbackState = PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_STOP)
                .setState(PlaybackStateCompat.STATE_CONNECTING, 0, 1f)
                .build()

            STOPPED -> playbackState = PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID)
                .setState(PlaybackStateCompat.STATE_STOPPED, 0, 1f)
                .build()
        }

        mediaSession.setPlaybackState(playbackState)
    }

    private inner class PlayerNotificationListener :
        PlayerNotificationManager.NotificationListener {
        override fun onNotificationPosted(
            notificationId: Int,
            notification: Notification,
            ongoing: Boolean
        ) {
            if (ongoing && !isForegroundService) {
                ContextCompat.startForegroundService(
                    applicationContext,
                    Intent(applicationContext, this@QuranMediaService.javaClass)
                )

                startForeground(notificationId, notification)
                isForegroundService = true
            }
        }

        override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
            stopForeground(true)
            isForegroundService = false
            stopSelf()
        }
    }
}

private const val MEDIA_ROOT_ID = "ROOT"