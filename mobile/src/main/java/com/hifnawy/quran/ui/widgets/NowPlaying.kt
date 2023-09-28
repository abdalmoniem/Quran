package com.hifnawy.quran.ui.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import android.widget.RemoteViews
import androidx.appcompat.content.res.AppCompatResources
import androidx.palette.graphics.Palette
import com.hifnawy.quran.R
import com.hifnawy.quran.shared.QuranMediaService
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.tools.Utilities.Companion.getSerializableExtra
import com.hifnawy.quran.ui.activities.MainActivity
import com.hoko.blur.HokoBlur


/**
 * Implementation of App Widget functionality.
 */

private var currentReciter: Reciter? = null
private var currentChapter: Chapter? = null

class NowPlaying : AppWidgetProvider() {
    private var sharedPrefs: SharedPreferences? = null
    override fun onReceive(context: Context?, intent: Intent?) {
        intent?.run {
            if (sharedPrefs == null) {
                sharedPrefs = context?.getSharedPreferences(
                    "${context.packageName}_preferences",
                    Context.MODE_PRIVATE
                )
            }

            val reciter = getSerializableExtra<Reciter>("RECITER")
            val chapter = getSerializableExtra<Chapter>("CHAPTER")

            val mediaAction = action

            if (mediaAction in listOf("PLAY_PAUSE", "NEXT", "PREVIOUS")) {
                Log.d("Quran_Widget", "$mediaAction button is pressed")
                if (QuranMediaService.isRunning) {
                    context?.sendBroadcast(Intent(context.getString(com.hifnawy.quran.shared.R.string.quran_media_player_controls)).apply {
                        putExtra(mediaAction, mediaAction)
                    })
                } else {
                    openMediaPlayer(context)
                }
            } else if (mediaAction.equals("OPEN_MEDIA_PLAYER")) {
                Log.d("Quran_Widget", "$mediaAction button is pressed")
                if (QuranMediaService.isRunning) {
                    context?.startActivity(Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        putExtra("DESTINATION", 3)
                        putExtra("RECITER", currentReciter)
                        putExtra("CHAPTER", currentChapter)
                    })
                } else {
                    openMediaPlayer(context)
                }
            } else {
                // do nothing
            }

            if ((reciter != null) and (chapter != null)) {
                currentReciter = reciter
                currentChapter = chapter
            }
        }

        super.onReceive(context, intent)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Construct the RemoteViews object
        val views = RemoteViews(context.packageName, R.layout.now_playing)

        val chapterImagePendingIntent = PendingIntent.getBroadcast(
            context,
            0, Intent(context, NowPlaying::class.java).apply {
                action = "OPEN_MEDIA_PLAYER"
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val chapterPlayPausePendingIntent = PendingIntent.getBroadcast(
            context,
            0, Intent(context, NowPlaying::class.java).apply {
                action = "PLAY_PAUSE"
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val chapterNextPendingIntent = PendingIntent.getBroadcast(
            context,
            0, Intent(context, NowPlaying::class.java).apply {
                action = "NEXT"
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val chapterPreviousPendingIntent = PendingIntent.getBroadcast(
            context,
            0, Intent(context, NowPlaying::class.java).apply {
                action = "PREVIOUS"
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        with(views) {
            setOnClickPendingIntent(R.id.chapter_image, chapterImagePendingIntent)
            setOnClickPendingIntent(R.id.chapter_play, chapterPlayPausePendingIntent)
            setOnClickPendingIntent(R.id.chapter_next, chapterNextPendingIntent)
            setOnClickPendingIntent(R.id.chapter_previous, chapterPreviousPendingIntent)
        }

        // There may be multiple widgets active, so update all of them
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(views, context, appWidgetManager, appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {
        // Enter relevant functionality for when the first widget is created
    }

    override fun onDisabled(context: Context) {
        // Enter relevant functionality for when the last widget is disabled
    }

    private fun openMediaPlayer(context: Context?) {
        context?.startActivity(Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra("DESTINATION", 3)
            putExtra("RECITER", currentReciter)
            putExtra("CHAPTER", currentChapter)
        })

        sharedPrefs?.run {
            val lastReciter = getSerializableExtra<Reciter>("LAST_RECITER")
            val lastChapter = getSerializableExtra<Chapter>("LAST_CHAPTER")
            val lastChapterPosition = getLong("LAST_CHAPTER_POSITION", -1L)

            context?.startForegroundService(Intent(
                context, QuranMediaService::class.java
            ).apply {
                putExtra("RECITER", lastReciter)
                putExtra("CHAPTER", lastChapter)

                if (lastChapterPosition != -1L) {
                    putExtra("CHAPTER_POSITION", lastChapterPosition)
                }
            })
        }
    }
}

internal fun updateAppWidget(
    views: RemoteViews,
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetId: Int
) {
    if (currentReciter != null) {
        views.setTextViewText(
            R.id.reciter_name,
            if (currentReciter!!.translated_name != null) currentReciter!!.translated_name!!.name else currentReciter!!.reciter_name
        )
    }

    if (currentChapter != null) {
        val chapterImageDrawableId = context.resources.getIdentifier(
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

        val dominantColor = Palette.from(
            (AppCompatResources.getDrawable(
                context, chapterImageDrawableId
            ) as BitmapDrawable).bitmap
        ).generate().getDominantColor(Color.RED)

        views.setTextViewText(R.id.chapter_name, currentChapter!!.name_arabic)
        views.setImageViewBitmap(R.id.background_image, chapterImageBlurred)
        views.setImageViewResource(R.id.chapter_image, chapterImageDrawableId)

        if (QuranMediaService.isRunning) {
            if (QuranMediaService.isMediaPlaying) {
                views.setImageViewResource(
                    R.id.chapter_play,
                    com.hifnawy.quran.shared.R.drawable.media_pause_white
                )
            } else {
                views.setImageViewResource(
                    R.id.chapter_play,
                    com.hifnawy.quran.shared.R.drawable.media_play_white
                )
            }
        } else {
            views.setImageViewResource(
                R.id.chapter_play,
                com.hifnawy.quran.shared.R.drawable.media_play_white
            )
        }
    }
    // Instruct the widget manager to update the widget
    appWidgetManager.updateAppWidget(appWidgetId, views)
}