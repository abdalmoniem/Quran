package com.hifnawy.quran.shared.tools

import android.content.Context
import android.content.SharedPreferences
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.tools.Utilities.Companion.getSerializableExtra
import com.hifnawy.quran.shared.tools.Utilities.Companion.putSerializableExtra

class SharedPreferencesManager(private val context: Context) {
    private enum class SharedPrefsKeys {
        LAST_RECITER, LAST_CHAPTER, LAST_CHAPTER_POSITION
    }

    private var sharedPrefs: SharedPreferences = context.getSharedPreferences(
        "${context.packageName}_preferences", Context.MODE_PRIVATE
    )

    var lastReciter: Reciter?
        get() = sharedPrefs.getSerializableExtra<Reciter>(SharedPrefsKeys.LAST_RECITER.name)
        set(value) = sharedPrefs.edit().putSerializableExtra(SharedPrefsKeys.LAST_RECITER.name, value!!)
            .apply()

    var lastChapter: Chapter?
        get() = sharedPrefs.getSerializableExtra<Chapter>(SharedPrefsKeys.LAST_CHAPTER.name)
        set(value) = sharedPrefs.edit().putSerializableExtra(SharedPrefsKeys.LAST_CHAPTER.name, value!!)
            .apply()

    var lastChapterPosition: Long
        get() = sharedPrefs.getLong(SharedPrefsKeys.LAST_CHAPTER_POSITION.name, -1L)
        set(value) = sharedPrefs.edit().putLong(SharedPrefsKeys.LAST_CHAPTER_POSITION.name, value)
            .apply()

    fun setChapterPath(reciter: Reciter, chapter: Chapter) {
        val reciterDirectory =
            "${this.context.filesDir.absolutePath}/${reciter.reciter_name}/${reciter.style ?: ""}"
        val chapterFileName =
            "$reciterDirectory/${chapter.id.toString().padStart(3, '0')}_${chapter.name_simple}.mp3"

        sharedPrefs.edit().putString("${reciter.id}_${chapter.id}", chapterFileName).apply()
    }

    fun getChapterPath(reciter: Reciter, chapter: Chapter): String? {
        return sharedPrefs.getString("${reciter.id}_${chapter.id}", null)
    }

}