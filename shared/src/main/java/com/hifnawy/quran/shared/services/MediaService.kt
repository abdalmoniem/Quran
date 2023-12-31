package com.hifnawy.quran.shared.services

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
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
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase
import com.hifnawy.quran.shared.BuildConfig
import com.hifnawy.quran.shared.R
import com.hifnawy.quran.shared.extensions.NumberExt.dp
import com.hifnawy.quran.shared.extensions.SerializableExt.Companion.getTypedSerializable
import com.hifnawy.quran.shared.managers.DownloadWorkManager.DownloadStatus
import com.hifnawy.quran.shared.managers.MediaManager
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.ChapterAudioFile
import com.hifnawy.quran.shared.model.Constants
import com.hifnawy.quran.shared.model.Constants.IntentDataKeys
import com.hifnawy.quran.shared.model.Constants.MediaServiceActions.CANCEL_DOWNLOADS
import com.hifnawy.quran.shared.model.Constants.MediaServiceActions.DOWNLOAD_CHAPTERS
import com.hifnawy.quran.shared.model.Constants.MediaServiceActions.PAUSE_MEDIA
import com.hifnawy.quran.shared.model.Constants.MediaServiceActions.PLAY_MEDIA
import com.hifnawy.quran.shared.model.Constants.MediaServiceActions.SEEK_MEDIA
import com.hifnawy.quran.shared.model.Constants.MediaServiceActions.SKIP_TO_NEXT_MEDIA
import com.hifnawy.quran.shared.model.Constants.MediaServiceActions.SKIP_TO_PREVIOUS_MEDIA
import com.hifnawy.quran.shared.model.Constants.MediaServiceActions.STOP_MEDIA
import com.hifnawy.quran.shared.model.Constants.MediaServiceActions.TOGGLE_MEDIA
import com.hifnawy.quran.shared.model.Constants.ServiceUpdates
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.storage.SharedPreferencesManager
import com.hifnawy.quran.shared.tools.ImageUtils.drawTextOn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.Timer
import java.util.TimerTask
import com.google.android.exoplayer2.MediaItem as ExoPlayerMediaItem

private val TAG = MediaService::class.simpleName

class MediaService : MediaBrowserServiceCompat(), Player.Listener {

    companion object {

        var isMediaPlaying = false
    }

    private enum class MediaSessionState {
        PLAYING,
        PAUSED,
        SKIPPING_TO_NEXT,
        SKIPPING_TO_PREVIOUS,
        BUFFERING,
        CONNECTING,
        STOPPED
    }

    private enum class MediaState {
        ROOT,
        RECITER_BROWSE,
        CHAPTER_BROWSE
    }

