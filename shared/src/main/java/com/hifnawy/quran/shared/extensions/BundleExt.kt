package com.hifnawy.quran.shared.extensions

import android.os.Build
import android.os.Bundle
import java.io.Serializable

class BundleExt {
    companion object {
        inline fun <reified GenericType : Serializable> Bundle.getTypedSerializable(key: String): GenericType? =
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getSerializable(
                    key, GenericType::class.java
                )

                else -> @Suppress("DEPRECATION") getSerializable(key) as? GenericType
            }
    }
}