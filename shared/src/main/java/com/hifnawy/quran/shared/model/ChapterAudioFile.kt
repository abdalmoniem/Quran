package com.hifnawy.quran.shared.model

import java.io.Serializable

data class ChapterAudioFile(
    val id: Int,
    val chapter_id: Int,
    val file_size: Float,
    val format: String,
    val audio_url: String
) : Serializable
