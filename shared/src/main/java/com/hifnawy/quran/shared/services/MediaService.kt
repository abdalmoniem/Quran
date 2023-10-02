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
import android.content.ServiceConnection
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
import com.hifnawy.quran.shared.model.Constants
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.tools.SharedPreferencesManager
import com.hifnawy.quran.shared.tools.Utilities.Companion.downloadFile
import com.hifnawy.quran.shared.tools.Utilities.Companion.getSerializableExtra
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

class MediaService : MediaBrowserServiceCompat(), Player.Listener {
    companion object {
        var isRunning = false
        var isMediaPlaying = false
        var startDownload = false

        var downloadComplete = false

        @Volatile
        var instance: MediaService? = null

        private val serviceConnection: ServiceConnection = object : ServiceConnection {
            override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
                synchronized(this@Companion) {
                    instance = (iBinder as MediaService.ServiceBinder).instance
                }
            }

            override fun onServiceDisconnected(componentName: ComponentName) {
                synchronized(this@Companion) {
                    instance = null
                }
            }
        }

        fun initialize(
            context: Context,
            reciter: Reciter?,
            chapter: Chapter?,
        ) {
            if (instance == null) {
                with(context) {
                    startForegroundService(Intent(
                        context, MediaService::class.java
                    ).apply {
                        action = Constants.Actions.PLAY_MEDIA.name
                        putExtra(
                            Constants.IntentDataKeys.RECITER.name, reciter
                        )
                        putExtra(
                            Constants.IntentDataKeys.CHAPTER.name, chapter
                        )
                        putExtra(
                            Constants.IntentDataKeys.CHAPTER_POSITION.name, 0L
                        )
                    })

                    bindService(
                        Intent(context, MediaService::class.java),
                        serviceConnection,
                        Context.BIND_AUTO_CREATE
                    )
                }
            }
        }
    }

    inner class ServiceBinder : Binder() {
        val instance: MediaService
            get() = this@MediaService
    }

    private enum class MediaSessionState {
        PLAYING, PAUSED, SKIPPING_TO_NEXT, SKIPPING_TO_PREVIOUS, BUFFERING, CONNECTING, STOPPED
    }

    private enum class MediaState {
        RECITER_BROWSE, CHAPTER_BROWSE, CHAPTER_PLAY
    }

    @Suppress("PrivatePropertyName")
    private val MEDIA_ROOT_ID = "ROOT"

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

    private lateinit var mediaSession: MediaSessionCompat

    private lateinit var playerStatusHandler: Handler

    private lateinit var sharedPrefsManager: SharedPreferencesManager

    private lateinit var serviceForegroundNotification: Notification

    private lateinit var serviceForegroundNotificationChannel: NotificationChannel

    private lateinit var currentReciter: Reciter

    private lateinit var currentChapter: Chapter

    private var currentChapterPosition: Long = -1L

    private var reciters: List<Reciter> = mutableListOf()

    private var chapters: List<Chapter> = mutableListOf()

    private var chaptersAudioFiles: List<ChapterAudioFile> = mutableListOf()

    private var mediaState = MediaState.RECITER_BROWSE

    override fun onCreate() {
        super.onCreate()

        isRunning = true

        startDownload = true

        playerStatusHandler = Handler(Looper.getMainLooper())

        Log.d(
            ::MediaService.javaClass.name, "${::MediaService.javaClass.name} service started!!!"
        )

        mediaSession = MediaSessionCompat(this, "QuranMediaService")

        sessionToken = mediaSession.sessionToken

        sharedPrefsManager = SharedPreferencesManager(this)

        currentChapterPosition = sharedPrefsManager.lastChapterPosition

        with(mediaSession) {
            setCallback(MediaSessionCallback(this@MediaService, sharedPrefsManager))

            isActive = true
        }

        exoPlayer.addListener(this)
    }

    override fun onDestroy() {
        isRunning = false

        super.onDestroy()
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
                        Constants.Actions.PLAY_MEDIA.name -> {
                            val reciter =
                                getSerializableExtra<Reciter>(Constants.IntentDataKeys.RECITER.name)
                            val chapter =
                                getSerializableExtra<Chapter>(Constants.IntentDataKeys.CHAPTER.name)
                            val chapterPosition =
                                getLongExtra(Constants.IntentDataKeys.CHAPTER_POSITION.name, -1L)

                            playMedia(reciter, chapter, chapterPosition)
                        }

                        Constants.Actions.PAUSE_MEDIA.name -> pauseMedia()

                        Constants.Actions.STOP_MEDIA.name -> stopSelf()

                        Constants.Actions.SKIP_TO_NEXT_MEDIA.name -> skipToNextChapter()

                        Constants.Actions.SKIP_TO_PREVIOUS_MEDIA.name -> skipToPreviousChapter()

                        Constants.Actions.SEEK_MEDIA.name -> {
                            val chapterPosition =
                                getLongExtra(Constants.IntentDataKeys.CHAPTER_POSITION.name, -1L)

                            seekChapterToPosition(chapterPosition)
                        }
                    }
                }
            }
        }

        return START_STICKY
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
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
                chapters = getChaptersList()

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
                        chaptersAudioFiles = getReciterChaptersAudioFiles(reciterId)

                        chapters.forEach { chapter ->
                            mediaItems.add(
                                createMediaItem("chapter_${chapter.id}",
                                    reciter,
                                    chapter,
                                    chaptersAudioFiles.single { chapterAudioFile ->
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

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        setMediaPlaybackState(if (isPlaying) MediaSessionState.PLAYING else MediaSessionState.PAUSED)
    }

    override fun onPlaybackStateChanged(state: Int) {
        when (state) {
            Player.STATE_IDLE -> {}
            Player.STATE_BUFFERING -> {}
            Player.STATE_READY -> setMediaPlaybackState(MediaSessionState.PLAYING)

            Player.STATE_ENDED -> {
                setMediaPlaybackState(MediaSessionState.BUFFERING)
                currentChapter =
                    chapters.single { chapter -> chapter.id == (if (currentChapter.id == 114) 1 else currentChapter.id + 1) }
                currentChapterPosition = -1L
                processAndPlayMedia()
            }
        }
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

            putSerializable(Constants.IntentDataKeys.RECITER.name, reciter)
            putSerializable(Constants.IntentDataKeys.CHAPTER.name, chapter)
        })
        return MediaBrowserCompat.MediaItem(
            mediaDescriptionBuilder.build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        )
    }

    @SuppressLint("DiscouragedApi")
    private fun processAndPlayMedia() {
        playerStatusHandler.removeCallbacksAndMessages(null)

        CoroutineScope(Dispatchers.IO).launch {
            if (reciters.isEmpty() or chapters.isEmpty()) {
                reciters = getRecitersList()
                chapters = getChaptersList()
            }

            val chapterAudioFile = getChapterAudioFile(currentReciter.id, currentChapter.id)

            sharedPrefsManager.lastReciter = currentReciter
            sharedPrefsManager.lastChapter = currentChapter

            sendBroadcast(Intent(getString(R.string.quran_media_service_updates)).apply {
                addCategory(Constants.Actions.SERVICE_UPDATE.name)

                putExtra(Constants.IntentDataKeys.RECITER.name, currentReciter)
                putExtra(Constants.IntentDataKeys.CHAPTER.name, currentChapter)
            })

            val drawableId = resources.getIdentifier(
                "chapter_${currentChapter.id.toString().padStart(3, '0')}", "drawable", packageName
            )

            mediaSession.setMetadata(
                MediaMetadataCompat.Builder().putText(
                    MediaMetadataCompat.METADATA_KEY_TITLE,
                    getString(R.string.loading_chapter, currentChapter.name_arabic)
                ).putText(
                    MediaMetadataCompat.METADATA_KEY_ARTIST,
                    (if (currentReciter.translated_name != null) currentReciter.translated_name!!.name else currentReciter.reciter_name) + if (currentReciter.style != null) " (${currentReciter.style?.style})" else ""
                ).putText(
                    MediaMetadataCompat.METADATA_KEY_GENRE, this@MediaService.getString(R.string.quran)
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

                putExtra(Constants.IntentDataKeys.RECITER.name, currentReciter)
                putExtra(Constants.IntentDataKeys.CHAPTER.name, currentChapter)

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
                this@MediaService, URL(chapterAudioFile?.audio_url), currentReciter, currentChapter
            ) { bytesDownloaded, fileSize, percentage ->
                serviceForegroundNotification = NotificationCompat.Builder(
                    this@MediaService,
                    "${getString(R.string.quran_recitation_notification_name)} Service"
                ).setOngoing(true).setPriority(NotificationManager.IMPORTANCE_MAX)
                    .setSmallIcon(R.drawable.quran_icon_monochrome_black_64).setSilent(true)
                    .setContentTitle(getString(R.string.loading_chapter, currentChapter.name_arabic))
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
                    .setSubText(if (currentReciter.translated_name != null) currentReciter.translated_name!!.name else currentReciter.reciter_name)
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
                        setMediaPlaybackState(MediaSessionState.PLAYING)
                    }

                    mediaSession.setMetadata(
                        MediaMetadataCompat.Builder()
                            .putText(MediaMetadataCompat.METADATA_KEY_TITLE, currentChapter.name_arabic)
                            .putText(
                                MediaMetadataCompat.METADATA_KEY_ARTIST,
                                (if (currentReciter.translated_name != null) currentReciter.translated_name!!.name else currentReciter.reciter_name) + if (currentReciter.style != null) " (${currentReciter.style!!.style})" else ""
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
                            putExtra(Constants.IntentDataKeys.RECITER.name, currentReciter)
                            putExtra(Constants.IntentDataKeys.CHAPTER.name, currentChapter)
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
                        ).setContentTitle(currentChapter.name_arabic)
                        .setContentText(if (currentReciter.translated_name != null) currentReciter.translated_name!!.name else currentReciter.reciter_name)
                        .setContentIntent(pendingIntent).setLargeIcon(
                            BitmapFactory.decodeResource(
                                this@MediaService.resources, drawableId
                            )
                        ).build()

                    val channel = NotificationChannel(
                        getString(R.string.quran_recitation_notification_name),
                        getString(R.string.quran_recitation_notification_name),
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply { description = currentChapter.name_arabic }

                    // Register the channel with the system
                    notificationManager.createNotificationChannel(channel)
                    notificationManager.notify(
                        R.integer.quran_chapter_recitation_notification_channel_id, notification
                    )

                    playerStatusHandler.post(object : Runnable {
                        override fun run() {
                            isMediaPlaying = exoPlayer.isPlaying
                            currentChapterPosition = exoPlayer.currentPosition

                            val reciterUpdated =
                                reciters.single { reciter -> reciter.id == currentReciter.id }
                            val chapterUpdated =
                                chapters.single { chapter -> chapter.id == currentChapter.id }

                            sharedPrefsManager.lastChapterPosition = currentChapterPosition

                            sendBroadcast(Intent(getString(R.string.quran_media_service_updates)).apply {
                                addCategory(Constants.Actions.SERVICE_UPDATE.name)

                                putExtra(Constants.IntentDataKeys.RECITER.name, reciterUpdated)
                                putExtra(Constants.IntentDataKeys.CHAPTER.name, chapterUpdated)
                                putExtra(
                                    Constants.IntentDataKeys.CHAPTER_DURATION.name, exoPlayer.duration
                                )
                                putExtra(
                                    Constants.IntentDataKeys.CHAPTER_POSITION.name,
                                    exoPlayer.currentPosition
                                )
                            })

                            Intent(
                                this@MediaService,
                                Class.forName("com.hifnawy.quran.ui.widgets.NowPlaying")
                            ).apply {
                                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                                putExtra(Constants.IntentDataKeys.RECITER.name, reciterUpdated)
                                putExtra(Constants.IntentDataKeys.CHAPTER.name, chapterUpdated)

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

                            playerStatusHandler.postDelayed(this, 10)
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
                sendBroadcast(Intent(getString(R.string.quran_media_service_updates)).apply {
                    action = Constants.Actions.ERROR.name
                })
            }
        }
    }

    private fun setMediaPlaybackState(state: MediaSessionState) {
        lateinit var playbackState: PlaybackStateCompat
        when (state) {
            MediaSessionState.PLAYING -> playbackState = PlaybackStateCompat.Builder().setActions(
                PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_REWIND or PlaybackStateCompat.ACTION_FAST_FORWARD or PlaybackStateCompat.ACTION_SET_REPEAT_MODE or PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO
            ).setState(PlaybackStateCompat.STATE_PLAYING, exoPlayer.currentPosition, 1f).build()

            MediaSessionState.PAUSED -> playbackState = PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY)
                .setState(PlaybackStateCompat.STATE_PAUSED, exoPlayer.currentPosition, 1f).build()

            MediaSessionState.SKIPPING_TO_NEXT -> {
                startDownload = true

                playbackState = PlaybackStateCompat.Builder().setActions(PlaybackStateCompat.ACTION_STOP)
                    .setState(PlaybackStateCompat.STATE_SKIPPING_TO_NEXT, 0, 1f).build()
            }

            MediaSessionState.SKIPPING_TO_PREVIOUS -> {
                startDownload = true

                playbackState = PlaybackStateCompat.Builder().setActions(PlaybackStateCompat.ACTION_STOP)
                    .setState(PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS, 0, 1f).build()
            }

            MediaSessionState.BUFFERING -> playbackState =
                PlaybackStateCompat.Builder().setActions(PlaybackStateCompat.ACTION_STOP)
                    .setState(PlaybackStateCompat.STATE_BUFFERING, 0, 1f).build()

            MediaSessionState.CONNECTING -> playbackState =
                PlaybackStateCompat.Builder().setActions(PlaybackStateCompat.ACTION_STOP)
                    .setState(PlaybackStateCompat.STATE_CONNECTING, 0, 1f).build()

            MediaSessionState.STOPPED -> playbackState =
                PlaybackStateCompat.Builder().setActions(PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID)
                    .setState(PlaybackStateCompat.STATE_STOPPED, 0, 1f).build()
        }

        mediaSession.setPlaybackState(playbackState)
    }

    private fun skipToChapter(chapter: Chapter) {
        exoPlayer.stop()

        currentChapterPosition = -1L
        setMediaPlaybackState(MediaSessionState.SKIPPING_TO_PREVIOUS)
        processAndPlayMedia()
    }

    fun playMedia(reciter: Reciter? = null, chapter: Chapter? = null, chapterPosition: Long = 0L) {
        if ((reciter != null) && (chapter != null)) {
            Log.d("ExoPlayer_Audio_Player", "Playing Chapter ${chapter.name_simple}...")

            if (this@MediaService::currentReciter.isInitialized && this@MediaService::currentChapter.isInitialized) {
                if ((reciter.id != currentReciter.id) || (chapter.id != currentChapter.id)) {
                    currentReciter = reciter
                    currentChapter = chapter
                    currentChapterPosition = chapterPosition

                    sharedPrefsManager.lastReciter = currentReciter
                    sharedPrefsManager.lastChapter = currentChapter
                    sharedPrefsManager.lastChapterPosition = chapterPosition

                    if (exoPlayer.isPlaying) {
                        exoPlayer.stop()
                    }

                    setMediaPlaybackState(MediaSessionState.BUFFERING)
                    processAndPlayMedia()
                } else {
                    exoPlayer.play()
                    setMediaPlaybackState(MediaSessionState.PLAYING)
                }
            } else {
                currentReciter = reciter
                currentChapter = chapter
                currentChapterPosition = chapterPosition

                sharedPrefsManager.lastReciter = currentReciter
                sharedPrefsManager.lastChapter = currentChapter
                sharedPrefsManager.lastChapterPosition = chapterPosition

                setMediaPlaybackState(MediaSessionState.BUFFERING)
                processAndPlayMedia()
            }

            serviceForegroundNotificationChannel = NotificationChannel(
                "${getString(R.string.quran_recitation_notification_name)} Service",
                "${getString(R.string.quran_recitation_notification_name)} Service",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = chapter.name_arabic }

            serviceForegroundNotification = NotificationCompat.Builder(
                this@MediaService, "${getString(R.string.quran_recitation_notification_name)} Service"
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
        setMediaPlaybackState(MediaSessionState.PAUSED)
    }

    fun resumeMedia() {
        exoPlayer.play()
        setMediaPlaybackState(MediaSessionState.PLAYING)
    }

    fun skipToNextChapter() {
        currentChapter =
            chapters.single { chapter -> chapter.id == (if (currentChapter.id == 114) 1 else currentChapter.id + 1) }

        Log.d("ExoPlayer_Audio_Player", "Skipping to next Chapter...")

        skipToChapter(currentChapter)
    }

    fun skipToPreviousChapter() {
        currentChapter =
            chapters.single { chapter -> chapter.id == (if (currentChapter.id == 114) 1 else currentChapter.id + 1) }

        Log.d("ExoPlayer_Audio_Player", "Skipping to previous Chapter...")

        skipToChapter(currentChapter)
    }

    fun seekChapterToPosition(chapterPosition: Long) {
        if (chapterPosition != -1L) {
            Log.d("ExoPlayer_Audio_Player", "Seeking to $chapterPosition...")
            exoPlayer.seekTo(chapterPosition)
            setMediaPlaybackState(MediaSessionState.PLAYING)
        }
    }
}
