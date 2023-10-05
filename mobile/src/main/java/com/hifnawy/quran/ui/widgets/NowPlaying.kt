package com.hifnawy.quran.ui.widgets

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import android.widget.RemoteViews
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat.startActivity
import com.hifnawy.quran.R
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.Constants
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.services.MediaService
import com.hifnawy.quran.shared.tools.SharedPreferencesManager
import com.hifnawy.quran.shared.tools.Utilities.Companion.getTypedSerializable
import com.hifnawy.quran.ui.activities.MainActivity
import com.hoko.blur.HokoBlur


/**
 * Implementation of App Widget functionality.
 */

class NowPlaying : AppWidgetProvider() {
    private enum class WidgetActions {
        PLAY_PAUSE, NEXT, PREVIOUS, OPEN_MEDIA_PLAYER
    }

    private var currentReciter: Reciter? = null
    private var currentChapter: Chapter? = null
    private var context: Context? = null
    private var sharedPrefsManager: SharedPreferencesManager? = null
    private val views: RemoteViews by lazy { RemoteViews(context?.packageName, R.layout.now_playing) }

    override fun onReceive(context: Context?, intent: Intent?) {
        this.context = context

        intent?.run {
            val reciter = getTypedSerializable<Reciter>(Constants.IntentDataKeys.RECITER.name)
            val chapter = getTypedSerializable<Chapter>(Constants.IntentDataKeys.CHAPTER.name)

            if ((reciter != null) and (chapter != null)) {
                currentReciter = reciter
                currentChapter = chapter
            }

            if (sharedPrefsManager == null) {
                sharedPrefsManager = SharedPreferencesManager(context!!)
            }

            when (val mediaAction = action) {
                AppWidgetManager.ACTION_APPWIDGET_ENABLED -> {
                    sharedPrefsManager?.run {
                        currentReciter = lastReciter
                        currentChapter = lastChapter

                        updateUI(context!!)
                    }
                }

                WidgetActions.PLAY_PAUSE.name -> {
                    Log.d("Quran_Widget", "$mediaAction button is pressed")

                    MediaService.instance?.run {
                        if (isMediaPlaying) {
                            pauseMedia()
                        } else {
                            resumeMedia()
                        }
                    } ?: openMediaPlayer(context)
                }

                WidgetActions.NEXT.name -> {
                    Log.d("Quran_Widget", "$mediaAction button is pressed")

                    MediaService.instance?.run {
                        skipToNextChapter().apply {
                            currentReciter = reciter
                            currentChapter = chapter
                        }
                    }
                }

                WidgetActions.PREVIOUS.name -> {
                    Log.d("Quran_Widget", "$mediaAction button is pressed")

                    MediaService.instance?.run {
                        skipToPreviousChapter().apply {
                            currentReciter = reciter
                            currentChapter = chapter
                        }
                    }
                }

                WidgetActions.OPEN_MEDIA_PLAYER.name -> {
                    Log.d("Quran_Widget", "$mediaAction button is pressed")

                    openMediaPlayer(context)
                }

                else -> {
                    // do nothing
                }
            }

            updateUI(context!!)
        }

        super.onReceive(context, intent)
    }

    override fun onUpdate(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray
    ) {
        this.context = context
        // There may be multiple widgets active, so update all of them
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    private fun updateAppWidget(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int
    ) {
        updateUI(context)

        // Instruct the widget manager to update the widget
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private fun updateUI(context: Context) {
        setClickListeners(context)

        if (currentReciter != null) {
            views.setTextViewText(R.id.reciter_name, currentReciter?.name_ar)
        }

        if (currentChapter != null) {
            @SuppressLint("DiscouragedApi") val chapterImageDrawableId = context.resources.getIdentifier(
                "chapter_${currentChapter?.id.toString().padStart(3, '0')}",
                "drawable",
                context.packageName
            )

            val chapterImageBlurred = HokoBlur.with(context)
                .scheme(HokoBlur.SCHEME_NATIVE) //different implementation, RenderScript、OpenGL、Native(default) and Java
                .mode(HokoBlur.MODE_GAUSSIAN) //blur algorithms，Gaussian、Stack(default) and Box
                .radius(3) //blur radius，max=25，default=5
                .sampleFactor(2.0f).processor().blur(
                    (AppCompatResources.getDrawable(
                        context, chapterImageDrawableId
                    ) as BitmapDrawable).bitmap
                )

            views.setTextViewText(R.id.chapter_name, currentChapter!!.name_arabic)
            views.setImageViewBitmap(R.id.background_image, chapterImageBlurred)
            views.setImageViewResource(R.id.chapter_image, chapterImageDrawableId)

            MediaService.instance?.run {
                if (isMediaPlaying) {
                    views.setImageViewResource(
                        R.id.media_playback, com.hifnawy.quran.shared.R.drawable.media_pause_white
                    )
                } else {
                    views.setImageViewResource(
                        R.id.media_playback, com.hifnawy.quran.shared.R.drawable.media_play_white
                    )
                }
            }
        }
    }

    private fun setClickListeners(context: Context?) {
        val chapterImagePendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(context, NowPlaying::class.java).apply {
                action = WidgetActions.OPEN_MEDIA_PLAYER.name
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val chapterPlayPausePendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(context, NowPlaying::class.java).apply {
                action = WidgetActions.PLAY_PAUSE.name
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val chapterNextPendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(context, NowPlaying::class.java).apply {
                action = WidgetActions.NEXT.name
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val chapterPreviousPendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(context, NowPlaying::class.java).apply {
                action = WidgetActions.PREVIOUS.name
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
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

    private fun openMediaPlayer(context: Context?) {
        Log.d("Quran_Widget", "opening media player...")

        sharedPrefsManager?.run {
            startActivity(context!!, Intent(context, MainActivity::class.java).apply {
                Log.d("Quran_Widget", "updating intent media player...")

                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                addCategory(NowPlaying::class.simpleName)

                putExtra(Constants.IntentDataKeys.RECITER.name, lastReciter)
                putExtra(Constants.IntentDataKeys.CHAPTER.name, lastChapter)
                putExtra(Constants.IntentDataKeys.CHAPTER_POSITION.name, lastChapterPosition)
            }, null)
        }
    }
}