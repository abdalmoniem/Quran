package com.hifnawy.quran.shared.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.media.MediaBrowserServiceCompat
import androidx.media.utils.MediaConstants
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.hifnawy.quran.shared.R
import com.hifnawy.quran.shared.api.APIRequester.Companion.getChapterAudioFile
import com.hifnawy.quran.shared.api.APIRequester.Companion.getChaptersList
import com.hifnawy.quran.shared.api.APIRequester.Companion.getReciterChaptersAudioFiles
import com.hifnawy.quran.shared.api.APIRequester.Companion.getRecitersList
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.ChapterAudioFile
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.tools.Utilities.Companion.downloadFile
import com.hifnawy.quran.shared.tools.Utilities.Companion.getSerializableExtra
import com.hifnawy.quran.shared.tools.Utilities.Companion.putSerializableExtra
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale


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

class MediaService : MediaBrowserServiceCompat() {
    enum class Actions {
        PLAY_MEDIA, PAUSE_MEDIA, STOP_MEDIA, SKIP_TO_NEXT_MEDIA, SKIP_TO_PREVIOUS_MEDIA, SEEK_MEDIA
    }

    enum class IntentDataKeys {
        RECITER, CHAPTER, CHAPTER_DURATION, CHAPTER_POSITION
    }

    inner class ServiceBinder : Binder() {
        val instance: MediaService
            get() = this@MediaService
    }

    companion object {
        var isRunning = false
        var isMediaPlaying = false
        var startDownload = false

        var downloadComplete = false

        private var currentReciterId: Int = -1
        private var currentChapterId: Int = -1
        private var currentChapterPosition: Long = -1L

        private const val PLAYING = 1
        private const val PAUSED = 2
        private const val SKIPPING_TO_NEXT = 3
        private const val SKIPPING_TO_PREVIOUS = 4
        private const val BUFFERING = 5
        private const val CONNECTING = 6
        private const val STOPPED = 7
        private const val MEDIA_ROOT_ID = "ROOT"
    }

    private enum class MediaState {
        RECITER_BROWSE, CHAPTER_BROWSE, CHAPTER_PLAY
    }

    private lateinit var mediaSession: MediaSessionCompat

    private lateinit var exoPlayerPositionListener: Handler

    private lateinit var sharedPrefs: SharedPreferences

    private lateinit var serviceForegroundNotification: Notification

    private lateinit var serviceForegroundNotificationChannel: NotificationChannel

    private var reciters: List<Reciter> = mutableListOf()

    private var chapters: List<Chapter> = mutableListOf()

    private var chaptersAudioFiles: List<ChapterAudioFile> = mutableListOf()

    private var mediaState = MediaState.RECITER_BROWSE

    private val serviceBinder = ServiceBinder()

