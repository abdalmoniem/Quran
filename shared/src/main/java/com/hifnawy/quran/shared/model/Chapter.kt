package com.hifnawy.quran.shared.model

import com.google.gson.annotations.SerializedName
import java.io.Serializable

data class Chapter(
        val id: Int,
        @SerializedName("revelation_place")
        val revelationPlace: RevelationPlace,
        @SerializedName("revelation_order")
        val revelationOrder: Int,
        @SerializedName("bismillah_pre")
        val bismillahPre: Boolean,
        @SerializedName("name_simple")
        val nameSimple: String,
        @SerializedName("name_complex")
        val nameComplex: String,
        @SerializedName("name_arabic")
        val nameArabic: String,
        @SerializedName("verses_count")
        val verseCount: Int,
        val pages: List<Int>,
        @SerializedName("translated_name")
        val translatedName: TranslatedName?
) : Serializable {

    @Suppress("unused", "EnumEntryName", "SpellCheckingInspection")
    enum class RevelationPlace(val place: String) {

        makkah("مكية"),
        madinah("مدنية")
    }

    data class TranslatedName(
            val name: String,
            @SerializedName("language_name")
            val languageName: String
    ) : Serializable
}