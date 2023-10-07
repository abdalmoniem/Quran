package com.hifnawy.quran.shared.extensions

import android.content.SharedPreferences
import com.google.gson.Gson
import java.io.Serializable

class SharedPreferencesExt {
    companion object {
        inline fun <reified GenericType : Serializable> SharedPreferences.getSerializable(key: String): GenericType =
            Gson().fromJson(getString(key, null), GenericType::class.java)

        inline fun <reified GenericType : Serializable> SharedPreferences.Editor.putSerializable(
            key: String, value: GenericType
        ): SharedPreferences.Editor = putString(key, Gson().toJson(value))
    }
}