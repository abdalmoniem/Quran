package com.hifnawy.quran.shared.extensions

import android.content.SharedPreferences
import com.google.gson.Gson
import java.io.Serializable

object SharedPreferencesExt {

    inline fun <reified AnyType : Serializable> SharedPreferences.getSerializable(key: String): AnyType =
        Gson().fromJson(getString(key, "{}"), AnyType::class.java)

    inline fun <reified AnyType : Serializable> SharedPreferences.Editor.putSerializable(
            key: String,
            value: AnyType
    ): SharedPreferences.Editor = putString(key, Gson().toJson(value))
}