package com.hifnawy.quran.ui.widgets

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat.startActivity
import com.hifnawy.quran.R
import com.hifnawy.quran.shared.extensions.SerializableExt.Companion.getTypedSerializable
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.Constants
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.services.MediaService
import com.hifnawy.quran.shared.storage.SharedPreferencesManager
import com.hifnawy.quran.ui.activities.MainActivity
import com.hoko.blur.HokoBlur
import com.hifnawy.quran.shared.R as sharedR

/**
 * Implementation of App Widget functionality.
 */
private val TAG = NowPlaying::class.simpleName

class NowPlaying : AppWidgetProvider() {

    companion object {

        private lateinit var currentReciter: Reciter
        private lateinit var currentChapter: Chapter
        private var currentChapterPosition: Long = -1L
        private var isMediaPlaying: Boolean = false
        private val isCurrentReciterInitialized
            get() = ::currentReciter.isInitialized
        private val isCurrentChapterInitialized
            get() = ::currentChapter.isInitialized
    }

    enum class WidgetActions(val value: Int) {
        PLAY_PAUSE(0),
        NEXT(1),
        PREVIOUS(2),
        OPEN_MEDIA_PLAYER(3)
    }

    private val views by lazy { RemoteViews(widgetContext.packageName, R.layout.now_playing) }
    private val sharedPrefsManager: SharedPreferencesManager by lazy {
        SharedPreferencesManager(
                widgetContext
        )
    }
    private lateinit var widgetContext: Context

    override fun onEnabled(context: Context?) {
        super.onEnabled(context)

        if (context == null) return

        widgetContext = context

        setClickListeners(context)
        updateUI(context)
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        super.onReceive(context, intent)

        if (context == null) return
        if (intent == null) return

        widgetContext = context

        when (intent.action) {
            WidgetActions.PLAY_PAUSE.name            -> changeMediaState(
                    context,
                    if (isMediaPlaying) Constants.MediaServiceActions.PAUSE_MEDIA
                    else Constants.MediaServiceActions.PLAY_MEDIA
            )

            WidgetActions.NEXT.name                  -> changeMediaState(
                    context,
                    Constants.MediaServiceActions.SKIP_TO_NEXT_MEDIA
            )

            WidgetActions.PREVIOUS.name              -> changeMediaState(
                    context,
                    Constants.MediaServiceActions.SKIP_TO_PREVIOUS_MEDIA
            )

            WidgetActions.OPEN_MEDIA_PLAYER.name     -> openMediaPlayer(context)
            AppWidgetManager.ACTION_APPWIDGET_UPDATE -> {
                val reciter = intent.getTypedSerializable<Reciter>(Constants.IntentDataKeys.RECITER.name)
                val chapter = intent.getTypedSerializable<Chapter>(Constants.IntentDataKeys.CHAPTER.name)
                currentChapterPosition =
                        intent.getLongExtra(Constants.IntentDataKeys.CHAPTER_POSITION.name, 0L)
                isMediaPlaying =
                        intent.getBooleanExtra(Constants.IntentDataKeys.IS_MEDIA_PLAYING.name, false)

                if (reciter == null) return
                if (chapter == null) return

                currentReciter = reciter
                currentChapter = chapter

                setClickListeners(context)
                updateUI(context)
            }

            else                                     -> Unit
        }
    }

    private fun pushUIUpdates(context: Context) {
        with(AppWidgetManager.getInstance(context)) {
            val widgetComponentName = ComponentName(context, NowPlaying::class.java)
            val widgetIds = getAppWidgetIds(widgetComponentName)
            updateAppWidget(widgetIds, views)
        }
    }

