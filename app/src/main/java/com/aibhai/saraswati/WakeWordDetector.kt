package com.aibhai.saraswati

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class WakeWordDetector(
    private val context: Context,
    private val onWakeWordDetected: (String) -> Unit,
    private val onError: (String) -> Unit,
    val onDebugText: ((String) -> Unit)? = null // shows what Vosk hears on screen
) {
    companion object {
        private const val TAG = "WakeWordDetector"
        private const val SAMPLE_RATE = 16000
        // Very broad list — Vosk small model has limited accuracy
        // Include phonetic variations of how Indians pronounce "saraswati"
        // These are the EXACT strings Vosk transcribes when user says "Hey/Hello Saraswati"
        // Collected from real device testing — add more as discovered
        val WAKE_WORDS = listOf(
            // What Vosk actually hears for "Hello/Hey Saraswati"
            "hello source", "hello sources", "hello sweetie",
            "hello thumb", "halo four", "hello source or",
            "hey source", "hey sources", "hey sweetie",
            "hey thumb", "hey source or",
            // Direct variations
            "hey saraswati", "hello saraswati", "hi saraswati",
            "ok saraswati", "saraswati",
            // Single word triggers that Vosk reliably hears
            "sweetie",      // Vosk hears "saraswati" as "sweetie"
            "sources",      // Vosk hears "saraswati" as "sources"
            // Extra phonetic fallbacks
            "sara", "source wati", "sara swati",
            "hello sara", "hey sara", "hi sara",
            "hello swati", "hey swati",
            "source or two", "source two",
            "halo four forty", "halo four"
        )
    }

    private var speechService: SpeechService? = null
    private var model: Model? = null
    private var isRunning = false
    private var isPaused = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── INITIALIZE MODEL ──
    fun initialize(onReady: () -> Unit) {
        Thread {
            try {
                val modelDir = File(context.filesDir, "vosk-model-small-en-in")
                if (!modelDir.exists() || !File(modelDir, "README").exists()) {
                    // Extract model from assets
                    Log.d(TAG, "Extracting Vosk model...")
                    extractModelFromAssets(modelDir)
                }
                model = Model(modelDir.absolutePath)
                Log.d(TAG, "Vosk model loaded successfully")
                mainHandler.post { onReady() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Vosk: ${e.message}")
                mainHandler.post { onError("Wake word model failed to load: ${e.message}") }
            }
        }.start()
    }

    // ── START LISTENING ──
    fun startListening() {
        if (isRunning || isPaused) return
        val m = model ?: run {
            onError("Model not initialized")
            return
        }

        try {
            val recognizer = Recognizer(m, SAMPLE_RATE.toFloat())
            speechService = SpeechService(recognizer, SAMPLE_RATE.toFloat())
            speechService?.startListening(object : RecognitionListener {

                override fun onPartialResult(hypothesis: String?) {
                    if (hypothesis.isNullOrBlank() || isPaused) return
                    val text = parseResult(hypothesis).lowercase()
                    if (text.length > 2) {
                        Log.d(TAG, "Vosk partial: '$text'")
                        // Show what Vosk hears on screen via callback
                        onDebugText?.invoke("Vosk hearing: $text")
                        checkForWakeWord(text, partial = true)
                    }
                }

                override fun onResult(hypothesis: String?) {
                    if (hypothesis.isNullOrBlank() || isPaused) return
                    val text = parseResult(hypothesis).lowercase()
                    if (text.length > 2) {
                        Log.d(TAG, "Vosk result: '$text'")
                        onDebugText?.invoke("Vosk heard: $text")
                        checkForWakeWord(text, partial = false)
                    }
                }

                override fun onFinalResult(hypothesis: String?) {
                    if (!hypothesis.isNullOrBlank() && !isPaused) {
                        val text = parseResult(hypothesis).lowercase()
                        if (text.length > 2) {
                            Log.d(TAG, "Vosk final: '$text'")
                            checkForWakeWord(text, partial = false)
                        }
                    }
                }

                override fun onError(exception: Exception?) {
                    Log.e(TAG, "Vosk error: ${exception?.message}")
                    onDebugText?.invoke("Vosk error: ${exception?.message}")
                }

                override fun onTimeout() {
                    Log.d(TAG, "Vosk timeout")
                    onDebugText?.invoke("Vosk timeout - restarting")
                }
            })
            isRunning = true
            Log.d(TAG, "Wake word detection started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start: ${e.message}")
            onError("Failed to start wake word detection")
        }
    }

    private fun checkForWakeWord(text: String, partial: Boolean) {
        val wakeWord = WAKE_WORDS.firstOrNull { text.contains(it) } ?: return

        // Extract command after wake word
        var command = text
        WAKE_WORDS.sortedByDescending { it.length }.forEach { w ->
            command = command.replace(w, "").trim()
        }
        // Remove filler words
        command = command.removePrefix("and").removePrefix("to")
            .removePrefix("please").trim()

        Log.d(TAG, "Wake word detected! Command: '$command'")
        mainHandler.post { onWakeWordDetected(command) }
    }

    private fun parseResult(json: String): String {
        // Parse Vosk JSON result: {"text": "hey saraswati what time is it"}
        return try {
            val textStart = json.indexOf("\"text\"")
            if (textStart < 0) return ""
            val valueStart = json.indexOf("\"", textStart + 7) + 1
            val valueEnd = json.indexOf("\"", valueStart)
            if (valueStart > 0 && valueEnd > valueStart)
                json.substring(valueStart, valueEnd)
            else ""
        } catch (e: Exception) { "" }
    }

    // ── PAUSE (while speaking or processing) ──
    fun pause() {
        isPaused = true
    }

    // ── RESUME ──
    fun resume() {
        isPaused = false
    }

    // ── STOP ──
    fun stop() {
        isRunning = false
        isPaused = false
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (e: Exception) { }
        speechService = null
    }

    fun isActive() = isRunning && !isPaused

    // ── EXTRACT MODEL FROM ASSETS ──
    private fun extractModelFromAssets(destDir: File) {
        destDir.mkdirs()
        val assetManager = context.assets
        copyAssetFolder(assetManager, "vosk-model-small-en-in", destDir.absolutePath)
    }

    private fun copyAssetFolder(
        assetManager: android.content.res.AssetManager,
        fromAssetPath: String,
        toPath: String
    ) {
        try {
            val files = assetManager.list(fromAssetPath) ?: return
            File(toPath).mkdirs()
            for (file in files) {
                val fromPath = "$fromAssetPath/$file"
                val toFile = "$toPath/$file"
                try {
                    // Try as file first
                    val input = assetManager.open(fromPath)
                    val output = FileOutputStream(toFile)
                    input.copyTo(output)
                    input.close()
                    output.close()
                } catch (e: IOException) {
                    // It's a directory — recurse
                    copyAssetFolder(assetManager, fromPath, toFile)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error copying assets: ${e.message}")
        }
    }
}
