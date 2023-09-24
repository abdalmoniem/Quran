package com.hifnawy.quran.shared.model

import java.io.Serializable

data class Chapter(
    val id: Int,
    val revelation_place: RevelationPlace,
    val revelation_order: Int,
    @Suppress("SpellCheckingInspection")
    val bismillah_pre: Boolean,
    val name_simple: String,
    val name_complex: String,
    val name_arabic: String,
    val verses_count: Int,
    val pages: List<Int>,
    val translated_name: TranslatedName?
) : Serializable {
    @Suppress("unused", "EnumEntryName", "SpellCheckingInspection")
    enum class RevelationPlace(val place: String) {
        makkah("مكية"),
        madinah("مدنية")
    }

    class TranslatedName(val name: String, @Suppress("unused") val language_name: String)
}