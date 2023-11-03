package com.hifnawy.quran.shared.extensions

import android.os.Build
import android.os.Bundle
import java.io.Serializable

object BundleExt {

    inline fun <reified AnyType : Serializable> Bundle.getTypedSerializable(key: String): AnyType? =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                getSerializable(key, AnyType::class.java)

            else -> @Suppress("DEPRECATION") getSerializable(key) as? AnyType
        }
}