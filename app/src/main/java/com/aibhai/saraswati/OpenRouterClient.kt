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
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val history = mutableListOf<Pair<String,String>>() // role, content

    suspend fun chat(systemPrompt: String, userMessage: String): String = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("saraswati", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        if (apiKey.isEmpty()) return@withContext "Please set your API key."

        // Keep last 6 exchanges
        history.add("user" to userMessage)
        if (history.size > 12) history.removeAt(0)

        val messages = JSONArray()

        // System message first
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })

        // Add conversation history
        history.forEach { (role, content) ->
            messages.put(JSONObject().apply {
                put("role", role)
                put("content", content)
            })
        }

        val body = JSONObject().apply {
            put("model", "openai/gpt-3.5-turbo") // GPT-3.5 reliably follows identity instructions
            put("max_tokens", 100)
            put("temperature", 0.7)
            put("messages", messages)
        }.toString()

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", "https://github.com/aibhai9811-cpu")
            .addHeader("X-Title", "SARASWATI")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return@withContext try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext "No response received."

            val json = JSONObject(responseBody)

            if (json.has("error")) {
                val err = json.getJSONObject("error").optString("message", "")
                // Try fallback model if primary fails
                return@withContext tryFallback(apiKey, messages, userMessage)
            }

            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return@withContext tryFallback(apiKey, messages, userMessage)
            }

            val reply = choices.getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
                .replace(Regex("\\*+"), "")
                .replace(Regex("#+\\s*"), "")
                .replace(Regex("\\n+"), " ")
                .trim()

            history.add("assistant" to reply)
            reply

        } catch (e: Exception) {
            "Sorry, I could not connect. Please check your internet."
        }
    }

    private suspend fun tryFallback(apiKey: String, messages: JSONArray, userMessage: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("model", "meta-llama/llama-3-8b-instruct:free") // free fallback
                    put("max_tokens", 100)
                    put("messages", messages)
                }.toString()

                val request = Request.Builder()
                    .url("https://openrouter.ai/api/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val json = JSONObject(response.body?.string() ?: "")
                json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    ?.trim() ?: "I could not process that. Please try again."
            } catch (e: Exception) {
                "Sorry, I encountered an error. Please try again."
            }
        }
    }

    fun clearHistory() = history.clear()
}
