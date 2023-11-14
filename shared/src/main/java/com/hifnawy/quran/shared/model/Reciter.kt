package com.hifnawy.quran.shared.model

import java.io.Serializable

data class Reciter(
    val id: Int,
    @Suppress("PropertyName") val reciter_name: String,
    val style: RecitationStyle?,
    @Suppress("PrivatePropertyName") private val translated_name: TranslatedName?
) : Serializable {
    val name_ar
        get() = translated_name?.name ?: reciter_name

    @Suppress("unused", "SpellCheckingInspection")
    enum class RecitationStyle(val style: String?) {
        Murattal("مرتل"),
        Mujawwad("مجود"),
        Muallim("معلم")
    }

    class TranslatedName(val name: String, @Suppress("unused") val language_name: String) : Serializable
}