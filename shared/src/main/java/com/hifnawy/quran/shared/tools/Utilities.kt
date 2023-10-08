package com.hifnawy.quran.shared.tools

import android.content.Context
import android.util.Log
import com.hifnawy.quran.shared.api.QuranAPI
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.storage.SharedPreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

class Utilities {
    companion object {
        @Suppress("BlockingMethodInNonBlockingContext")
        suspend fun updateChapterPaths(context: Context) {
            val ioCoroutineScope = CoroutineScope(Dispatchers.IO)
            val reciters = ioCoroutineScope.async { QuranAPI.getRecitersList() }.await()
            val chapters = ioCoroutineScope.async { QuranAPI.getChaptersList() }.await()
            Log.d(Utilities::class.simpleName, "Checking Chapters' Paths...")

            for (reciter in reciters) {
                for (chapter in chapters) {
                    val chapterFile = getChapterPath(context, reciter, chapter)

                    if (!chapterFile.exists()) {
                        Log.d(
                                Utilities::class.simpleName,
                                "${chapterFile.absolutePath} doesn't exist, skipping"
                        )
                        continue
                    }

                    Log.d(Utilities::class.simpleName, "${chapterFile.absolutePath} exists, checking...")
                    val chapterAudioFileUrl =
                        QuranAPI.getChapterAudioFile(reciter.id, chapter.id)?.audio_url ?: continue

                    (URL(chapterAudioFileUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Accept-Encoding", "identity")
                        connect()

                        if (responseCode !in 200..299) {
                            return@apply
                        }
                        val chapterFileSize = Files.readAttributes(
                                chapterFile.toPath(), BasicFileAttributes::class.java
                        ).size()

                        if (chapterFileSize != contentLength.toLong()) {
                            Log.d(
                                    Utilities::class.simpleName,
                                    "Chapter Audio File incomplete, Deleting chapterPath:\nreciter #${reciter.id}: ${reciter.reciter_name}\nchapter: ${chapter.name_simple}\npath: ${chapterFile.absolutePath}\n"
                            )
                            chapterFile.delete()
                        } else {
                            Log.d(
                                    Utilities::class.simpleName,
                                    "Chapter Audio File complete, Updating chapterPath:\nreciter #${reciter.id}: ${reciter.reciter_name}\nchapter: ${chapter.name_simple}\npath: ${chapterFile.absolutePath}\nsize: ${chapterFileSize / 1024 / 1024} MBs"
                            )
                            SharedPreferencesManager(context).setChapterPath(
                                    reciter, chapter
                            )
                        }
                    }
                }
            }

            Log.d(Utilities::class.simpleName, "SharedPrefs Updated!!!")
        }

        private fun getChapterPath(context: Context, reciter: Reciter, chapter: Chapter): File {
            val reciterDirectory =
                "${context.filesDir.absolutePath}/${reciter.reciter_name}/${reciter.style ?: ""}"
            val chapterFileName =
                "$reciterDirectory/${chapter.id.toString().padStart(3, '0')}_${chapter.name_simple}.mp3"

            return File(chapterFileName)
        }
    }
}