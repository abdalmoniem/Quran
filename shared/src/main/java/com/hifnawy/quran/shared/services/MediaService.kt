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
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
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
import com.hifnawy.quran.shared.R
import com.hifnawy.quran.shared.api.QuranAPI.Companion.getChaptersList
import com.hifnawy.quran.shared.api.QuranAPI.Companion.getReciterChaptersAudioFiles
import com.hifnawy.quran.shared.api.QuranAPI.Companion.getRecitersList
import com.hifnawy.quran.shared.extensions.SerializableExt.Companion.getTypedSerializable
import com.hifnawy.quran.shared.managers.DownloadWorkManager
import com.hifnawy.quran.shared.managers.MediaManager
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.ChapterAudioFile
import com.hifnawy.quran.shared.model.Constants
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.storage.SharedPreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.File
import java.util.Timer
import java.util.TimerTask

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

    inner class ServiceBinder : Binder() {

        val instance: MediaService
            get() = this@MediaService
    }

    var isMediaPlaying = false

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
    private var playbackMonitorTimer = Timer()
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var sharedPrefsManager: SharedPreferencesManager
    private lateinit var serviceForegroundNotification: Notification
    private lateinit var serviceForegroundNotificationChannel: NotificationChannel
    private val mediaManager: MediaManager by lazy { MediaManager.getInstance(this) }

    @Suppress("PrivatePropertyName")
    private val TAG = MediaService::class.simpleName
    private var currentReciter: Reciter? = null
    private var currentChapter: Chapter? = null
    private var currentChapterPosition: Long = -1L
    private var mediaState = MediaState.RECITER_BROWSE

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "$TAG service started!!!")

        sharedPrefsManager = SharedPreferencesManager(this)

        currentChapterPosition = sharedPrefsManager.lastChapterPosition

        mediaSession = MediaSessionCompat(this, "QuranMediaService")

        sessionToken = mediaSession.sessionToken

        mediaSession.setCallback(MediaSessionCallback(this@MediaService, sharedPrefsManager))

        mediaSession.isActive = true

        with(mediaManager) {
            mediaStateListener = MediaManager.MediaStateListener(::updateMediaSession)
            downloadListener = MediaManager.DownloadListener(::processDownloadProgress)
        }

        exoPlayer.addListener(this)
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == SERVICE_INTERFACE) super.onBind(intent)
        else serviceBinder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var reciter: Reciter? = null
        var chapter: Chapter? = null

        intent?.let { serviceIntent ->
            serviceIntent.action?.let { action ->
                with(serviceIntent) {
                    when (action) {
                        Constants.Actions.START_SERVICE.name -> Unit
                        Constants.Actions.PLAY_MEDIA.name -> {
                            reciter = getTypedSerializable(Constants.IntentDataKeys.RECITER.name)
                            chapter = getTypedSerializable(Constants.IntentDataKeys.CHAPTER.name)
                            val chapterPosition =
                                getLongExtra(Constants.IntentDataKeys.CHAPTER_POSITION.name, -1L)

                            if ((reciter == null) || (chapter == null)) return -1

                            Log.d(TAG, "onStartCommand with position: $chapterPosition")
                            prepareMedia(reciter, chapter, chapterPosition)
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

        serviceForegroundNotificationChannel = NotificationChannel(
                "${getString(R.string.quran_recitation_notification_name)} Service",
                "${getString(R.string.quran_recitation_notification_name)} Service",
                NotificationManager.IMPORTANCE_HIGH
        ).apply { description = chapter?.name_arabic ?: getString(R.string.chapter_name) }

        serviceForegroundNotification = NotificationCompat.Builder(
                this@MediaService, "${getString(R.string.quran_recitation_notification_name)} Service"
        ).setOngoing(true).setPriority(NotificationManager.IMPORTANCE_MAX)
            .setSmallIcon(R.drawable.quran_icon_monochrome_black_64).setSilent(true)
            .setContentTitle(chapter?.name_arabic ?: getString(R.string.chapter_name))
            .setContentText(reciter?.name_ar ?: getString(R.string.reciter_name)).build()
        // Register the channel with the system
        notificationManager.createNotificationChannel(
                serviceForegroundNotificationChannel
        )
        startForeground(
                R.integer.quran_chapter_recitation_notification_channel_id, serviceForegroundNotification
        )

        return START_STICKY
    }

    override fun onDestroy() {
        mediaManager.stopLifecycle()
        mediaManager.cancelPendingDownloads()
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot =
        BrowserRoot(MEDIA_ROOT_ID, null)

    @OptIn(DelicateCoroutinesApi::class)
    override fun onLoadChildren(
            parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        val mediaItems: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()

        GlobalScope.launch {
            // Check whether this is the root menu:
            if (parentId == MEDIA_ROOT_ID) {
                // Build the MediaItem objects for the top level
                // and put them in the mediaItems list.
                mediaManager.reciters = GlobalScope.async(Dispatchers.IO) { getRecitersList() }.await()
                mediaManager.chapters = GlobalScope.async(Dispatchers.IO) { getChaptersList() }.await()

                mediaItems.add(
                        createBrowsableMediaItem(
                                "quran_reciters", getString(R.string.quran)
                        )
                )
            } else {
                if (mediaManager.reciters.isEmpty()) mediaManager.reciters =
                    GlobalScope.async(Dispatchers.IO) { getRecitersList() }.await()
                if (mediaManager.chapters.isEmpty()) mediaManager.chapters =
                    GlobalScope.async(Dispatchers.IO) { getChaptersList() }.await()

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
                        mediaManager.reciters.forEach { reciter ->
                            mediaItems.add(
                                    createBrowsableMediaItem(
                                            "reciter_${reciter.id}",
                                            (reciter.name_ar + if (reciter.style != null) " (${reciter.style.style})" else "")
                                    )
                            )
                        }
                    }

                    MediaState.CHAPTER_BROWSE -> {
                        val reciterId = parentId.replace("reciter_", "").toInt()
                        val reciter = mediaManager.reciters.single { reciter -> reciter.id == reciterId }
                        val chaptersAudioFiles = getReciterChaptersAudioFiles(reciterId)

                        mediaManager.chapters.forEach { chapter ->
                            mediaItems.add(
                                    createMediaItem("chapter_${chapter.id}",
                                                    reciter,
                                                    chapter,
                                                    chaptersAudioFiles.find { chapterAudioFile ->
                                                        chapterAudioFile.chapter_id == chapter.id
                                                    })
                            )
                        }
                    }

                    MediaState.CHAPTER_PLAY -> Unit
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
            Player.STATE_IDLE -> Unit
            Player.STATE_BUFFERING -> Unit
            Player.STATE_READY -> {
                setMediaPlaybackState(MediaSessionState.PLAYING)

                try {
                    startPlaybackMonitoring()
                } catch (_: IllegalStateException) {
                    Log.w(
                            TAG,
                            "${this::playbackMonitorTimer.name} is already cancelled or not started, skipping cancellation!"
                    )
                    playbackMonitorTimer = Timer()
                    startPlaybackMonitoring()
                }
            }

            Player.STATE_ENDED -> {
                currentChapterPosition = -1L
                skipToNextChapter()
            }
        }
    }

    private fun startPlaybackMonitoring() {
        val mainCoroutineScope = CoroutineScope(Dispatchers.Main)

        playbackMonitorTimer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                mainCoroutineScope.launch {
                    isMediaPlaying = exoPlayer.isPlaying
                    // currentChapterPosition = exoPlayer.currentPosition
                    sharedPrefsManager.lastChapterPosition = exoPlayer.currentPosition

                    updateMediaPlayer(
                            currentReciter!!,
                            currentChapter!!,
                            exoPlayer.duration,
                            exoPlayer.currentPosition
                    )

                    updateWidget(currentReciter!!, currentChapter!!)
                }
            }
        }, 0, 500)
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

    private fun createMediaItem(
            mediaId: String, reciter: Reciter, chapter: Chapter, chapterAudioFile: ChapterAudioFile?
    ): MediaBrowserCompat.MediaItem {
        val mediaDescriptionBuilder = MediaDescriptionCompat.Builder()
        mediaDescriptionBuilder.setMediaId(mediaId)
        mediaDescriptionBuilder.setTitle(chapter.name_arabic)

        if (chapterAudioFile != null) {
            mediaDescriptionBuilder.setMediaUri(Uri.parse(chapterAudioFile.audio_url))
        }
        @SuppressLint("DiscouragedApi") val drawableId = resources.getIdentifier(
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

    private fun setMediaPlaybackState(state: MediaSessionState) {
        lateinit var playbackState: PlaybackStateCompat
        when (state) {
            MediaSessionState.PLAYING -> playbackState = PlaybackStateCompat.Builder().setActions(
                    PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_REWIND or PlaybackStateCompat.ACTION_FAST_FORWARD or
                            PlaybackStateCompat.ACTION_SET_REPEAT_MODE or PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackStateCompat.ACTION_SEEK_TO
            ).setState(PlaybackStateCompat.STATE_PLAYING, exoPlayer.currentPosition, 1f).build()

            MediaSessionState.PAUSED -> playbackState = PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY)
                .setState(PlaybackStateCompat.STATE_PAUSED, exoPlayer.currentPosition, 1f).build()

            MediaSessionState.SKIPPING_TO_NEXT -> playbackState =
                PlaybackStateCompat.Builder().setActions(PlaybackStateCompat.ACTION_STOP)
                    .setState(PlaybackStateCompat.STATE_SKIPPING_TO_NEXT, 0, 1f).build()

            MediaSessionState.SKIPPING_TO_PREVIOUS -> playbackState =
                PlaybackStateCompat.Builder().setActions(PlaybackStateCompat.ACTION_STOP)
                    .setState(PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS, 0, 1f).build()

            MediaSessionState.BUFFERING -> playbackState =
                PlaybackStateCompat.Builder()
                    .setActions(
                            PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    )
                    .setState(PlaybackStateCompat.STATE_BUFFERING, 0, 1f).build()

            MediaSessionState.CONNECTING -> playbackState =
                PlaybackStateCompat.Builder()
                    .setActions(
                            PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    )
                    .setState(PlaybackStateCompat.STATE_CONNECTING, 0, 1f).build()

            MediaSessionState.STOPPED -> playbackState =
                PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID)
                    .setState(PlaybackStateCompat.STATE_STOPPED, 0, 1f).build()
        }

        mediaSession.setPlaybackState(playbackState)
    }

    private fun updateMediaSession(
            reciter: Reciter,
            chapter: Chapter,
            audioFile: File,
            drawable: Drawable?
    ) {
        currentReciter = reciter
        currentChapter = chapter

        Log.d(TAG, "playing ${audioFile.name} from $currentChapterPosition...")
        val mediaMetadataRetriever = MediaMetadataRetriever().apply {
            setDataSource(this@MediaService, audioFile.toUri())
        }
        val durationStr =
            mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val durationMs = durationStr!!.toInt()

        mediaSession.setMetadata(
                MediaMetadataCompat.Builder().putText(
                        MediaMetadataCompat.METADATA_KEY_TITLE, currentChapter!!.name_arabic
                ).putText(
                        MediaMetadataCompat.METADATA_KEY_ARTIST,
                        (currentReciter!!.name_ar) + if (currentReciter!!.style != null) " (${currentReciter!!.style!!.style})" else ""
                ).putText(
                        MediaMetadataCompat.METADATA_KEY_GENRE,
                        getString(R.string.quran)
                ).putLong(
                        MediaMetadataCompat.METADATA_KEY_DURATION, durationMs.toLong()
                ).putBitmap(
                        MediaMetadataCompat.METADATA_KEY_ALBUM_ART, (drawable as BitmapDrawable).bitmap
                ).build()
        )

        showMediaNotification(drawable)

        if (exoPlayer.isPlaying || (currentChapterPosition == 0L)) {
            exoPlayer.stop()
        }
        val mediaItem = com.google.android.exoplayer2.MediaItem.fromUri(audioFile.toUri())
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        Log.d(TAG, "seeking to $currentChapterPosition...")
        exoPlayer.seekTo(currentChapterPosition)

        setMediaPlaybackState(MediaSessionState.PLAYING)
    }

    private fun updateMediaPlayer(
            reciter: Reciter, chapter: Chapter, duration: Long = -1L, currentPosition: Long = -1L
    ) {
        sendBroadcast(Intent(getString(R.string.quran_media_service_updates)).apply {
            addCategory(Constants.ServiceUpdates.SERVICE_UPDATE.name)

            putExtra(Constants.IntentDataKeys.RECITER.name, reciter)
            putExtra(Constants.IntentDataKeys.CHAPTER.name, chapter)
            putExtra(
                    Constants.IntentDataKeys.CHAPTER_DURATION.name, duration
            )
            putExtra(
                    Constants.IntentDataKeys.CHAPTER_POSITION.name, currentPosition
            )
        })
    }

    private fun updateWidget(reciter: Reciter, chapter: Chapter) {
        Intent(this, Constants.NowPlayingClass).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE

            putExtra(Constants.IntentDataKeys.RECITER.name, reciter)
            putExtra(Constants.IntentDataKeys.CHAPTER.name, chapter)
            putExtra(Constants.IntentDataKeys.CHAPTER_POSITION.name, exoPlayer.currentPosition)
            putExtra(Constants.IntentDataKeys.IS_MEDIA_PLAYING.name, isMediaPlaying)
            val widgetIds = AppWidgetManager.getInstance(this@MediaService).getAppWidgetIds(
                    ComponentName(this@MediaService, Constants.NowPlayingClass)
            )
            if ((widgetIds != null) && widgetIds.isNotEmpty()) {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds)
                sendBroadcast(this)
            }
        }
    }

    @SuppressLint("DiscouragedApi")
    @Suppress("UNUSED_PARAMETER")
    private fun processDownloadProgress(
            reciter: Reciter,
            chapter: Chapter,
            downloadStatus: DownloadWorkManager.DownloadStatus,
            bytesDownloaded: Long,
            audioFileSize: Int,
            progress: Float,
            audioFile: File?
    ) {
        Log.d(
                TAG,
                "$downloadStatus ${currentChapter?.name_simple} $bytesDownloaded / $audioFileSize (${progress}%)"
        )

        when (downloadStatus) {
            DownloadWorkManager.DownloadStatus.STARTING_DOWNLOAD -> {
                currentReciter = reciter
                currentChapter = chapter
                val drawableId = resources.getIdentifier(
                        "chapter_${currentChapter!!.id.toString().padStart(3, '0')}",
                        "drawable",
                        packageName
                )

                setMediaPlaybackState(MediaSessionState.BUFFERING)

                mediaSession.setMetadata(
                        MediaMetadataCompat.Builder().putText(
                                MediaMetadataCompat.METADATA_KEY_TITLE,
                                getString(R.string.loading_chapter, currentChapter!!.name_arabic)
                        ).putText(
                                MediaMetadataCompat.METADATA_KEY_ARTIST,
                                (currentReciter!!.name_ar) + if (currentReciter!!.style != null) " (${currentReciter!!.style?.style})" else ""
                        ).putText(
                                MediaMetadataCompat.METADATA_KEY_GENRE,
                                getString(R.string.quran)
                        ).putBitmap(
                                MediaMetadataCompat.METADATA_KEY_ALBUM_ART,
                                (AppCompatResources.getDrawable(
                                        this@MediaService, drawableId
                                ) as BitmapDrawable).bitmap
                        ).build()
                )
            }

            DownloadWorkManager.DownloadStatus.DOWNLOADING -> Unit
            DownloadWorkManager.DownloadStatus.FILE_EXISTS,
            DownloadWorkManager.DownloadStatus.FINISHED_DOWNLOAD -> setMediaPlaybackState(
                    MediaSessionState.PLAYING
            )

            DownloadWorkManager.DownloadStatus.DOWNLOAD_ERROR -> Unit
            DownloadWorkManager.DownloadStatus.DOWNLOAD_INTERRUPTED -> Unit
        }
    }

    private fun showMediaNotification(drawable: Drawable) {
        val pendingIntent = PendingIntent.getActivity(
                this@MediaService, 0, Intent(
                this@MediaService, Class.forName("com.hifnawy.quran.ui.activities.MainActivity")
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
            ).setContentTitle(currentChapter!!.name_arabic).setContentText(currentReciter!!.name_ar)
            .setContentIntent(pendingIntent).setLargeIcon((drawable as BitmapDrawable).bitmap).build()
        val channel = NotificationChannel(
                getString(R.string.quran_recitation_notification_name),
                getString(R.string.quran_recitation_notification_name),
                NotificationManager.IMPORTANCE_HIGH
        ).apply { description = currentChapter!!.name_arabic }
        // Register the channel with the system
        notificationManager.createNotificationChannel(channel)
        notificationManager.notify(
                R.integer.quran_chapter_recitation_notification_channel_id, notification
        )
    }

    private fun playMedia(reciter: Reciter, chapter: Chapter, chapterPosition: Long) {
        if (exoPlayer.isPlaying) {
            exoPlayer.stop()
        }

        setMediaPlaybackState(MediaSessionState.BUFFERING)

        updateMediaPlayer(reciter, chapter, chapterPosition)
        updateWidget(reciter, chapter)
        CoroutineScope(Dispatchers.IO).launch { mediaManager.processChapter(reciter, chapter) }
        @SuppressLint("DiscouragedApi") val drawableId = resources.getIdentifier(
                "chapter_${chapter.id.toString().padStart(3, '0')}", "drawable", packageName
        )
        showMediaNotification(AppCompatResources.getDrawable(this@MediaService, drawableId)!!)
    }

    fun prepareMedia(reciter: Reciter? = null, chapter: Chapter? = null, chapterPosition: Long = 0L) {
        if ((reciter == null) || (chapter == null)) return
        Log.d("ExoPlayer_Audio_Player", "Playing Chapter ${chapter.name_simple}...")

        currentChapterPosition = chapterPosition
        sharedPrefsManager.lastReciter = reciter
        sharedPrefsManager.lastChapter = chapter
        sharedPrefsManager.lastChapterPosition = chapterPosition

        isMediaPlaying = false

        if (currentReciter == null || currentChapter == null) {
            currentReciter = reciter
            currentChapter = chapter

            playMedia(reciter, chapter, chapterPosition)
            return
        }

        if ((reciter.id != currentReciter?.id) || (chapter.id != currentChapter?.id)) {
            currentReciter = reciter
            currentChapter = chapter
            playbackMonitorTimer.cancel()
            playbackMonitorTimer.purge()

            playMedia(reciter, chapter, chapterPosition)
            return
        }

        playMedia(reciter, chapter, chapterPosition)
        // setMediaPlaybackState(MediaSessionState.PLAYING)
    }

    fun pauseMedia() {
        exoPlayer.pause()
        setMediaPlaybackState(MediaSessionState.PAUSED)
    }

    fun resumeMedia() {
        exoPlayer.play()
        setMediaPlaybackState(MediaSessionState.PLAYING)
    }

    fun stop() {
        exoPlayer.stop()
        mediaManager.cancelPendingDownloads()
        setMediaPlaybackState(MediaSessionState.STOPPED)
    }

    fun skipToNextChapter() {
        exoPlayer.stop()
        currentChapterPosition = -1L
        playbackMonitorTimer.cancel()
        playbackMonitorTimer.purge()
        CoroutineScope(Dispatchers.IO).launch { mediaManager.processNextChapter() }
    }

    fun skipToPreviousChapter() {
        exoPlayer.stop()
        playbackMonitorTimer.cancel()
        playbackMonitorTimer.purge()
        currentChapterPosition = -1L
        CoroutineScope(Dispatchers.IO).launch { mediaManager.processPreviousChapter() }
    }

    fun seekChapterToPosition(chapterPosition: Long) {
        if (chapterPosition != -1L) {
            Log.d("ExoPlayer_Audio_Player", "Seeking to $chapterPosition...")
            exoPlayer.seekTo(chapterPosition)
            setMediaPlaybackState(MediaSessionState.PLAYING)
        }
    }
}
