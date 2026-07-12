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
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val conversationHistory = mutableListOf<JSONObject>()

    suspend fun chat(systemPrompt: String, userMessage: String): String = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("saraswati", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""

        if (apiKey.isEmpty()) return@withContext "Please set your OpenRouter API key first."

        // Add user message to history
        conversationHistory.add(JSONObject().apply {
            put("role", "user")
            put("content", userMessage)
        })

        // Keep only last 10 exchanges to stay within token limits
        if (conversationHistory.size > 20) {
            conversationHistory.removeAt(0)
            if (conversationHistory.size > 20) conversationHistory.removeAt(0)
        }

        val messagesArray = JSONArray().apply {
            conversationHistory.forEach { put(it) }
        }

        val body = JSONObject().apply {
            put("model", "anthropic/claude-3-haiku")
            put("max_tokens", 200)
            put("system", systemPrompt)
            put("messages", messagesArray)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", "https://github.com/aibhai9811-cpu/SARASWATI-Android")
            .addHeader("X-Title", "SARASWATI Android")
            .post(body)
            .build()

        return@withContext try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            val json = JSONObject(responseBody)

            val reply = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            // Add assistant reply to history
            conversationHistory.add(JSONObject().apply {
                put("role", "assistant")
                put("content", reply)
            })

            reply
        } catch (e: Exception) {
            "I encountered an error. Please check your internet connection and API key."
        }
    }

    fun clearHistory() {
        conversationHistory.clear()
    }
}
