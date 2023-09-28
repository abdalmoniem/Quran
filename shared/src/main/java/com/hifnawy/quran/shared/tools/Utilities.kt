package com.hifnawy.quran.shared.tools

import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import com.google.gson.Gson
import java.io.Serializable

class Utilities {
    companion object {
        @Suppress("EXTENSION_SHADOWED_BY_MEMBER")
        inline fun <reified GenericType : Serializable> Bundle.getSerializable(key: String): GenericType? =
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getSerializable(
                    key, GenericType::class.java
                )

                else -> @Suppress("DEPRECATION") getSerializable(key) as? GenericType
            }

        @Suppress("EXTENSION_SHADOWED_BY_MEMBER")
        inline fun <reified GenericType : Serializable> Intent.getSerializableExtra(key: String): GenericType? =
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getSerializableExtra(
                    key, GenericType::class.java
                )

                else -> @Suppress("DEPRECATION") getSerializableExtra(key) as? GenericType
            }

        inline fun <reified GenericType : Serializable> SharedPreferences.getSerializableExtra(key: String): GenericType? =
            Gson().fromJson(getString(key, null), GenericType::class.java)

        fun SharedPreferences.Editor.putSerializableExtra(
            key: String,
            value: Serializable
        ): SharedPreferences.Editor =
            putString(key, Gson().toJson(value))
    }
}