package com.hifnawy.quran.shared.extensions

import android.content.res.Resources

object NumberExt {

    inline val Int.dp: Int
        get() = (this * Resources.getSystem().displayMetrics.density + 0.5f).toInt()
    inline val Float.dp: Float
        get() = this * Resources.getSystem().displayMetrics.density + 0.5f
    inline val Int.sp: Int
        get() = (this * Resources.getSystem().displayMetrics.scaledDensity).toInt()
    inline val Float.sp: Float
        get() = this * Resources.getSystem().displayMetrics.scaledDensity
}