package com.aibhai.saraswati

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class OpenRouterClient(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val history = mutableListOf<JSONObject>()

    suspend fun chat(systemPrompt: String, userMessage: String): String = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("saraswati", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        if (apiKey.isEmpty()) return@withContext "Please set your API key in settings."

        history.add(JSONObject().put("role", "user").put("content", userMessage))
        if (history.size > 10) history.removeAt(0)

        val messages = JSONArray().apply { history.forEach { put(it) } }

        val bodyJson = JSONObject().apply {
            // Use mistral-7b which does NOT identify as Claude
            put("model", "mistralai/mistral-7b-instruct")
            put("max_tokens", 150)
            put("temperature", 0.7)
            put("messages", JSONArray().apply {
                // Prepend system as first user message since some models handle it better
                put(JSONObject().put("role", "user").put("content", "[SYSTEM]: $systemPrompt"))
                put(JSONObject().put("role", "assistant").put("content", "Understood. I am SARASWATI, ready to assist."))
                history.forEach { put(it) }
            })
        }.toString()

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", "https://github.com/aibhai9811-cpu/SARASWATI-Android")
            .addHeader("X-Title", "SARASWATI")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        return@withContext try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            val json = JSONObject(body)

            if (json.has("error")) {
                val errorMsg = json.getJSONObject("error").optString("message", "Unknown error")
                return@withContext "Sorry, I encountered an error. Please try again."
            }

            val reply = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
                // Remove any markdown
                .replace("**", "").replace("*", "").replace("#", "")
                .replace(Regex("\\n+"), " ")
                .trim()

            history.add(JSONObject().put("role", "assistant").put("content", reply))
            reply
        } catch (e: Exception) {
            "Sorry, I couldn't connect. Please check your internet connection."
        }
    }

    fun clearHistory() = history.clear()
}