    private val audioAttributes by lazy {
        AudioAttributes.Builder()
            .setContentType(C.CONTENT_TYPE_SPEECH)
            .setUsage(C.USAGE_MEDIA)
            .build()
    }
    private val exoPlayer by lazy {
        ExoPlayer.Builder(this)
            .build()
            .apply {
                setAudioAttributes(this@MediaService.audioAttributes, true)
                setHandleAudioBecomingNoisy(true)
            }
    }
    private val notificationManager by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    private lateinit var playbackMonitorTimer: Timer
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var sharedPrefsManager: SharedPreferencesManager
    private val mediaManager: MediaManager by lazy { MediaManager.getInstance(this) }
    private var reciterDrawables = listOf<Bitmap>()
    private var currentReciter: Reciter? = null
    private var currentChapter: Chapter? = null
    private var currentChapterPosition: Long = -1L
    private var isMediaReady = false

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "$TAG service started!!!")

        sharedPrefsManager = SharedPreferencesManager(this).apply {
            currentChapterPosition = lastChapterPosition
        }

        mediaSession = MediaSessionCompat(this, "QuranMediaService").apply {
            this@MediaService.sessionToken = sessionToken
            setCallback(MediaSessionCallback(this@MediaService))
        }

        mediaSession.isActive = true

        with(mediaManager) {
            onMediaReady = ::updateMediaSession
            onSingleDownloadProgressUpdate = ::processSingleDownloadProgress
            onBulkDownloadProgressUpdate = ::processBulkDownloadProgress
            onBulkDownloadSucceed = ::processBulkDownloadSucceed
            onBulkDownloadFail = ::processBulkDownloadFailure
        }

        exoPlayer.addListener(this)

        Firebase.crashlytics.setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        if (intent.action == null) return START_NOT_STICKY
        val reciter = intent.getTypedSerializable<Reciter>(IntentDataKeys.RECITER.name)
        val chapter = intent.getTypedSerializable<Chapter>(IntentDataKeys.CHAPTER.name)

        mediaManager.whenReady { _, _ ->
            val chapterPosition =
                    intent.getLongExtra(IntentDataKeys.CHAPTER_POSITION.name, -1L)

            when (intent.action) {
                DOWNLOAD_CHAPTERS.name        -> {
                    if (reciter == null) return@whenReady
                    showDownloadForegroundNotification(reciter)
                    mediaManager.downloadChapters(reciter)
                }

                CANCEL_DOWNLOADS.name -> {
                    MediaManager.cancelPendingDownloads()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }

                PLAY_MEDIA.name               -> {
                    if (reciter == null || chapter == null) return@whenReady
                    showMediaForegroundNotification(reciter, chapter)
                    prepareMedia(reciter, chapter, chapterPosition)
                }

                PAUSE_MEDIA.name              -> pauseMedia()
                TOGGLE_MEDIA.name             -> toggleMedia()
                STOP_MEDIA.name               -> stopSelf()
                SKIP_TO_NEXT_MEDIA.name       -> skipToNextChapter()
                SKIP_TO_PREVIOUS_MEDIA.name   -> skipToPreviousChapter()
                SEEK_MEDIA.name               -> seekChapterToPosition(chapterPosition)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        // release media session
        mediaSession.apply {
            isActive = false
            release()
        }
        // release exoplayer
        exoPlayer.apply {
            removeListener(this@MediaService)
            release()
        }
        // stop mediaManager
        mediaManager.apply {
            stopLifecycle()
            cancelPendingDownloads()
        }
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot =
            BrowserRoot(MediaState.ROOT.name, null)

    override fun onLoadChildren(
            parentId: String,
            result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        if (parentId == MediaState.ROOT.name) {
            result.sendResult(
                    listOf(
                            createBrowsableMediaItem(
                                    -1,
                                    MediaState.RECITER_BROWSE.name
                            )
                    ).toMutableList()
            )
        } else {
            var mediaItems: MutableList<MediaBrowserCompat.MediaItem>
            val mediaState = when {
                parentId == MediaState.RECITER_BROWSE.name -> MediaState.RECITER_BROWSE
                parentId.startsWith("reciter_")            -> MediaState.CHAPTER_BROWSE
                else                                       -> MediaState.RECITER_BROWSE
            }
            val resultSent = when (mediaState) {
                MediaState.RECITER_BROWSE -> {
                    mediaManager.whenRecitersReady { reciters ->
                        reciterDrawables.ifEmpty {
                            reciterDrawables = reciters.mapIndexed { index, reciter ->
                                (if ((index % 2) == 0) R.drawable.reciter_background_2
                                else R.drawable.reciter_background_3).drawTextOn(
                                        context = this@MediaService,
                                        text = reciter.name_ar,
                                        subText = if (reciter.style != null) "(${reciter.style.style})" else "",
                                        fontFace = R.font.aref_ruqaa,
                                        fontSize = 60.dp.toFloat(),
                                        fontMargin = 0
                                )
                            }
                        }
                        mediaItems = reciters.mapIndexed { reciterIndex, reciter ->
                            createBrowsableMediaItem(
                                    reciterIndex,
                                    "reciter_${reciter.id}"
                            )
                        }.toMutableList()

                        result.sendResult(mediaItems)
                    }
                }

                MediaState.CHAPTER_BROWSE -> {
                    val reciterID = parentId.replace("reciter_", "").toInt()
                    mediaManager.whenReady(reciterID) { reciters, chapters, chaptersAudioFiles ->
                        reciters.find { reciter -> reciter.id == reciterID }?.let { reciter ->
                            mediaItems = chapters.map { chapter ->
                                createMediaItem("chapter_${chapter.id}",
                                                reciter,
                                                chapter,
                                                chaptersAudioFiles.find { chapterAudioFile ->
                                                    chapterAudioFile.chapter_id == chapter.id
                                                })
                            }.toMutableList()

                            result.sendResult(mediaItems)
                        }
                    }
                }

                else                      -> false
            }

            if (!resultSent) result.detach()
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        setMediaPlaybackState(if (isPlaying) MediaSessionState.PLAYING else MediaSessionState.PAUSED)
    }

    override fun onPlaybackStateChanged(state: Int) {
        when (state) {
            Player.STATE_IDLE      -> Unit
            Player.STATE_BUFFERING -> Unit
            Player.STATE_READY     -> {
                setMediaPlaybackState(MediaSessionState.PLAYING)
                startPlaybackMonitoring()
            }

            Player.STATE_ENDED     -> {
                currentChapterPosition = -1L
                skipToNextChapter()
            }
        }
    }

    private fun startPlaybackMonitoring() {
        stopPlaybackMonitoring()
        val mainCoroutineScope = CoroutineScope(Dispatchers.Main)
        val timerTask = object : TimerTask() {
            override fun run() {
                mainCoroutineScope.launch {
                    isMediaPlaying = exoPlayer.isPlaying
                    // currentChapterPosition = exoPlayer.currentPosition
                    sharedPrefsManager.lastChapterPosition = exoPlayer.currentPosition
                    sharedPrefsManager.lastChapterDuration = exoPlayer.duration

                    updateMediaPlayer(
                            currentReciter!!,
                            currentChapter!!,
                            exoPlayer.duration,
                            exoPlayer.currentPosition
                    )

                    updateWidget(currentReciter!!, currentChapter!!)
                }
            }
        }
        playbackMonitorTimer = Timer()
        playbackMonitorTimer.scheduleAtFixedRate(timerTask, 0, 500)
    }

    private fun stopPlaybackMonitoring() {
        if (this::playbackMonitorTimer.isInitialized) {
            playbackMonitorTimer.cancel()
            playbackMonitorTimer.purge()
        }
    }

    private fun createBrowsableMediaItem(
            reciterIndex: Int,
            mediaId: String,
    ): MediaBrowserCompat.MediaItem {
        val extras = Bundle()
        val mediaDescriptionBuilder = MediaDescriptionCompat.Builder()
        mediaDescriptionBuilder.setMediaId(mediaId)

        if (reciterIndex in reciterDrawables.indices) {
            mediaDescriptionBuilder.setIconBitmap(reciterDrawables[reciterIndex])
        }
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
        @SuppressLint("DiscouragedApi")
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

    private fun setMediaPlaybackState(state: MediaSessionState) {
        lateinit var playbackState: PlaybackStateCompat
        when (state) {
            MediaSessionState.PLAYING              -> playbackState =
                    PlaybackStateCompat.Builder().setActions(
                            PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY or
                                    PlaybackStateCompat.ACTION_REWIND or PlaybackStateCompat.ACTION_FAST_FORWARD or
                                    PlaybackStateCompat.ACTION_SET_REPEAT_MODE or PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE or
                                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                                    PlaybackStateCompat.ACTION_SEEK_TO
                    ).setState(PlaybackStateCompat.STATE_PLAYING, exoPlayer.currentPosition, 1f).build()

            MediaSessionState.PAUSED               -> playbackState = PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY)
                .setState(PlaybackStateCompat.STATE_PAUSED, exoPlayer.currentPosition, 1f).build()

            MediaSessionState.SKIPPING_TO_NEXT     -> playbackState =
                    PlaybackStateCompat.Builder().setActions(PlaybackStateCompat.ACTION_STOP)
                        .setState(PlaybackStateCompat.STATE_SKIPPING_TO_NEXT, 0, 1f).build()

            MediaSessionState.SKIPPING_TO_PREVIOUS -> playbackState =
                    PlaybackStateCompat.Builder().setActions(PlaybackStateCompat.ACTION_STOP)
                        .setState(PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS, 0, 1f).build()

            MediaSessionState.BUFFERING            -> playbackState =
                    PlaybackStateCompat.Builder()
                        .setActions(
                                PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                        )
                        .setState(PlaybackStateCompat.STATE_BUFFERING, 0, 1f).build()

            MediaSessionState.CONNECTING           -> playbackState =
                    PlaybackStateCompat.Builder()
                        .setActions(
                                PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                        )
                        .setState(PlaybackStateCompat.STATE_CONNECTING, 0, 1f).build()

            MediaSessionState.STOPPED              -> playbackState =
                    PlaybackStateCompat.Builder()
                        .setActions(PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID)
                        .setState(PlaybackStateCompat.STATE_STOPPED, 0, 1f).build()
        }

        mediaSession.setPlaybackState(playbackState)
    }

    @SuppressLint("DiscouragedApi")
    private fun updateMediaSession(
            reciter: Reciter,
            chapter: Chapter,
            chapterAudioFile: File? = null,
            chapterDrawable: Drawable? = null
    ) {
        currentReciter = reciter
        currentChapter = chapter
        val chapterDrawableId = resources.getIdentifier(
                "chapter_${currentChapter!!.id.toString().padStart(3, '0')}",
                "drawable",
                packageName
        )
        val chapterDrawableBitmap = if (chapterDrawable == null) (AppCompatResources.getDrawable(
                this,
                chapterDrawableId
        ) as BitmapDrawable).bitmap
        else (chapterDrawable as BitmapDrawable).bitmap
        val durationMs = if (chapterAudioFile != null) {
            Log.d(TAG, "playing ${chapterAudioFile.name} from $currentChapterPosition...")
            val mediaMetadataRetriever = MediaMetadataRetriever().apply {
                setDataSource(this@MediaService, chapterAudioFile.toUri())
            }
            mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLong() ?: -1L
        } else {
            -1L
        }

        showMediaNotification(chapter)

        mediaSession.setMetadata(
                MediaMetadataCompat.Builder()
                    .putText(
                            MediaMetadataCompat.METADATA_KEY_TITLE,
                            if (chapterAudioFile == null) getString(
                                    R.string.loading_chapter,
                                    currentChapter!!.name_arabic
                            )
                            else chapter.name_arabic
                    )
                    .putText(
                            MediaMetadataCompat.METADATA_KEY_ARTIST,
                            "${reciter.name_ar} ${if (reciter.style != null) "(${reciter.style.style})" else ""}"
                    )
                    .putText(MediaMetadataCompat.METADATA_KEY_GENRE, getString(R.string.quran))
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, chapterDrawableBitmap).build()
        )

        if (chapterAudioFile != null) playMedia(chapter, chapterAudioFile)
    }

    private fun updateMediaPlayer(
            reciter: Reciter, chapter: Chapter, duration: Long = -1L, currentPosition: Long = -1L
    ) {
        sendBroadcast(Intent(getString(R.string.quran_media_service_updates)).apply {
            addCategory(ServiceUpdates.MEDIA_PLAYBACK_UPDATES.name)

            putExtra(IntentDataKeys.RECITER.name, reciter)
            putExtra(IntentDataKeys.CHAPTER.name, chapter)
            putExtra(IntentDataKeys.CHAPTER_DURATION.name, duration)
            putExtra(IntentDataKeys.CHAPTER_POSITION.name, currentPosition)
            putExtra(IntentDataKeys.IS_MEDIA_PLAYING.name, isMediaPlaying)
        })
    }

    private fun updateWidget(reciter: Reciter, chapter: Chapter) {
        Intent(this, Constants.NowPlayingClass).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE

            putExtra(IntentDataKeys.RECITER.name, reciter)
            putExtra(IntentDataKeys.CHAPTER.name, chapter)
            putExtra(IntentDataKeys.CHAPTER_POSITION.name, exoPlayer.currentPosition)
            putExtra(IntentDataKeys.IS_MEDIA_PLAYING.name, isMediaPlaying)
            val widgetIds = AppWidgetManager.getInstance(this@MediaService).getAppWidgetIds(
                    ComponentName(this@MediaService, Constants.NowPlayingClass)
            )
            if ((widgetIds != null) && widgetIds.isNotEmpty()) {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds)
                sendBroadcast(this)
            }
        }
    }

    private fun processSingleDownloadProgress(
            reciter: Reciter,
            chapter: Chapter,
            downloadStatus: DownloadStatus,
            bytesDownloaded: Long,
            audioFileSize: Int,
            progress: Float,
            @Suppress("UNUSED_PARAMETER")
            chapterAudioFile: File?
    ) {
        Log.d(
                TAG,
                "$downloadStatus ${chapter.name_simple} $bytesDownloaded / $audioFileSize (${progress}%)"
        )

        stopPlaybackMonitoring()
        isMediaPlaying = false
        updateMediaPlayer(reciter, chapter)
        updateWidget(reciter, chapter)

        when (downloadStatus) {
            DownloadStatus.STARTING_DOWNLOAD  -> {
                isMediaReady = false
                currentReciter = reciter
                currentChapter = chapter
                setMediaPlaybackState(MediaSessionState.BUFFERING)

                updateMediaSession(reciter, chapter)
            }

            DownloadStatus.FILE_EXISTS,
            DownloadStatus.FINISHED_DOWNLOAD  -> {
                setMediaPlaybackState(MediaSessionState.PLAYING)
                showMediaNotification(chapter)
            }

            DownloadStatus.DOWNLOADING,
            DownloadStatus.DOWNLOAD_ERROR,
            DownloadStatus.DOWNLOAD_INTERRUPTED,
            DownloadStatus.CONNECTION_FAILURE -> Unit
        }
    }

    private fun processBulkDownloadProgress(
            reciter: Reciter,
            currentChapter: Chapter?,
            currentChapterIndex: Int,
            currentChapterDownloadStatus: DownloadStatus,
            currentChapterBytesDownloaded: Long,
            currentChapterFileSize: Int,
            currentChapterProgress: Float,
            allChaptersProgress: Float
    ) {
        processBulkDownload(
                ServiceUpdates.ALL_CHAPTERS_DOWNLOAD_PROGRESS,
                reciter,
                currentChapter,
                currentChapterIndex,
                currentChapterDownloadStatus,
                currentChapterBytesDownloaded,
                currentChapterFileSize,
                currentChapterProgress,
                allChaptersProgress
        )
    }

    private fun processBulkDownloadSucceed(
            reciter: Reciter,
            currentChapter: Chapter?,
            currentChapterIndex: Int,
            currentChapterDownloadStatus: DownloadStatus,
            currentChapterBytesDownloaded: Long,
            currentChapterFileSize: Int,
            currentChapterProgress: Float,
            allChaptersProgress: Float
    ) {
        processBulkDownload(
                ServiceUpdates.ALL_CHAPTERS_DOWNLOAD_SUCCEED,
                reciter,
                currentChapter,
                currentChapterIndex,
                currentChapterDownloadStatus,
                currentChapterBytesDownloaded,
                currentChapterFileSize,
                currentChapterProgress,
                allChaptersProgress
        )
    }

    private fun processBulkDownloadFailure(
            reciter: Reciter,
            currentChapter: Chapter?,
            currentChapterIndex: Int,
            currentChapterDownloadStatus: DownloadStatus,
            currentChapterBytesDownloaded: Long,
            currentChapterFileSize: Int,
            currentChapterProgress: Float,
            allChaptersProgress: Float
    ) {
        processBulkDownload(
                ServiceUpdates.ALL_CHAPTERS_DOWNLOAD_FAILED,
                reciter,
                currentChapter,
                currentChapterIndex,
                currentChapterDownloadStatus,
                currentChapterBytesDownloaded,
                currentChapterFileSize,
                currentChapterProgress,
                allChaptersProgress
        )
    }

    private fun processBulkDownload(
            status: ServiceUpdates, reciter: Reciter,
            currentChapter: Chapter?,
            currentChapterIndex: Int,
            currentChapterDownloadStatus: DownloadStatus,
            currentChapterBytesDownloaded: Long,
            currentChapterFileSize: Int,
            currentChapterProgress: Float,
            allChaptersProgress: Float
    ) {
        sendBroadcast(Intent(getString(R.string.quran_media_service_updates)).apply {
            addCategory(status.name)

            putExtra(IntentDataKeys.RECITER.name, reciter)
            putExtra(IntentDataKeys.CHAPTER.name, currentChapter)
            putExtra(IntentDataKeys.CHAPTER_INDEX.name, currentChapterIndex)
            putExtra(IntentDataKeys.CHAPTER_DOWNLOAD_STATUS.name, currentChapterDownloadStatus)
            putExtra(IntentDataKeys.CHAPTER_DOWNLOADED_BYTES.name, currentChapterBytesDownloaded)
            putExtra(IntentDataKeys.CHAPTER_DOWNLOAD_SIZE.name, currentChapterFileSize)
            putExtra(IntentDataKeys.CHAPTER_DOWNLOAD_PROGRESS.name, currentChapterProgress)
            putExtra(IntentDataKeys.CHAPTERS_DOWNLOAD_PROGRESS.name, allChaptersProgress)
        })
    }

    private fun showDownloadForegroundNotification(reciter: Reciter) {
        val notification = NotificationCompat.Builder(
                this,
                getString(R.string.quran_download_notification_name)
        )
            .setSilent(true)
            .setOngoing(true)
            .setPriority(NotificationManager.IMPORTANCE_MAX)
            .setContentInfo(getString(R.string.app_name))
            .setSubText("${reciter.name_ar} \\ ${getString(R.string.chapter_name)}")
            .setProgress(100, 0, false)
            .setSmallIcon(R.drawable.quran_icon_monochrome_black_64)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        val channel = NotificationChannel(
                getString(R.string.quran_download_notification_name),
                getString(R.string.quran_download_notification_name),
                NotificationManager.IMPORTANCE_HIGH
        )
        val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)

        startForeground(
                R.integer.quran_chapter_download_notification_channel_id,
                notification
        )
    }

    private fun showMediaForegroundNotification(reciter: Reciter, chapter: Chapter) {
        val notificationChannel = NotificationChannel(
                getString(R.string.quran_recitation_notification_name),
                getString(R.string.quran_recitation_notification_name),
                NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = chapter.name_arabic
        }
        val notification = NotificationCompat.Builder(
                this@MediaService,
                getString(R.string.quran_recitation_notification_name)
        )
            .setSilent(true)
            .setPriority(NotificationManager.IMPORTANCE_MAX)
            .setSmallIcon(R.drawable.quran_icon_monochrome_black_64)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(chapter.name_arabic)
            .setSubText(reciter.name_ar)
            .build()

        notificationManager.createNotificationChannel(notificationChannel)
        startForeground(
                R.integer.quran_chapter_recitation_notification_channel_id,
                notification
        )
    }

    private fun showMediaNotification(chapter: Chapter) {
        @SuppressLint("DiscouragedApi")
        val chapterDrawableId = resources.getIdentifier(
                "chapter_${chapter.id.toString().padStart(3, '0')}", "drawable", packageName
        )
        val chapterDrawable = AppCompatResources.getDrawable(this, chapterDrawableId)
        val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, Constants.MainActivityClass).apply {
                    addCategory(Constants.MAIN_ACTIVITY_INTENT_CATEGORY)
                    putExtra(IntentDataKeys.RECITER.name, currentReciter)
                    putExtra(IntentDataKeys.CHAPTER.name, chapter)
                }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification =
                NotificationCompat.Builder(this, getString(R.string.quran_recitation_notification_name))
                    .setSilent(true)
                    .setStyle(
                            androidx.media.app.NotificationCompat.MediaStyle()
                                .setMediaSession(mediaSession.sessionToken)
                    )
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setContentTitle(chapter.name_arabic)
                    .setContentText(currentReciter!!.name_ar)
                    .setSmallIcon(R.drawable.quran_icon_monochrome_black_64)
                    .setLargeIcon((chapterDrawable as BitmapDrawable).bitmap)
                    .setContentIntent(pendingIntent)
                    .build()
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
    }

    private fun processChapter(reciter: Reciter, chapter: Chapter, chapterPosition: Long) {
        if (exoPlayer.isPlaying) {
            exoPlayer.stop()
        }

        setMediaPlaybackState(MediaSessionState.BUFFERING)

        updateMediaSession(reciter, chapter)
        updateMediaPlayer(reciter, chapter, chapterPosition)
        updateWidget(reciter, chapter)
        mediaManager.processChapter(reciter, chapter)

        showMediaNotification(chapter)
    }

    private fun playMedia(chapter: Chapter, chapterAudioFile: File) {
        isMediaReady = true

        showMediaNotification(chapter)

        if (exoPlayer.isPlaying || (currentChapterPosition == 0L)) {
            exoPlayer.stop()
        }
        val mediaItem = ExoPlayerMediaItem.fromUri(chapterAudioFile.toUri())
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        Log.d(TAG, "seeking to $currentChapterPosition...")
        exoPlayer.seekTo(currentChapterPosition)

        setMediaPlaybackState(MediaSessionState.PLAYING)
    }

    fun prepareMedia(reciter: Reciter? = null, chapter: Chapter? = null, chapterPosition: Long = 0L) {
        if ((reciter == null) || (chapter == null)) return
        Log.d("ExoPlayer_Audio_Player", "Preparing Chapter ${chapter.name_simple}...")

        currentChapterPosition = chapterPosition
        sharedPrefsManager.lastReciter = reciter
        sharedPrefsManager.lastChapter = chapter
        sharedPrefsManager.lastChapterPosition = chapterPosition

        isMediaPlaying = false

        if ((currentReciter == null) || (currentChapter == null)) {
            currentReciter = reciter
            currentChapter = chapter

            processChapter(reciter, chapter, chapterPosition)
            return
        }

        if ((reciter.id != currentReciter?.id) || (chapter.id != currentChapter?.id)) {
            currentReciter = reciter
            currentChapter = chapter
            stopPlaybackMonitoring()

            processChapter(reciter, chapter, chapterPosition)
            return
        }

        if (!isMediaReady) {
            processChapter(reciter, chapter, chapterPosition)
            return
        }

        isMediaReady = true

        showMediaNotification(chapter)
        if (!exoPlayer.isPlaying) exoPlayer.play()
        setMediaPlaybackState(MediaSessionState.PLAYING)
    }

    private fun toggleMedia() {
        if (isMediaPlaying) {
            pauseMedia()
        } else {
            resumeMedia()
        }
    }

    fun pauseMedia() {
        exoPlayer.pause()
        setMediaPlaybackState(MediaSessionState.PAUSED)
    }

    fun resumeMedia() {
        if (isMediaReady) {
            exoPlayer.play()
            setMediaPlaybackState(MediaSessionState.PLAYING)
        } else {
            prepareMedia(
                    currentReciter ?: sharedPrefsManager.lastReciter,
                    currentChapter ?: sharedPrefsManager.lastChapter,
                    if (currentReciter != null) currentChapterPosition
                    else sharedPrefsManager.lastChapterPosition
            )
        }
    }

    fun stop() {
        if (exoPlayer.isPlaying) {
            exoPlayer.stop()
        } else {
            isMediaReady = false
            updateMediaSession(currentReciter!!, currentChapter!!)
        }

        mediaManager.cancelPendingDownloads()
        setMediaPlaybackState(MediaSessionState.STOPPED)
    }

    fun skipToNextChapter() {
        exoPlayer.stop()
        currentChapterPosition = -1L
        stopPlaybackMonitoring()
        mediaManager.processNextChapter()
    }

    fun skipToPreviousChapter() {
        exoPlayer.stop()
        currentChapterPosition = -1L
        stopPlaybackMonitoring()
        mediaManager.processPreviousChapter()
    }

    fun seekChapterToPosition(chapterPosition: Long) {
        if (chapterPosition != -1L) {
            Log.d("ExoPlayer_Audio_Player", "Seeking to $chapterPosition...")
            exoPlayer.seekTo(chapterPosition)
            setMediaPlaybackState(MediaSessionState.PLAYING)
        }
    }
}

