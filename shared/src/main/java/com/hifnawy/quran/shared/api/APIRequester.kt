package com.hifnawy.quran.shared.api

import android.util.Log
import com.google.gson.Gson
import com.hifnawy.quran.shared.model.Reciter
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import ru.gildor.coroutines.okhttp.await

class APIRequester {
    companion object {
        suspend fun getRecitersList(): List<Reciter> {
            var reciters: Array<Reciter> = emptyArray()

            val client: OkHttpClient = OkHttpClient().newBuilder().build()
            val request: Request =
                Request.Builder().url("https://api.quran.com/api/v4/resources/recitations?language=ar")
                    .method("GET", null).addHeader("Accept", "application/json").build()
            val response: Response = client.newCall(request).await()

            response.body()?.string()?.let { responseBody ->
                val recitersJsonArray = JSONObject(responseBody).getJSONArray("recitations").toString()

                Log.d(this@Companion::class.java.canonicalName, recitersJsonArray)

                reciters = Gson().fromJson(recitersJsonArray, Array<Reciter>::class.java)
            }

            return reciters.toList()
        }
    }
}