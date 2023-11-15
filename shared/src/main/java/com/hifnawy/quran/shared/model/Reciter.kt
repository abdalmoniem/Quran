package com.hifnawy.quran.shared.model

import com.google.gson.annotations.SerializedName
import java.io.Serializable

data class Reciter(
        val id: Int,
        @SerializedName("reciter_name")
        val name: String,
        @SerializedName("style")
        val recitationStyle: RecitationStyle?,
        @SerializedName("translated_name")
        private val translatedName: TranslatedName?
) : Serializable {

    val nameArabic
        get() = translatedName?.name ?: name

    @Suppress("unused", "SpellCheckingInspection")
    enum class RecitationStyle(val style: String?) {

        Murattal("مرتل"),
        Mujawwad("مجود"),
        Muallim("معلم")
    }

    data class TranslatedName(
            val name: String,
            @SerializedName("language_name")
            val languageName: String
    ) : Serializable
}