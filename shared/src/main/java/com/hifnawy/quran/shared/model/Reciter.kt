package com.hifnawy.quran.shared.model

data class Reciter(
    val id: Int,
    val reciter_name: String,
    val style: RecitationStyle?,
    val translated_name: TranslatedName?
) {
    @Suppress("unused", "SpellCheckingInspection")
    enum class RecitationStyle(val style: String?) {
        Murattal("مرتل"), Mujawwad("مجود"), Muallim("معلم")
    }

    class TranslatedName(val name: String, @Suppress("unused") val language_name: String)
}