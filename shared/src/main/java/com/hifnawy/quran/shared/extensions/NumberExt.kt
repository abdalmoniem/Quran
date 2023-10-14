package com.hifnawy.quran.shared.extensions

import android.content.Context

object NumberExt {

    fun Int.dp(context: Context): Int = (this * context.resources.displayMetrics.density + 0.5f).toInt()
}