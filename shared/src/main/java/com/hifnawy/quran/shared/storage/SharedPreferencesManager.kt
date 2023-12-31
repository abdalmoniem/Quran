package com.hifnawy.quran.shared.storage

import android.content.Context
import android.util.Log
import com.hifnawy.quran.shared.extensions.SharedPreferencesExt.Companion.getSerializable
import com.hifnawy.quran.shared.extensions.SharedPreferencesExt.Companion.getSerializableList
import com.hifnawy.quran.shared.extensions.SharedPreferencesExt.Companion.putSerializable
import com.hifnawy.quran.shared.extensions.SharedPreferencesExt.Companion.putSerializableList
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.ChapterAudioFile
import com.hifnawy.quran.shared.model.Constants
import com.hifnawy.quran.shared.model.Reciter
import java.io.File

class SharedPreferencesManager(private val context: Context) {
    private enum class SharedPrefsKeys {
        RECITERS,
        CHAPTERS,
        LAST_RECITER,
        LAST_CHAPTER,
        LAST_CHAPTER_POSITION,
        LAST_CHAPTER_DURATION,
        RECITER_CHAPTER_AUDIO_FILES,
        MEDIA_PATH_CONSISTENCY,
        MEDIA_PATH_RENAMED
    }

    private val sharedPrefs =
            context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
    var reciters: List<Reciter>?
        get() = sharedPrefs.getSerializableList(SharedPrefsKeys.RECITERS.name)
        set(value) = sharedPrefs.edit()
            .putSerializableList(SharedPrefsKeys.RECITERS.name, value!!)
            .apply()
    var chapters: List<Chapter>?
        get() = sharedPrefs.getSerializableList(SharedPrefsKeys.CHAPTERS.name)
        set(value) = sharedPrefs.edit()
            .putSerializableList(SharedPrefsKeys.CHAPTERS.name, value!!)
            .apply()
    var lastReciter: Reciter?
        get() = sharedPrefs.getSerializable(SharedPrefsKeys.LAST_RECITER.name)
        set(value) = sharedPrefs.edit()
            .putSerializable(SharedPrefsKeys.LAST_RECITER.name, value!!)
            .apply()
    var lastChapter: Chapter?
        get() = sharedPrefs.getSerializable(SharedPrefsKeys.LAST_CHAPTER.name)
        set(value) = sharedPrefs.edit()
            .putSerializable(SharedPrefsKeys.LAST_CHAPTER.name, value!!)
            .apply()
    var lastChapterPosition: Long
        get() = sharedPrefs.getLong(SharedPrefsKeys.LAST_CHAPTER_POSITION.name, -1L)
        set(value) = sharedPrefs.edit()
            .putLong(SharedPrefsKeys.LAST_CHAPTER_POSITION.name, value)
            .apply()
    var lastChapterDuration: Long
        get() = sharedPrefs.getLong(SharedPrefsKeys.LAST_CHAPTER_DURATION.name, -1L)
        set(value) = sharedPrefs.edit()
            .putLong(SharedPrefsKeys.LAST_CHAPTER_DURATION.name, value)
            .apply()
    var areChapterPathsSaved: Boolean
        get() = sharedPrefs.getBoolean(SharedPrefsKeys.MEDIA_PATH_CONSISTENCY.name, false)
        set(value) = sharedPrefs.edit()
            .putBoolean(SharedPrefsKeys.MEDIA_PATH_CONSISTENCY.name, value)
            .apply()
    var areChapterPathsRenamed: Boolean
        get() = sharedPrefs.getBoolean(SharedPrefsKeys.MEDIA_PATH_RENAMED.name, false)
        set(value) = sharedPrefs.edit()
            .putBoolean(SharedPrefsKeys.MEDIA_PATH_RENAMED.name, value)
            .apply()

    fun getReciterChapterAudioFiles(reciterID: Int): List<ChapterAudioFile> =
            sharedPrefs.getSerializableList("${SharedPrefsKeys.RECITER_CHAPTER_AUDIO_FILES.name}_#$reciterID")

    fun setReciterChapterAudioFiles(reciterID: Int, chapterAudioFiles: List<ChapterAudioFile>) =
            sharedPrefs.edit()
                .putSerializableList(
                        "${SharedPrefsKeys.RECITER_CHAPTER_AUDIO_FILES.name}_#$reciterID",
                        chapterAudioFiles
                )
                .apply()

    fun setChapterPath(reciter: Reciter, chapter: Chapter) {
        val chapterPathKey = getChapterPathKey(reciter, chapter)
        val chapterFilePath = Constants.getChapterPath(context, reciter, chapter)
        sharedPrefs.edit().putString(chapterPathKey, chapterFilePath).apply()
    }

    fun getChapterPath(reciter: Reciter, chapter: Chapter): String? {
        val chapterPathKey = getChapterPathKey(reciter, chapter)
        return sharedPrefs.getString(chapterPathKey, null)
    }

    fun getChapterFile(reciter: Reciter, chapter: Chapter): File? {
        val chapterPathKey = getChapterPathKey(reciter, chapter)
        getChapterPath(reciter, chapter)?.let { chapterFilePath ->
            val chapterFile = File(chapterFilePath)

            Log.d("SharedPrefsManager", "file $chapterFilePath exists: ${chapterFile.exists()}")
            if (chapterFile.exists())
                return chapterFile
            else {
                sharedPrefs.edit().remove(chapterPathKey).apply()
                return null
            }
        } ?: return null
    }

    private fun getChapterPathKey(reciter: Reciter, chapter: Chapter): String =
            "${reciter.id}_${chapter.id}"
}