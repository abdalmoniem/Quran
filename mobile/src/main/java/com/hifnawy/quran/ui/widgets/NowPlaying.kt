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

/**
 * Implementation of App Widget functionality.
 */
class NowPlaying : AppWidgetProvider() {

    companion object {

        private var currentReciter: Reciter? = null
        private var currentChapter: Chapter? = null
        private var currentChapterPosition: Long = -1L
        private var isMediaPlaying: Boolean = false
    }

    enum class WidgetActions(val value: Int) { PLAY_PAUSE(0), NEXT(1), PREVIOUS(2), OPEN_MEDIA_PLAYER(3)
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
            WidgetActions.PLAY_PAUSE.name -> {
                val state = if (isMediaPlaying) Constants.Actions.PAUSE_MEDIA
                else Constants.Actions.PLAY_MEDIA

                Log.d(NowPlaying::class.simpleName, "new state: $state")
                changeMediaState(
                        context, state
                )
            }

            WidgetActions.NEXT.name -> changeMediaState(context, Constants.Actions.SKIP_TO_NEXT_MEDIA)
            WidgetActions.PREVIOUS.name -> changeMediaState(
                    context,
                    Constants.Actions.SKIP_TO_PREVIOUS_MEDIA
            )

            WidgetActions.OPEN_MEDIA_PLAYER.name -> openMediaPlayer(context)
            AppWidgetManager.ACTION_APPWIDGET_UPDATE -> {
                val reciter = intent.getTypedSerializable<Reciter>(Constants.IntentDataKeys.RECITER.name)
                val chapter = intent.getTypedSerializable<Chapter>(Constants.IntentDataKeys.CHAPTER.name)
                currentChapterPosition =
                    intent.getLongExtra(Constants.IntentDataKeys.CHAPTER_POSITION.name, 0L)
                isMediaPlaying =
                    intent.getBooleanExtra(Constants.IntentDataKeys.IS_MEDIA_PLAYING.name, false)

                if ((reciter == null) && (chapter == null)) return

                currentReciter = reciter
                currentChapter = chapter

                setClickListeners(context)
                updateUI(context)
            }

            else -> Unit
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
            if (currentReciter == null) currentReciter = lastReciter
            if (currentChapter == null) currentChapter = lastChapter
        }
        @SuppressLint("DiscouragedApi") val chapterImageDrawableId = context.resources.getIdentifier(
                "chapter_${currentChapter?.id.toString().padStart(3, '0')}",
                "drawable",
                context.packageName
        )
        val chapterImageBlurred = HokoBlur.with(context)
            .scheme(HokoBlur.SCHEME_NATIVE) // different implementation, RenderScript、OpenGL、Native(default) and Java
            .mode(HokoBlur.MODE_GAUSSIAN) // blur algorithms，Gaussian、Stack(default) and Box
            .radius(3) // blur radius，max=25，default=5
            .sampleFactor(2.0f).processor().blur(
                    (AppCompatResources.getDrawable(
                            context, chapterImageDrawableId
                    ) as BitmapDrawable).bitmap
            )

        with(views) {
            setTextViewText(R.id.reciter_name, currentReciter?.name_ar)
            setTextViewText(R.id.chapter_name, currentChapter?.name_arabic)
            setImageViewBitmap(R.id.background_image, chapterImageBlurred)
            setImageViewResource(R.id.chapter_image, chapterImageDrawableId)
            setImageViewResource(
                    R.id.media_playback,
                    if (isMediaPlaying) com.hifnawy.quran.shared.R.drawable.media_pause_white
                    else com.hifnawy.quran.shared.R.drawable.media_play_white
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
            if (currentReciter == null) currentReciter = lastReciter
            if (currentChapter == null) currentChapter = lastChapter
            if (currentChapterPosition == -1L) currentChapterPosition = lastChapterPosition
        }

        startActivity(context, Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            addCategory(NowPlaying::class.simpleName)

            putExtra(Constants.IntentDataKeys.RECITER.name, currentReciter)
            putExtra(Constants.IntentDataKeys.CHAPTER.name, currentChapter)
            putExtra(Constants.IntentDataKeys.CHAPTER_POSITION.name, currentChapterPosition)
        }, null)
    }

    private fun changeMediaState(context: Context, action: Constants.Actions) {
        views.setViewVisibility(R.id.chapter_loading, View.VISIBLE)
        views.setViewVisibility(R.id.media_playback, View.INVISIBLE)
        pushUIUpdates(context)

        sharedPrefsManager.apply {
            if (currentReciter == null) currentReciter = lastReciter
            if (currentChapter == null) currentChapter = lastChapter
            if (currentChapterPosition == -1L) currentChapterPosition = lastChapterPosition
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