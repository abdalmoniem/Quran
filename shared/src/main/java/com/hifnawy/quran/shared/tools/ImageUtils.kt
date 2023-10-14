package com.hifnawy.quran.shared.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.DrawableRes
import com.hifnawy.quran.shared.R

object ImageUtils {

    fun drawTextOnBitmap(
            context: Context,
            @DrawableRes drawableId: Int,
            text: String
    ): Bitmap? {
        val dp = context.resources.displayMetrics.density + 0.5f
        val typeface = context.resources.getFont(R.font.decotype_thuluth_2)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        var bitmap = BitmapFactory.decodeResource(context.resources, drawableId)
        val bitmapConfig = bitmap.config
        bitmap = bitmap.copy(bitmapConfig, true)
        val canvas = Canvas(bitmap)

        paint.typeface = typeface
        paint.color = Color.WHITE
        paint.textSize = 170 * dp
        val textWidth = (canvas.width - (16 * dp)).toInt()
        val textLayout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        val textHeight = textLayout.height
        val x = (bitmap.width - textWidth) / 2f
        val y = (bitmap.height - textHeight) / 2f

        canvas.save()
        canvas.translate(x, y)
        textLayout.draw(canvas)
        canvas.restore()

        return bitmap
    }
}