package com.hifnawy.quran.shared.extensions

import android.content.Intent
import android.os.Build
import java.io.Serializable

object SerializableExt {

    inline fun <reified AnyType : Serializable> Intent.getTypedSerializable(key: String): AnyType? =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                getSerializableExtra(key, AnyType::class.java)

            else -> @Suppress("DEPRECATION") getSerializableExtra(key) as? AnyType
        }
}