    private val audioAttributes =
        AudioAttributes.Builder().setContentType(C.CONTENT_TYPE_SPEECH).setUsage(C.USAGE_MEDIA).build()

    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(this@MediaService.audioAttributes, true)
            setHandleAudioBecomingNoisy(true)
        }
    }

    private val notificationManager: NotificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onCreate() {
        super.onCreate()

        isRunning = true

        startDownload = true

        exoPlayerPositionListener = Handler(Looper.getMainLooper())

        Log.d(
            ::MediaService.javaClass.name,
            "${::MediaService.javaClass.name} service started!!!"
        )

        mediaSession = MediaSessionCompat(this, "QuranMediaService")

        sessionToken = mediaSession.sessionToken

        sharedPrefs = getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)

        currentChapterPosition = sharedPrefs.getLong("LAST_CHAPTER_POSITION", -1L)

        with(mediaSession) {
            setCallback(MediaSessionCallback(this@MediaService, sharedPrefs))

            isActive = true
        }

        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                setMediaPlaybackState(if (isPlaying) PLAYING else PAUSED)
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_IDLE -> {}
                    Player.STATE_BUFFERING -> {}
                    Player.STATE_READY -> setMediaPlaybackState(PLAYING)

                    Player.STATE_ENDED -> {
                        // exoPlayer.stop()
                        setMediaPlaybackState(BUFFERING)
                        currentChapterId = if (currentChapterId == 114) 1 else currentChapterId + 1
                        exoPlayerPositionListener.removeCallbacksAndMessages(null)
                        currentChapterPosition = -1L
                        processAndPlayMedia()
                    }
                }
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder {
        return serviceBinder
    }

    @SuppressLint("DiscouragedApi")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startDownload = true

        intent?.let { serviceIntent ->
            serviceIntent.action?.let { action ->
                with(serviceIntent) {
                    when (action) {
                        Actions.PLAY_MEDIA.name -> {
                            val reciter = getSerializableExtra<Reciter>(IntentDataKeys.RECITER.name)
                            val chapter = getSerializableExtra<Chapter>(IntentDataKeys.CHAPTER.name)
                            val chapterPosition = getLongExtra(IntentDataKeys.CHAPTER_POSITION.name, -1L)

                            playMedia(reciter, chapter, chapterPosition)
                        }

                        Actions.PAUSE_MEDIA.name -> pauseMedia()

                        Actions.STOP_MEDIA.name -> stopSelf()

                        Actions.SKIP_TO_NEXT_MEDIA.name -> skipToNextChapter()

                        Actions.SKIP_TO_PREVIOUS_MEDIA.name -> skipToPreviousChapter()

                        Actions.SEEK_MEDIA.name -> {
                            val chapterPosition = getLongExtra(IntentDataKeys.CHAPTER_POSITION.name, -1L)

                            seekChapterToPosition(chapterPosition)
                        }
                    }
                }
            }
        }

        return START_STICKY
    }

    override fun onGetRoot(
        clientPackageName: String, clientUid: Int, rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot(MEDIA_ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        val mediaItems: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()

        CoroutineScope(Dispatchers.IO).launch {
            // Check whether this is the root menu:
            if (parentId == MEDIA_ROOT_ID) {

                // Build the MediaItem objects for the top level
                // and put them in the mediaItems list.
                reciters = getRecitersList()
                mediaItems.add(
                    createBrowsableMediaItem(
                        "quran_reciters", applicationContext.getString(R.string.quran)
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
                                    (if (reciter.translated_name != null) reciter.translated_name.name else reciter.reciter_name) + if (reciter.style != null) " (${reciter.style.style})" else ""
                                )
                            )
                        }
                    }

                    MediaState.CHAPTER_BROWSE -> {
                        val reciterId = parentId.replace("reciter_", "").toInt()
                        val reciter = reciters.single { reciter -> reciter.id == reciterId }
                        currentReciterId = reciterId

                        Log.d(javaClass.canonicalName, "currentReciterId = $currentReciterId")
                        chapters = getChaptersList()
                        chaptersAudioFiles = getReciterChaptersAudioFiles(reciterId)
                        chapters.forEach { chapter ->
                            mediaItems.add(
                                createMediaItem("chapter_${chapter.id}",
                                    reciter,
                                    chapter,
                                    chaptersAudioFiles.first { chapterAudioFile ->
                                        chapterAudioFile.chapter_id == chapter.id
                                    })
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
    ): MediaBrowserCompat.MediaItem {
        val mediaDescriptionBuilder = MediaDescriptionCompat.Builder()
        mediaDescriptionBuilder.setMediaId(mediaId)
        mediaDescriptionBuilder.setTitle(folderName)

        mediaDescriptionBuilder.setIconBitmap(
            (AppCompatResources.getDrawable(
                this@MediaService, R.drawable.reciter_name
            ) as BitmapDrawable).bitmap
        )
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
        mediaId: String, reciter: Reciter, chapter: Chapter, chapterAudioFile: ChapterAudioFile?
    ): MediaBrowserCompat.MediaItem {
        val mediaDescriptionBuilder = MediaDescriptionCompat.Builder()
        mediaDescriptionBuilder.setMediaId(mediaId)
        mediaDescriptionBuilder.setTitle(chapter.name_arabic)

        if (chapterAudioFile != null) {
            Log.d(javaClass.canonicalName, Uri.parse(chapterAudioFile.audio_url).toString())
            mediaDescriptionBuilder.setMediaUri(Uri.parse(chapterAudioFile.audio_url))
        }

        val drawableId = resources.getIdentifier(
            "chapter_${chapter.id.toString().padStart(3, '0')}", "drawable", packageName
        )

        mediaDescriptionBuilder.setIconBitmap(
            (AppCompatResources.getDrawable(
                this@MediaService, drawableId
            ) as BitmapDrawable).bitmap
        )

        mediaDescriptionBuilder.setExtras(Bundle().apply {
            putInt(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM,
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
            )
            putInt(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
            )
            putInt(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
            )

            putSerializable(IntentDataKeys.RECITER.name, reciter)
            putSerializable(IntentDataKeys.CHAPTER.name, chapter)
        })
        return MediaBrowserCompat.MediaItem(
            mediaDescriptionBuilder.build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        )
    }

    @SuppressLint("DiscouragedApi")
    private fun processAndPlayMedia() {
        CoroutineScope(Dispatchers.IO).launch {
            if (reciters.isEmpty() or chapters.isEmpty() or chaptersAudioFiles.isEmpty()) {
                reciters = getRecitersList()
                chapters = getChaptersList()
                chaptersAudioFiles = getReciterChaptersAudioFiles(currentReciterId)
            }

            val reciter = reciters.single { reciter -> reciter.id == currentReciterId }
            val chapter = chapters.single { chapter -> chapter.id == currentChapterId }
            val chapterAudioFile = getChapterAudioFile(currentReciterId, currentChapterId)

            sharedPrefs.edit().putSerializableExtra("LAST_RECITER", reciter).apply()
            sharedPrefs.edit().putSerializableExtra("LAST_CHAPTER", chapter).apply()

            sendBroadcast(Intent(getString(R.string.quran_media_service_updates)).apply {
                putExtra(IntentDataKeys.RECITER.name, reciter)
                putExtra(IntentDataKeys.CHAPTER.name, chapter)
            })

            val drawableId = resources.getIdentifier(
                "chapter_${chapter.id.toString().padStart(3, '0')}", "drawable", packageName
            )

            mediaSession.setMetadata(
                MediaMetadataCompat.Builder().putText(
                    MediaMetadataCompat.METADATA_KEY_TITLE,
                    getString(R.string.loading_chapter, chapter.name_arabic)
                ).putText(
                    MediaMetadataCompat.METADATA_KEY_ARTIST,
                    (if (reciter.translated_name != null) reciter.translated_name.name else reciter.reciter_name) + if (reciter.style != null) " (${reciter.style.style})" else ""
                ).putText(
                    MediaMetadataCompat.METADATA_KEY_GENRE,
                    this@MediaService.getString(R.string.quran)
                ).putBitmap(
                    MediaMetadataCompat.METADATA_KEY_ALBUM_ART, (AppCompatResources.getDrawable(
                        this@MediaService, drawableId
                    ) as BitmapDrawable).bitmap
                ).build()
            )

            Intent(
                this@MediaService, Class.forName("com.hifnawy.quran.ui.widgets.NowPlaying")
            ).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(IntentDataKeys.RECITER.name, reciter)
                putExtra(IntentDataKeys.CHAPTER.name, chapter)

                val widgetIds = AppWidgetManager.getInstance(this@MediaService).getAppWidgetIds(
                    ComponentName(
                        this@MediaService, Class.forName("com.hifnawy.quran.ui.widgets.NowPlaying")
                    )
                )
                if ((widgetIds != null) && widgetIds.isNotEmpty()) {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds)
                    sendBroadcast(this)
                }
            }

            val decimalFormat =
                DecimalFormat("#.#", DecimalFormatSymbols.getInstance(Locale("ar", "EG")))

            val (audioFile, audioFileSize) = downloadFile(
                this@MediaService, URL(chapterAudioFile?.audio_url), reciter, chapter
            ) { bytesDownloaded, fileSize, percentage ->
                serviceForegroundNotification = NotificationCompat.Builder(
                    this@MediaService,
                    "${getString(R.string.quran_recitation_notification_name)} Service"
                ).setOngoing(true).setPriority(NotificationManager.IMPORTANCE_MAX)
                    .setSmallIcon(R.drawable.quran_icon_monochrome_black_64).setSilent(true)
                    .setContentTitle(getString(R.string.loading_chapter, chapter.name_arabic))
                    .setContentText(
                        "${decimalFormat.format(bytesDownloaded / (1024 * 1024))} مب. \\ ${
                            decimalFormat.format(
                                fileSize / (1024 * 1024)
                            )
                        } مب. (${
                            decimalFormat.format(
                                percentage
                            )
                        }٪)"
                    )
                    .setSubText(if (reciter.translated_name != null) reciter.translated_name.name else reciter.reciter_name)
                    .build()

                // notificationManager.cancel(R.integer.quran_chapter_recitation_notification_channel_id)
                notificationManager.notify(
                    R.integer.quran_chapter_recitation_notification_channel_id,
                    serviceForegroundNotification
                )
            }

            if (audioFile.exists()) {
                @Suppress("BlockingMethodInNonBlockingContext") val audioFileActualSize =
                    Files.readAttributes(
                        audioFile.toPath(), BasicFileAttributes::class.java
                    ).size()
                if ((audioFileSize > -1) && (audioFileSize.toLong() == audioFileActualSize)) {
                    val mediaMetadataRetriever = MediaMetadataRetriever().apply {
                        setDataSource(this@MediaService, audioFile.toUri())
                    }

                    val durationStr =
                        mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val durationMs = durationStr!!.toInt()

                    withContext(Dispatchers.Main) {
                        setMediaPlaybackState(PLAYING)
                    }

                    mediaSession.setMetadata(
                        MediaMetadataCompat.Builder()
                            .putText(MediaMetadataCompat.METADATA_KEY_TITLE, chapter.name_arabic)
                            .putText(
                                MediaMetadataCompat.METADATA_KEY_ARTIST,
                                (if (reciter.translated_name != null) reciter.translated_name.name else reciter.reciter_name) + if (reciter.style != null) " (${reciter.style.style})" else ""
                            ).putText(
                                MediaMetadataCompat.METADATA_KEY_GENRE,
                                this@MediaService.getString(R.string.quran)
                            ).putLong(
                                MediaMetadataCompat.METADATA_KEY_DURATION, durationMs.toLong()
                            ).putBitmap(
                                MediaMetadataCompat.METADATA_KEY_ALBUM_ART,
                                (AppCompatResources.getDrawable(
                                    this@MediaService, drawableId
                                ) as BitmapDrawable).bitmap
                            ).build()
                    )

                    val pendingIntent = PendingIntent.getActivity(
                        this@MediaService, 0, Intent(
                            this@MediaService,
                            Class.forName("com.hifnawy.quran.ui.activities.MainActivity")
                        ).apply {
                            putExtra("DESTINATION", 3)
                            putExtra("RECITER", reciter)
                            putExtra("CHAPTER", chapter)
                        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )

                    val notification = NotificationCompat.Builder(
                        this@MediaService, getString(R.string.quran_recitation_notification_name)
                    ).setOngoing(true)
                        // Show controls on lock screen even when user hides sensitive content.
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setSmallIcon(R.drawable.quran_icon_monochrome_black_64).setSilent(true)
                        // Apply the media style template
                        .setStyle(
                            androidx.media.app.NotificationCompat.MediaStyle()
                                // .setShowActionsInCompactView(1 /* #1: pause button \*/)
                                .setMediaSession(mediaSession.sessionToken)
                        ).setContentTitle(chapter.name_arabic)
                        .setContentText(if (reciter.translated_name != null) reciter.translated_name.name else reciter.reciter_name)
                        .setContentIntent(pendingIntent).setLargeIcon(
                            BitmapFactory.decodeResource(
                                this@MediaService.resources, drawableId
                            )
                        ).build()

                    val channel = NotificationChannel(
                        getString(R.string.quran_recitation_notification_name),
                        getString(R.string.quran_recitation_notification_name),
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply { description = chapter.name_arabic }

                    // Register the channel with the system
                    notificationManager.createNotificationChannel(channel)
                    notificationManager.notify(
                        R.integer.quran_chapter_recitation_notification_channel_id, notification
                    )

                    exoPlayerPositionListener.removeCallbacksAndMessages(null)
                    exoPlayerPositionListener.post(object : Runnable {
                        override fun run() {
                            isMediaPlaying = exoPlayer.isPlaying
                            currentChapterPosition = exoPlayer.currentPosition

                            val reciterUpdated =
                                reciters.first { reciter -> reciter.id == currentReciterId }
                            val chapterUpdated =
                                chapters.first { chapter -> chapter.id == currentChapterId }

                            sharedPrefs.edit().putLong("LAST_CHAPTER_POSITION", currentChapterPosition)
                                .apply()

                            sendBroadcast(Intent(getString(R.string.quran_media_service_updates)).apply {
                                putExtra(IntentDataKeys.RECITER.name, reciterUpdated)
                                putExtra(IntentDataKeys.CHAPTER.name, chapterUpdated)
                                putExtra(IntentDataKeys.CHAPTER_DURATION.name, exoPlayer.duration)
                                putExtra(IntentDataKeys.CHAPTER_POSITION.name, exoPlayer.currentPosition)
                            })

                            Intent(
                                this@MediaService,
                                Class.forName("com.hifnawy.quran.ui.widgets.NowPlaying")
                            ).apply {
                                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                                putExtra(IntentDataKeys.RECITER.name, reciterUpdated)
                                putExtra(IntentDataKeys.CHAPTER.name, chapterUpdated)

                                val widgetIds =
                                    AppWidgetManager.getInstance(this@MediaService).getAppWidgetIds(
                                        ComponentName(
                                            this@MediaService,
                                            Class.forName("com.hifnawy.quran.ui.widgets.NowPlaying")
                                        )
                                    )
                                if ((widgetIds != null) && widgetIds.isNotEmpty()) {
                                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds)
                                    sendBroadcast(this)
                                }
                            }

                            exoPlayerPositionListener.postDelayed(this, 10)
                        }
                    })

                    withContext(Dispatchers.Main) {
                        if (exoPlayer.isPlaying) {
                            exoPlayer.stop()
                        }

                        val mediaItem =
                            com.google.android.exoplayer2.MediaItem.fromUri(audioFile.toUri())
                        exoPlayer.setMediaItem(mediaItem)

                        exoPlayer.prepare()

                        exoPlayer.playWhenReady = true
                    }
                }
            } else {
                FirebaseCrashlytics.getInstance()
                    .recordException(Exception("An error occurred while trying to connect to fetch ${chapterAudioFile?.audio_url}"))
                sendBroadcast(Intent(getString(R.string.quran_media_service_updates)))
            }
        }
    }

    private fun setMediaPlaybackState(state: Int) {
        lateinit var playbackState: PlaybackStateCompat
        when (state) {
            PLAYING -> playbackState = PlaybackStateCompat.Builder().setActions(
                PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_REWIND or PlaybackStateCompat.ACTION_FAST_FORWARD or PlaybackStateCompat.ACTION_SET_REPEAT_MODE or PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO
            ).setState(PlaybackStateCompat.STATE_PLAYING, exoPlayer.currentPosition, 1f).build()

            PAUSED -> playbackState = PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY)
                .setState(PlaybackStateCompat.STATE_PAUSED, exoPlayer.currentPosition, 1f).build()

            SKIPPING_TO_NEXT -> {
                startDownload = true

                playbackState = PlaybackStateCompat.Builder().setActions(PlaybackStateCompat.ACTION_STOP)
                    .setState(PlaybackStateCompat.STATE_SKIPPING_TO_NEXT, 0, 1f).build()
            }

            SKIPPING_TO_PREVIOUS -> {
                startDownload = true

                playbackState = PlaybackStateCompat.Builder().setActions(PlaybackStateCompat.ACTION_STOP)
                    .setState(PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS, 0, 1f).build()
            }

            BUFFERING -> playbackState =
                PlaybackStateCompat.Builder().setActions(PlaybackStateCompat.ACTION_STOP)
                    .setState(PlaybackStateCompat.STATE_BUFFERING, 0, 1f).build()

            CONNECTING -> playbackState =
                PlaybackStateCompat.Builder().setActions(PlaybackStateCompat.ACTION_STOP)
                    .setState(PlaybackStateCompat.STATE_CONNECTING, 0, 1f).build()

            STOPPED -> playbackState =
                PlaybackStateCompat.Builder().setActions(PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID)
                    .setState(PlaybackStateCompat.STATE_STOPPED, 0, 1f).build()
        }

        mediaSession.setPlaybackState(playbackState)
    }

    private fun skipToChapter(chapterId: Int) {
        exoPlayer.stop()

        exoPlayerPositionListener.removeCallbacksAndMessages(null)
        currentChapterPosition = -1L
        setMediaPlaybackState(SKIPPING_TO_PREVIOUS)
        processAndPlayMedia()
    }

    fun playMedia(reciter: Reciter? = null, chapter: Chapter? = null, chapterPosition: Long = 0L) {
        if ((reciter != null) && (chapter != null)) {
            Log.d("ExoPlayer_Audio_Player", "Playing Chapter ${chapter.name_simple}...")

            if (((reciter.id != currentReciterId) || (chapter.id != currentChapterId))) {
                exoPlayerPositionListener.removeCallbacksAndMessages(null)
                currentChapterPosition = -1L
                currentReciterId = reciter.id
                currentChapterId = chapter.id

                sharedPrefs.edit().putSerializableExtra("LAST_RECITER", reciter).apply()
                sharedPrefs.edit().putSerializableExtra("LAST_CHAPTER", chapter).apply()

                if (chapterPosition != -1L) {
                    sharedPrefs.edit().putLong("LAST_CHAPTER_POSITION", chapterPosition).apply()
                }

                if (exoPlayer.isPlaying) {
                    exoPlayer.stop()
                }

                currentChapterPosition = chapterPosition
                setMediaPlaybackState(BUFFERING)
                processAndPlayMedia()
            } else {
                exoPlayer.play()
                setMediaPlaybackState(PLAYING)
            }

            serviceForegroundNotificationChannel = NotificationChannel(
                "${getString(R.string.quran_recitation_notification_name)} Service",
                "${getString(R.string.quran_recitation_notification_name)} Service",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = chapter.name_arabic }

            serviceForegroundNotification = NotificationCompat.Builder(
                this@MediaService,
                "${getString(R.string.quran_recitation_notification_name)} Service"
            ).setOngoing(true).setPriority(NotificationManager.IMPORTANCE_MAX)
                .setSmallIcon(R.drawable.quran_icon_monochrome_black_64).setSilent(true)
                .setContentTitle(chapter.name_arabic)
                .setContentText(if (reciter.translated_name != null) reciter.translated_name.name else reciter.reciter_name)
                .build()

            // Register the channel with the system
            notificationManager.createNotificationChannel(
                serviceForegroundNotificationChannel
            )
            startForeground(
                R.integer.quran_chapter_recitation_notification_channel_id, serviceForegroundNotification
            )
        }
    }

    fun pauseMedia() {
        exoPlayer.pause()
        setMediaPlaybackState(PAUSED)
    }

    fun resumeMedia() {
        exoPlayer.play()
        setMediaPlaybackState(PLAYING)
    }

    fun skipToNextChapter() {
        currentChapterId = if (currentChapterId == 114) 1 else currentChapterId + 1

        Log.d("ExoPlayer_Audio_Player", "Skipping to next Chapter...")

        skipToChapter(currentChapterId)
    }

    fun skipToPreviousChapter() {
        currentChapterId = if (currentChapterId == 1) 114 else currentChapterId - 1

        Log.d("ExoPlayer_Audio_Player", "Skipping to previous Chapter...")

        skipToChapter(currentChapterId)
    }

    fun seekChapterToPosition(chapterPosition: Long) {
        if (chapterPosition != -1L) {
            Log.d("ExoPlayer_Audio_Player", "Seeking to $chapterPosition...")
            exoPlayer.seekTo(chapterPosition)
            setMediaPlaybackState(PLAYING)
        }
    }
}