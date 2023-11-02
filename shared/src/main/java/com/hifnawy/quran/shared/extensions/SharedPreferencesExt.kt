package com.hifnawy.quran.shared.extensions

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.Serializable

class SharedPreferencesExt {
    companion object {

        inline fun <reified GenericType : Serializable> SharedPreferences.getSerializable(key: String): GenericType =
            Gson().fromJson(getString(key, "{}"), GenericType::class.java)

        inline fun <reified GenericType : List<Serializable>> SharedPreferences.getSerializableList(key: String): GenericType =
            Gson().fromJson(getString(key, "[]"), object : TypeToken<GenericType>() {}.type)

        inline fun <reified GenericType : Serializable> SharedPreferences.Editor.putSerializable(
                key: String, value: GenericType
        ): SharedPreferences.Editor = putString(key, Gson().toJson(value))

        inline fun <reified GenericType : List<Serializable>> SharedPreferences.Editor.putSerializableList(
                key: String, value: GenericType
        ): SharedPreferences.Editor = putString(key, Gson().toJson(value.toTypedArray()))
    }
}