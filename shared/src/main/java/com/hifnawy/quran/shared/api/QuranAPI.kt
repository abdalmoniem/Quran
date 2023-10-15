package com.hifnawy.quran.shared.api

import android.util.Log
import com.google.gson.Gson
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.ChapterAudioFile
import com.hifnawy.quran.shared.model.Reciter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

@Suppress("PrivatePropertyName")
private val TAG = QuranAPI::class.simpleName

object QuranAPI {

    private val ioCoroutineScope = CoroutineScope(Dispatchers.IO)

    fun interface ResponseHandler {

        fun handleResponse(error: Boolean, responseMessage: String)
    }

    private suspend fun sendRESTRequest(url: String, responseHandler: ResponseHandler) {
        val client: OkHttpClient = OkHttpClient().newBuilder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true).build()
        val request: Request = Request.Builder()
            .url(url)
            .method("GET", null)
            .addHeader("Accept", "application/json")
            .build()
        try {
            ioCoroutineScope
                .async {
                    client
                        .newCall(request)
                        .execute()
                }
                .await()
                .apply {
                    responseHandler.handleResponse(false, body.string())
                }
        } catch (ex: SocketTimeoutException) {
            Log.w(TAG, ex.message, ex)
            responseHandler.handleResponse(true, "Connection failed with error: $ex")
        }
    }

    suspend fun getRecitersList(): List<Reciter> {
        var reciters: Array<Reciter> = emptyArray()

        sendRESTRequest("https://api.quran.com/api/v4/resources/recitations?language=ar") { error, responseMessage ->
            if (error) {
                Log.w(TAG, responseMessage)
                return@sendRESTRequest
            }
            val recitersJsonArray =
                JSONObject(responseMessage).getJSONArray("recitations").toString()

            reciters = Gson().fromJson(recitersJsonArray, Array<Reciter>::class.java)

            Log.i(TAG, reciters.joinToString(separator = "\n") { it.toString() })
        }

        return reciters.toList()
    }

    suspend fun getChaptersList(): List<Chapter> {
        var chapters: Array<Chapter> = emptyArray()

        sendRESTRequest("https://api.quran.com/api/v4/chapters?language=ar") { error, responseMessage ->
            if (error) {
                Log.w(TAG, responseMessage)
                return@sendRESTRequest
            }
            val chaptersJsonArray = JSONObject(responseMessage).getJSONArray("chapters").toString()
            chapters = Gson().fromJson(chaptersJsonArray, Array<Chapter>::class.java)

            Log.i(TAG, chapters.joinToString(separator = "\n") { it.toString() })
        }

        return chapters.toList()
    }

    suspend fun getChapterAudioFile(reciterID: Int, chapterID: Int): ChapterAudioFile? {
        var chapterAudioFile: ChapterAudioFile? = null

        sendRESTRequest("https://api.quran.com/api/v4/chapter_recitations/$reciterID/$chapterID") { error, responseMessage ->
            if (error) {
                Log.w(TAG, responseMessage)
                return@sendRESTRequest
            }
            val chapterJsonObject =
                JSONObject(responseMessage).getJSONObject("audio_file").toString()
            chapterAudioFile = Gson().fromJson(chapterJsonObject, ChapterAudioFile::class.java)

            Log.i(TAG, chapterAudioFile.toString())
        }
        return chapterAudioFile
    }

    suspend fun getReciterChaptersAudioFiles(reciterID: Int): List<ChapterAudioFile> {
        var reciterChaptersAudioFiles: Array<ChapterAudioFile> = emptyArray()

        sendRESTRequest("https://api.quran.com/api/v4/chapter_recitations/$reciterID") { error, responseMessage ->
            if (error) {
                Log.w(TAG, responseMessage)
                return@sendRESTRequest
            }
            val chapterJsonObject =
                JSONObject(responseMessage).getJSONArray("audio_files").toString()
            reciterChaptersAudioFiles =
                Gson().fromJson(chapterJsonObject, Array<ChapterAudioFile>::class.java)

            Log.i(TAG, reciterChaptersAudioFiles.joinToString(separator = "\n") { it.toString() })
        }
        return reciterChaptersAudioFiles.toList()
    }
}