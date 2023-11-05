package com.hifnawy.quran.shared.api

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hifnawy.quran.shared.model.Chapter
import com.hifnawy.quran.shared.model.ChapterAudioFile
import com.hifnawy.quran.shared.model.Reciter
import com.hifnawy.quran.shared.storage.SharedPreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

@Suppress("PrivatePropertyName")
private val TAG = QuranAPI::class.simpleName

object QuranAPI {

    private val ioCoroutineScope = CoroutineScope(Dispatchers.IO)

    private suspend fun sendRESTRequest(
            url: String,
            responseHandler: ((error: Boolean, errorType: KClass<out Exception>?, responseMessage: String) -> Unit)?
    ) {
        val client: OkHttpClient = OkHttpClient().newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
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
                    responseHandler?.invoke(false, null, body.string())
                }
        } catch (ex: Exception) {
            Log.w(TAG, ex.message, ex)
            responseHandler?.invoke(true, ex::class, "Connection failed with error: $ex")
        }
    }

    suspend fun getRecitersList(context: Context): List<Reciter> {
        val sharedPrefsManager = SharedPreferencesManager(context)
        var reciters = sharedPrefsManager.reciters

        if (reciters.isNullOrEmpty()) {
            Log.d(
                    TAG,
                    "No cached reciters found, fetching from: https://api.quran.com/api/v4/resources/recitations?language=ar..."
            )

            sendRESTRequest("https://api.quran.com/api/v4/resources/recitations?language=ar") { error, _, responseMessage ->
                if (error) {
                    Log.w(TAG, responseMessage)
                    return@sendRESTRequest
                }
                val recitersJsonArray =
                    JSONObject(responseMessage).getJSONArray("recitations").toString()

                reciters =
                    Gson().fromJson(recitersJsonArray, object : TypeToken<List<Reciter>>() {}.type)

                sharedPrefsManager.reciters = reciters
            }
        } else {
            Log.d(TAG, "a cached reciters list was found, using that!")
        }

        reciters?.let { nonNullReciters ->
            Log.d(TAG, nonNullReciters.joinToString(separator = "\n") { it.toString() })
        }

        return reciters?.toList() ?: emptyList()
    }

    suspend fun getChaptersList(context: Context): List<Chapter> {
        val sharedPrefsManager = SharedPreferencesManager(context)
        var chapters = sharedPrefsManager.chapters

        if (chapters.isNullOrEmpty()) {
            Log.d(
                    TAG,
                    "No cached chapters found, fetching from: https://api.quran.com/api/v4/chapters?language=ar..."
            )

            sendRESTRequest("https://api.quran.com/api/v4/chapters?language=ar") { error, _, responseMessage ->
                if (error) {
                    Log.w(TAG, responseMessage)
                    return@sendRESTRequest
                }
                val chaptersJsonArray = JSONObject(responseMessage).getJSONArray("chapters").toString()
                chapters =
                    Gson().fromJson(chaptersJsonArray, object : TypeToken<List<Chapter>>() {}.type)

                sharedPrefsManager.chapters = chapters
            }
        } else {
            Log.d(TAG, "a cached chapters list was found, using that!")
        }

        chapters?.let { nonNullChapters ->
            Log.d(TAG, nonNullChapters.joinToString(separator = "\n") { it.toString() })
        }

        return chapters?.toList() ?: emptyList()
    }

    suspend fun getChapterAudioFile(reciterID: Int, chapterID: Int): ChapterAudioFile? {
        var chapterAudioFile: ChapterAudioFile? = null

        sendRESTRequest("https://api.quran.com/api/v4/chapter_recitations/$reciterID/$chapterID") { error, _, responseMessage ->
            if (error) {
                Log.w(TAG, responseMessage)
                return@sendRESTRequest
            }
            val chapterJsonObject =
                JSONObject(responseMessage).getJSONObject("audio_file").toString()
            chapterAudioFile = Gson().fromJson(chapterJsonObject, ChapterAudioFile::class.java)

            Log.d(TAG, chapterAudioFile.toString())
        }
        return chapterAudioFile
    }

    suspend fun getReciterChaptersAudioFiles(
            context: Context? = null,
            reciterID: Int
    ): List<ChapterAudioFile> {
        val sharedPrefsManager = context?.let { SharedPreferencesManager(it) }
        var reciterChaptersAudioFiles = sharedPrefsManager?.getReciterChapterAudioFiles(reciterID)

        if (reciterChaptersAudioFiles.isNullOrEmpty()) {
            Log.d(
                    TAG,
                    "No cached chapter audio files found for reciterID: $reciterID, fetching from: https://api.quran.com/api/v4/chapter_recitations/$reciterID..."
            )

            sendRESTRequest("https://api.quran.com/api/v4/chapter_recitations/$reciterID") { error, _, responseMessage ->
                if (error) {
                    Log.w(TAG, responseMessage)
                    return@sendRESTRequest
                }
                val chapterJsonObject =
                    JSONObject(responseMessage).getJSONArray("audio_files").toString()
                reciterChaptersAudioFiles =
                    Gson().fromJson(
                            chapterJsonObject,
                            object : TypeToken<List<ChapterAudioFile>>() {}.type
                    )

                sharedPrefsManager?.setReciterChapterAudioFiles(reciterID, reciterChaptersAudioFiles!!)
            }
        } else {
            Log.d(
                    TAG,
                    "a cached chapter audio files list for reciterID: $reciterID was found, using that!"
            )
        }

        reciterChaptersAudioFiles?.let { noNullReciterChaptersAudioFiles ->
            Log.d(TAG, noNullReciterChaptersAudioFiles.joinToString(separator = "\n") { it.toString() })
        }

        return reciterChaptersAudioFiles?.toList() ?: emptyList()
    }
}