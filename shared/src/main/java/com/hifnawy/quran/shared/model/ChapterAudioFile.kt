package com.hifnawy.quran.shared.model

import com.google.gson.annotations.SerializedName
import java.io.Serializable

data class ChapterAudioFile(
        val id: Int,
        @SerializedName("chapter_id")
        val chapterID: Int,
        @SerializedName("file_size")
        val size: Float,
        @SerializedName("format")
        val format: String,
        @SerializedName("audio_url")
        val url: String?
) : Serializable
