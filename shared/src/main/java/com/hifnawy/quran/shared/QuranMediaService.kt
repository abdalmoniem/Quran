package com.hifnawy.quran.shared

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.media.MediaBrowserServiceCompat
import androidx.media.utils.MediaConstants
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import com.hifnawy.quran.shared.api.APIRequester.Companion.getChapter
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
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths


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
        private const val PLAYING = 1
        private const val PAUSE = 2
        private const val SKIPPING_TO_NEXT = 3
        private const val SKIPPING_TO_PREVIOUS = 4
        private const val BUFFERING = 5
        private const val CONNECTING = 6
        private const val STOPPED = 7
    }

    private enum class MediaState {
        RECITER_BROWSE,
        CHAPTER_BROWSE,
        CHAPTER_PLAY
    }

    private lateinit var mediaSession: MediaSessionCompat

    private lateinit var exoPlayerPositionListener: Handler

    private var currentReciterId: Int = -1

    private var currentChapterId: Int = -1

    private var reciters: List<Reciter> = mutableListOf()

    private var chapters: List<Chapter> = mutableListOf()

    private var chaptersAudioFiles: List<ChapterAudioFile> = mutableListOf()

    private var mediaState = MediaState.RECITER_BROWSE

    // private var isForegroundService = false

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

    private val notificationManager: NotificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onCreate() {
        super.onCreate()

        exoPlayerPositionListener = Handler(Looper.getMainLooper())

        Log.d(javaClass.canonicalName, "${javaClass.canonicalName} started!!!")

        mediaSession = MediaSessionCompat(this, "QuranMediaService")

        sessionToken = mediaSession.sessionToken

        sharedPrefs = getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)

        with(mediaSession) {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    Log.d("ExoPlayer_Audio_Player", "Playing...")

                    val reciterId = sharedPrefs.getInt("LAST_RECITER_ID", -1)
                    val chapterId = sharedPrefs.getInt("LAST_CHAPTER_ID", -1)

                    if ((chapterId != -1) and (reciterId != -1)) {
                        currentReciterId = reciterId
                        currentChapterId = chapterId

                        if (exoPlayer.mediaItemCount == 0) {
                            setMediaPlaybackState(BUFFERING)
                            playMedia()
                        } else {
                            setMediaPlaybackState(PLAYING)
                            exoPlayer.play()
                        }
                    }
                }

                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    super.onPlayFromMediaId(mediaId, extras)

                    val chapterId = mediaId?.replace(
                        "chapter_".toRegex(),
                        ""
                    )!!.toInt()

                    currentChapterId = chapterId

                    Log.d("ExoPlayer_Audio_Player", "Playing chapter: $currentChapterId...")

                    if (exoPlayer.isPlaying) {
                        exoPlayer.stop()
                    }

                    setMediaPlaybackState(BUFFERING)
                    playMedia()
                }

                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    Log.d("ExoPlayer_Audio_Player", "Playing $query from search...")
                }

                override fun onPause() {
                    super.onPause()
                    Log.d("ExoPlayer_Audio_Player", "Pausing...")

                    setMediaPlaybackState(PAUSE)
                    if (exoPlayer.isPlaying) {
                        exoPlayer.pause()
                    }
                }

                override fun onStop() {
                    super.onStop()

                    setMediaPlaybackState(STOPPED)
                    if (exoPlayer.isPlaying) {
                        exoPlayer.stop()
                    }

                    Log.d("ExoPlayer_Audio_Player", "Stopping...")
                }

                override fun onSkipToQueueItem(queueId: Long) {
                    Log.d("ExoPlayer_Audio_Player", "Skipped to $queueId")
                }

                override fun onSeekTo(position: Long) {
                    Log.d("ExoPlayer_Audio_Player", "Seeking to $position...")

                    if (exoPlayer.isPlaying) {
                        exoPlayer.seekTo(position)
                        setMediaPlaybackState(PLAYING)
                    }
                }

                override fun onSkipToNext() {
                    currentChapterId = if (currentChapterId == 114) 1 else currentChapterId + 1

                    Log.d("ExoPlayer_Audio_Player", "Skipping to next audio track...")

                    if (exoPlayer.isPlaying) {
                        exoPlayer.stop()
                    }

                    setMediaPlaybackState(SKIPPING_TO_NEXT)
                    playMedia()
                }

                override fun onSkipToPrevious() {
                    currentChapterId = if (currentChapterId == 1) 114 else currentChapterId - 1

                    Log.d("ExoPlayer_Audio_Player", "Skipping to previous audio track...")

                    if (exoPlayer.isPlaying) {
                        exoPlayer.stop()
                    }

                    setMediaPlaybackState(SKIPPING_TO_PREVIOUS)
                    playMedia()
                }

                override fun onCustomAction(action: String?, extras: Bundle?) {
                    Log.d("ExoPlayer_Audio_Player", "Custom action...")
                }
            })

            isActive = true
        }

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_IDLE -> {}
                    Player.STATE_BUFFERING -> {}
                    Player.STATE_READY -> {
                        setMediaPlaybackState(PLAYING)

                        exoPlayerPositionListener.removeCallbacksAndMessages(null)
                        exoPlayerPositionListener.postDelayed(object : Runnable {
                            override fun run() {
                                sendBroadcast(Intent("your_action_name").apply {
                                    putExtra("duration", exoPlayer.duration)
                                    putExtra("currentPosition", exoPlayer.currentPosition)
                                })

                                exoPlayerPositionListener.postDelayed(this, 1000)
                            }
                        }, 1000)
                    }
                    Player.STATE_ENDED -> {
                        // exoPlayer.stop()
                        setMediaPlaybackState(BUFFERING)
                        currentChapterId = if (currentChapterId == 114) 1 else currentChapterId + 1
                        playMedia()
                    }
                }
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val bundle = intent.extras

            if (bundle != null) {
                val reciter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getSerializableExtra("RECITER_ID", Reciter::class.java)
                } else {
                    bundle.getSerializable("RECITER_ID") as Reciter
                }

                val chapter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getSerializableExtra("CHAPTER_ID", Chapter::class.java)
                } else {
                    bundle.getSerializable("CHAPTER_ID") as Chapter
                }

                if ((reciter != null) && (chapter != null)) {
                    currentReciterId = reciter.id
                    currentChapterId = chapter.id

                    sharedPrefs.edit().putInt("LAST_RECITER_ID", currentReciterId).apply()
                    sharedPrefs.edit().putInt("LAST_CHAPTER_ID", currentChapterId).apply()

                    val drawableId = resources.getIdentifier(
                        "chapter_${chapter.id.toString().padStart(3, '0')}",
                        "drawable",
                        packageName
                    )

                    val notification = NotificationCompat.Builder(
                        this@QuranMediaService,
                        getString(R.string.quran_recitation_notification_name)
                    )
                        // Show controls on lock screen even when user hides sensitive content.
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setSmallIcon(R.drawable.quran_icon_monochrome_64)
                        .setSilent(true)
                        // Apply the media style template
                        .setStyle(
                            androidx.media.app.NotificationCompat.MediaStyle()
                                // .setShowActionsInCompactView(1 /* #1: pause button \*/)
                                .setMediaSession(mediaSession.sessionToken)
                        )
                        .setContentTitle(chapter.name_arabic)
                        .setContentText(if (reciter.translated_name != null) reciter.translated_name.name else reciter.reciter_name)
                        .setLargeIcon(
                            BitmapFactory.decodeResource(
                                this@QuranMediaService.resources,
                                drawableId
                            )
                        ).build()

                    if (exoPlayer.isPlaying) {
                        exoPlayer.stop()
                    }

                    startForeground(R.integer.quran_chapter_notification_channel_id, notification)
                    setMediaPlaybackState(BUFFERING)
                    playMedia()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.w(
            "ExoPlayer_Audio_Player",
            "${::QuranMediaService.javaClass.name} service is being destroyed!"
        )

        if (!exoPlayer.isPlaying) {
            Log.w(
                "ExoPlayer_Audio_Player",
                "${::QuranMediaService.javaClass.name} service is being destroyed! releasing ${::exoPlayer.name}..."
            )
            exoPlayer.release()
        }

        if (!mediaSession.isActive) {
            Log.w(
                "ExoPlayer_Audio_Player",
                "${::QuranMediaService.javaClass.name} service is being destroyed! releasing ${::mediaSession.name}..."
            )
            mediaSession.release()
        }
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
                                    chaptersAudioFiles.first { chapterAudioFile ->
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

        // val drawableId = resources.getIdentifier(
        //     "chapter_${chapter.id.toString().padStart(3, '0')}",
        //     "drawable",
        //     packageName
        // )
        // val iconUri = Uri.parse("android.resource://$packageName/$drawableId")
        mediaDescriptionBuilder.setIconUri(Uri.parse("android.resource://$packageName/${R.drawable.reciter_name}"))
        val extras = Bundle()
        extras.putInt(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM,
            MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        )
        extras.putInt(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
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

    @SuppressLint("DiscouragedApi")
    private fun createMediaItem(
        mediaId: String,
        chapter: Chapter,
        chapterAudioFile: ChapterAudioFile?
        // iconUri: Uri
    ): MediaBrowserCompat.MediaItem {
        val mediaDescriptionBuilder = MediaDescriptionCompat.Builder()
        mediaDescriptionBuilder.setMediaId(mediaId)
        mediaDescriptionBuilder.setTitle(chapter.name_arabic)

        if (chapterAudioFile != null) {
            Log.d(javaClass.canonicalName, Uri.parse(chapterAudioFile.audio_url).toString())
            mediaDescriptionBuilder.setMediaUri(Uri.parse(chapterAudioFile.audio_url))
        }

        val drawableId = resources.getIdentifier(
            "chapter_${chapter.id.toString().padStart(3, '0')}",
            "drawable",
            packageName
        )
        val iconUri = Uri.parse("android.resource://$packageName/$drawableId")

        mediaDescriptionBuilder.setIconUri(iconUri)

        val extras = Bundle()
        extras.putInt(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM,
            MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        )
        extras.putInt(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        )
        extras.putInt(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        )
        mediaDescriptionBuilder.setExtras(extras)
        return MediaBrowserCompat.MediaItem(
            mediaDescriptionBuilder.build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        )
    }

    @SuppressLint("DiscouragedApi")
    private fun playMedia() {
        sharedPrefs.edit().putInt("LAST_RECITER_ID", currentReciterId).apply()
        sharedPrefs.edit().putInt("LAST_CHAPTER_ID", currentChapterId).apply()

        CoroutineScope(Dispatchers.IO).launch {
            if (reciters.isEmpty() or chapters.isEmpty() or chaptersAudioFiles.isEmpty()) {
                reciters = getRecitersList()
                chapters = getChaptersList()
                chaptersAudioFiles = getReciterChaptersAudioFiles(currentReciterId)
            }

            val reciter = reciters.single { reciter -> reciter.id == currentReciterId }
            val chapter = chapters.single { chapter -> chapter.id == currentChapterId }
            val chapterAudioFile = getChapter(currentReciterId, currentChapterId)
            // chaptersAudioFiles.single { chapterAudioFile -> chapterAudioFile.chapter_id == currentChapterId }

            val drawableId = resources.getIdentifier(
                "chapter_${chapter.id.toString().padStart(3, '0')}",
                "drawable",
                packageName
            )
            val uri = Uri.parse("android.resource://$packageName/$drawableId")

            mediaSession.setMetadata(
                MediaMetadataCompat.Builder()
                    .putText(
                        MediaMetadataCompat.METADATA_KEY_TITLE,
                        getString(R.string.loading_chapter, chapter.name_arabic)
                    )
                    .putText(
                        MediaMetadataCompat.METADATA_KEY_ARTIST,
                        (if (reciter.translated_name != null) reciter.translated_name.name else reciter.reciter_name) +
                                if (reciter.style != null) " (${reciter.style.style})" else ""
                    )
                    .putText(
                        MediaMetadataCompat.METADATA_KEY_GENRE,
                        this@QuranMediaService.getString(R.string.quran)
                    )
                    .putText(
                        MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI,
                        uri.toString()
                    ).build()
            )


            val file: File = downloadFile(URL(chapterAudioFile?.audio_url), reciter, chapter)

            val mmr = MediaMetadataRetriever().apply {
                setDataSource(this@QuranMediaService, file.toUri())
            }

            val durationStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr!!.toInt()

            Log.d(
                "ExoPlayer_Audio_Player",
                "reciter_id: ${reciter.id}\n" +
                        "chapter_id: ${chapter.id}\n" +
                        "file_size: ${chapterAudioFile?.file_size}\n" +
                        "file: ${file}\n" +
                        "Duration in ms: $durationMs"
            )

            withContext(Dispatchers.Main) {
                if (exoPlayer.isPlaying) {
                    exoPlayer.stop()
                }

                // Collections.rotate(
                //     chaptersAudioFiles,
                //     (chaptersAudioFiles.indexOf(chapterAudioFile)) * -1
                // )

                // val mediaItems: ArrayList<com.google.android.exoplayer2.MediaItem> = ArrayList()
                //
                // chaptersAudioFiles.forEach {
                //     Log.d("ExoPlayer_Audio_Player", "chapterId: ${it.chapter_id}")
                //     mediaItems.add(
                //         com.google.android.exoplayer2.MediaItem.fromUri(
                //             it.audio_url.toUri()
                //         )
                //     )
                // }
                // exoPlayer.setMediaItems(mediaItems.toList())

                val mediaItem =
                    com.google.android.exoplayer2.MediaItem.fromUri(file.toUri())
                exoPlayer.setMediaItem(mediaItem)

                exoPlayer.prepare()

                mediaSession.setMetadata(
                    MediaMetadataCompat.Builder()
                        .putText(MediaMetadataCompat.METADATA_KEY_TITLE, chapter.name_arabic)
                        .putText(
                            MediaMetadataCompat.METADATA_KEY_ARTIST,
                            (if (reciter.translated_name != null) reciter.translated_name.name else reciter.reciter_name) +
                                    if (reciter.style != null) " (${reciter.style.style})" else ""
                        )
                        .putText(
                            MediaMetadataCompat.METADATA_KEY_GENRE,
                            this@QuranMediaService.getString(R.string.quran)
                        )
                        .putLong(
                            MediaMetadataCompat.METADATA_KEY_DURATION,
                            durationMs.toLong()
                        )
                        .putText(
                            MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI,
                            uri.toString()
                        )
                        .build()
                )

                val notification = NotificationCompat.Builder(
                    this@QuranMediaService,
                    getString(R.string.quran_recitation_notification_name)
                )
                    // Show controls on lock screen even when user hides sensitive content.
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setSmallIcon(R.drawable.quran_icon_monochrome_64)
                    .setSilent(true)
                    // Apply the media style template
                    .setStyle(
                        androidx.media.app.NotificationCompat.MediaStyle()
                            // .setShowActionsInCompactView(1 /* #1: pause button \*/)
                            .setMediaSession(mediaSession.sessionToken)
                    )
                    .setContentTitle(chapter.name_arabic)
                    .setContentText(if (reciter.translated_name != null) reciter.translated_name.name else reciter.reciter_name)
                    .setLargeIcon(
                        BitmapFactory.decodeResource(
                            this@QuranMediaService.resources,
                            drawableId
                        )
                    )
                    .build()

                val name = getString(R.string.quran_recitation_notification_name)
                val descriptionText = chapter.name_arabic
                val importance = NotificationManager.IMPORTANCE_HIGH
                val channel = NotificationChannel(
                    getString(R.string.quran_recitation_notification_name),
                    name,
                    importance
                ).apply { description = descriptionText }

                // Register the channel with the system
                notificationManager.createNotificationChannel(channel)
                notificationManager.notify(R.integer.quran_chapter_notification_channel_id, notification)

                exoPlayer.playWhenReady = true
                // exoPlayer.play()
            }
        }
    }

    private fun setMediaPlaybackState(state: Int) {
        var playbackState: PlaybackStateCompat? = null
        when (state) {
            PLAYING -> playbackState = PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_REWIND or
                            PlaybackStateCompat.ACTION_FAST_FORWARD or
                            PlaybackStateCompat.ACTION_SET_REPEAT_MODE or
                            PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(PlaybackStateCompat.STATE_PLAYING, exoPlayer.currentPosition, 1f)
                .build()

            PAUSE -> playbackState = PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY)
                .setState(PlaybackStateCompat.STATE_PAUSED, exoPlayer.currentPosition, 1f)
                .build()

            SKIPPING_TO_NEXT -> playbackState = PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_STOP)
                .setState(PlaybackStateCompat.STATE_SKIPPING_TO_NEXT, 0, 1f)
                .build()

            SKIPPING_TO_PREVIOUS -> playbackState = PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_STOP)
                .setState(PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS, 0, 1f)
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

    private fun downloadFile(url: URL, reciter: Reciter, chapter: Chapter): File {
        val reciterDirectory = "${this@QuranMediaService.filesDir.absolutePath}/${reciter.reciter_name}"
        val chapterFileName =
            "$reciterDirectory/${chapter.id.toString().padStart(3, '0')}_${chapter.name_simple}.mp3"
        val reciterDirectoryFile = File(reciterDirectory)
        val chapterFile = File(chapterFileName)

        if (!reciterDirectoryFile.exists()) {
            reciterDirectoryFile.mkdir()
        }

        if (!chapterFile.exists()) {
            // download the file if it doesn't exist
            url.openStream().use { Files.copy(it, Paths.get(chapterFileName)) }
        }

        return chapterFile

    }
}


private const val MEDIA_ROOT_ID = "ROOT"