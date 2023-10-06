package com.hifnawy.quran.shared.api

import android.util.Log
import com.google.gson.Gson
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.ChapterAudioFile
import com.hifnawy.quran.shared.model.Reciter
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import ru.gildor.coroutines.okhttp.await
import java.net.SocketTimeoutException

class QuranAPI {
    fun interface ResponseHandler {
        fun handleResponse(error: Boolean, responseBodyJson: String)
    }

    companion object {
        private suspend fun sendRESTRequest(url: String, responseHandler: ResponseHandler) {
            val client: OkHttpClient = OkHttpClient().newBuilder().build()
            val request: Request =
                Request.Builder().url(url).method("GET", null).addHeader("Accept", "application/json")
                    .build()
            try {
                val response: Response = client.newCall(request).await()
                response.body()?.string()?.let { jsonString ->
                    responseHandler.handleResponse(false, jsonString)
                } ?: responseHandler.handleResponse(true, "")
            } catch (ex: SocketTimeoutException) {
                Log.w(QuranAPI::class.simpleName, ex.stackTraceToString())
                responseHandler.handleResponse(true, "")
            }
        }

        suspend fun getRecitersList(): List<Reciter> {
            var reciters: Array<Reciter> = emptyArray()

            sendRESTRequest("https://api.quran.com/api/v4/resources/recitations?language=ar") { error, responseBody ->
                if (error) return@sendRESTRequest

                val recitersJsonArray = JSONObject(responseBody).getJSONArray("recitations").toString()

                Log.d(this@Companion::class.simpleName, recitersJsonArray)

                reciters = Gson().fromJson(recitersJsonArray, Array<Reciter>::class.java)
            }

            return reciters.toList()
        }

        suspend fun getChaptersList(): List<Chapter> {
            var chapters: Array<Chapter> = emptyArray()

            sendRESTRequest("https://api.quran.com/api/v4/chapters?language=ar") { error, responseBody ->
                if (error) return@sendRESTRequest

                val chaptersJsonArray = JSONObject(responseBody).getJSONArray("chapters").toString()

                Log.d(this@Companion::class.simpleName, chaptersJsonArray)

                chapters = Gson().fromJson(chaptersJsonArray, Array<Chapter>::class.java)
            }

            return chapters.toList()
        }

        suspend fun getChapterAudioFile(reciterID: Int, chapterID: Int): ChapterAudioFile? {
            var chapterAudioFile: ChapterAudioFile? = null

            sendRESTRequest("https://api.quran.com/api/v4/chapter_recitations/$reciterID/$chapterID") { error, responseBody ->
                if (error) return@sendRESTRequest

                val chapterJsonObject = JSONObject(responseBody).getJSONObject("audio_file").toString()

                Log.d(this@Companion::class.simpleName, chapterJsonObject)

                chapterAudioFile = Gson().fromJson(chapterJsonObject, ChapterAudioFile::class.java)
            }
            return chapterAudioFile
        }

        suspend fun getReciterChaptersAudioFiles(reciterID: Int): List<ChapterAudioFile> {
            var reciterChaptersAudioFiles: Array<ChapterAudioFile> = emptyArray()

            sendRESTRequest("https://api.quran.com/api/v4/chapter_recitations/$reciterID") { error, responseBody ->
                if (error) return@sendRESTRequest

                val chapterJsonObject = JSONObject(responseBody).getJSONArray("audio_files").toString()

                Log.d(this@Companion::class.simpleName, chapterJsonObject)

                reciterChaptersAudioFiles =
                    Gson().fromJson(chapterJsonObject, Array<ChapterAudioFile>::class.java)
            }
            return reciterChaptersAudioFiles.toList()
        }
    }
}