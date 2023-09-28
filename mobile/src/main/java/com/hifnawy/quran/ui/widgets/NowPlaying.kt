package com.hifnawy.quran.ui.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.widget.RemoteViews
import androidx.appcompat.content.res.AppCompatResources
import androidx.palette.graphics.Palette
import com.hifnawy.quran.R
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.tools.Utilities.Companion.getSerializableExtra
import com.hoko.blur.HokoBlur

/**
 * Implementation of App Widget functionality.
 */

private var currentReciter: Reciter? = null
private var currentChapter: Chapter? = null

class NowPlaying : AppWidgetProvider() {

    override fun onReceive(context: Context?, intent: Intent?) {
        intent?.let {
            val reciter = intent.getSerializableExtra<Reciter>("RECITER")
            val chapter = intent.getSerializableExtra<Chapter>("CHAPTER")

            if ((reciter != null) and (chapter != null)) {
                currentReciter = reciter
                currentChapter = chapter
            }
        }

        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // There may be multiple widgets active, so update all of them
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {
        // Enter relevant functionality for when the first widget is created
    }

    override fun onDisabled(context: Context) {
        // Enter relevant functionality for when the last widget is disabled
    }
}

internal fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
    // Construct the RemoteViews object
    val views = RemoteViews(context.packageName, R.layout.now_playing)

    if (currentReciter != null) {
        views.setTextViewText(
            R.id.reciter_name,
            if (currentReciter!!.translated_name != null) currentReciter!!.translated_name!!.name else currentReciter!!.reciter_name
        )
    }

    if (currentChapter != null) {
        val chapterImageDrawableId = context.resources.getIdentifier(
            "chapter_${currentChapter?.id.toString().padStart(3, '0')}", "drawable", context.packageName
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
    }
    // Instruct the widget manager to update the widget
    appWidgetManager.updateAppWidget(appWidgetId, views)
}