    private fun updateUI(context: Context) {
        sharedPrefsManager.apply {
            if (!isCurrentReciterInitialized) currentReciter = lastReciter ?: return@apply
            if (!isCurrentChapterInitialized) currentChapter = lastChapter ?: return@apply
        }

        currentChapter.let { chapter ->
            @SuppressLint("DiscouragedApi")
            val chapterImageDrawableId = context.resources.getIdentifier(
                    "chapter_${chapter.id.toString().padStart(3, '0')}",
                    "drawable",
                    context.packageName
            )
            val chapterDrawable = AppCompatResources.getDrawable(context, chapterImageDrawableId)
            val chapterDrawableBitmap = (chapterDrawable as BitmapDrawable).bitmap
            val chapterImageBlurred = HokoBlur.with(context)
                .scheme(HokoBlur.SCHEME_NATIVE) // different implementation, RenderScript、OpenGL、Native(default) and Java
                .mode(HokoBlur.MODE_GAUSSIAN) // blur algorithms，Gaussian、Stack(default) and Box
                .radius(3) // blur radius，max=25，default=5
                .sampleFactor(2.0f)
                .processor()
                .blur(chapterDrawableBitmap)

            views.setImageViewBitmap(R.id.background_image, chapterImageBlurred)
            views.setImageViewResource(R.id.chapter_image, chapterImageDrawableId)
        }

        with(views) {
            setTextViewText(R.id.reciter_name, currentReciter.nameArabic)
            setTextViewText(R.id.chapter_name, currentChapter.nameArabic)
            setImageViewResource(
                    R.id.media_playback,
                    if (isMediaPlaying) sharedR.drawable.media_pause_white
                    else sharedR.drawable.media_play_white
            )
            setViewVisibility(R.id.chapter_loading, View.INVISIBLE)
            setViewVisibility(R.id.media_playback, View.VISIBLE)
        }

        pushUIUpdates(context)
    }

    private fun setClickListeners(context: Context) {
        val chapterImagePendingIntent = PendingIntent.getBroadcast(
                context,
                WidgetActions.OPEN_MEDIA_PLAYER.value,
                Intent(context, NowPlaying::class.java).apply {
                    action = WidgetActions.OPEN_MEDIA_PLAYER.name
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val chapterPlayPausePendingIntent = PendingIntent.getBroadcast(
                context, WidgetActions.PLAY_PAUSE.value, Intent(context, NowPlaying::class.java).apply {
            action = WidgetActions.PLAY_PAUSE.name
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val chapterNextPendingIntent = PendingIntent.getBroadcast(
                context, WidgetActions.NEXT.value, Intent(context, NowPlaying::class.java).apply {
            action = WidgetActions.NEXT.name
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val chapterPreviousPendingIntent = PendingIntent.getBroadcast(
                context, WidgetActions.PREVIOUS.value, Intent(context, NowPlaying::class.java).apply {
            action = WidgetActions.PREVIOUS.name
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        with(views) {
            setOnClickPendingIntent(R.id.chapter_image, chapterImagePendingIntent)
            setOnClickPendingIntent(R.id.media_playback, chapterPlayPausePendingIntent)
            setOnClickPendingIntent(R.id.chapter_next, chapterNextPendingIntent)
            setOnClickPendingIntent(
                    R.id.chapter_previous, chapterPreviousPendingIntent
            )
        }
    }

    private fun openMediaPlayer(context: Context) {
        sharedPrefsManager.apply {
            if (currentChapterPosition == -1L) currentChapterPosition = lastChapterPosition
            if (!isCurrentReciterInitialized) currentReciter = lastReciter ?: return@apply
            if (!isCurrentChapterInitialized) currentChapter = lastChapter ?: return@apply
        }

        startActivity(context, Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            addCategory(TAG)

            putExtra(Constants.IntentDataKeys.RECITER.name, currentReciter)
            putExtra(Constants.IntentDataKeys.CHAPTER.name, currentChapter)
            putExtra(Constants.IntentDataKeys.CHAPTER_POSITION.name, currentChapterPosition)
        }, null)
    }

    private fun changeMediaState(context: Context, action: Constants.MediaServiceActions) {
        Log.d(TAG, "changing widget media state to $action")

        views.setViewVisibility(R.id.chapter_loading, View.VISIBLE)
        views.setViewVisibility(R.id.media_playback, View.INVISIBLE)
        pushUIUpdates(context)

        sharedPrefsManager.apply {
            if (currentChapterPosition == -1L) currentChapterPosition = lastChapterPosition
            if (!isCurrentReciterInitialized) currentReciter = lastReciter ?: return@apply
            if (!isCurrentChapterInitialized) currentChapter = lastChapter ?: return@apply
        }

        context.startForegroundService(Intent(
                context, MediaService::class.java
        ).apply {
            this.action = action.name

            putExtra(Constants.IntentDataKeys.RECITER.name, currentReciter)
            putExtra(Constants.IntentDataKeys.CHAPTER.name, currentChapter)
            putExtra(Constants.IntentDataKeys.CHAPTER_POSITION.name, currentChapterPosition)
            putExtra(Constants.IntentDataKeys.IS_MEDIA_PLAYING.name, isMediaPlaying)
        })
    }
}