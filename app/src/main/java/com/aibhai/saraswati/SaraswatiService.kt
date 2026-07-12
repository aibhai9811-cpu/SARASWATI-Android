package com.aibhai.saraswati

import android.app.*
import android.content.Intent
import android.os.*
import android.speech.*
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.*

class SaraswatiService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val CHANNEL_ID = "saraswati_channel"
        const val NOTIFICATION_ID = 1
        var uiCallback: ((state: String, userText: String, aiText: String) -> Unit)? = null
        var instance: SaraswatiService? = null
    }

    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private var conversationMode = false
    private var wakeWordPaused = false
    private var ttsReady = false

    private val WAKE_WORDS = listOf("hey saraswati", "saraswati", "hi saraswati", "ok saraswati")
    private val EXIT_WORDS = listOf("stop", "exit", "goodbye", "go to sleep", "bye saraswati")

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var commandProcessor: CommandProcessor
    private lateinit var openRouterClient: OpenRouterClient

    private var lastTranscript = ""
    private var lastTranscriptTime = 0L
    private val DUPLICATE_GUARD_MS = 2500L
    private var restartScheduled = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Standby — Say \"Hey Saraswati\""))

        tts = TextToSpeech(this, this)
        commandProcessor = CommandProcessor(this)
        openRouterClient = OpenRouterClient(this)
    }

    override fun onTtsInit(status: Int) = onInit(status)

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("en", "IN")
            tts?.setSpeechRate(0.9f)
            tts?.setPitch(0.85f)
            ttsReady = true
            Handler(Looper.getMainLooper()).postDelayed({
                startWakeWordListening()
                speak("Namaste. I am SARASWATI. Say Hey Saraswati to activate me.")
            }, 1000)
        }
    }

    // ── SPEECH RECOGNITION ──

    fun startWakeWordListening() {
        if (wakeWordPaused || isListening) return
        Handler(Looper.getMainLooper()).post {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object : RecognitionListener {

                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    updateNotification("Listening for \"Hey Saraswati\"...")
                    uiCallback?.invoke("idle", "", "")
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val transcript = matches?.firstOrNull()?.lowercase()?.trim() ?: return
                    handleTranscript(transcript)
                }

                override fun onError(error: Int) {
                    isListening = false
                    // Silently restart on no-speech, network, etc.
                    if (!wakeWordPaused && !restartScheduled) scheduleRestart(1200)
                }

                override fun onEndOfSpeech() {}
                override fun onBeginningOfSpeech() { uiCallback?.invoke("listening", "", "") }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onRmsChanged(rmsdB: Float) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            try { recognizer?.startListening(intent) } catch (e: Exception) {
                if (!restartScheduled) scheduleRestart(1000)
            }
        }
    }

    private fun handleTranscript(transcript: String) {
        val now = System.currentTimeMillis()

        // Duplicate guard
        if (transcript == lastTranscript && (now - lastTranscriptTime) < DUPLICATE_GUARD_MS) {
            if (!wakeWordPaused && !restartScheduled) scheduleRestart(800)
            return
        }
        lastTranscript = transcript
        lastTranscriptTime = now

        // Check exit words (in conversation mode)
        if (conversationMode && EXIT_WORDS.any { transcript.contains(it) }) {
            conversationMode = false
            wakeWordPaused = false
            uiCallback?.invoke("idle", transcript, "")
            speak("Going to sleep. Say Hey Saraswati to wake me again.")
            scheduleRestart(3000)
            return
        }

        // In conversation mode — process as command directly
        if (conversationMode) {
            uiCallback?.invoke("thinking", transcript, "")
            wakeWordPaused = true
            processCommand(transcript)
            return
        }

        // Check for wake word
        val heardWakeWord = WAKE_WORDS.any { transcript.contains(it) }
        if (heardWakeWord) {
            var command = transcript
            WAKE_WORDS.forEach { command = command.replace(it, "").trim() }

            conversationMode = true
            wakeWordPaused = true

            if (command.length > 2) {
                // Wake word + command in one breath
                uiCallback?.invoke("thinking", command, "")
                processCommand(command)
            } else {
                // Just the wake word — acknowledge
                speak("Yes, I'm listening.")
                scheduleRestart(1500)
                wakeWordPaused = false
            }
        } else {
            // Not a wake word, not in conversation mode — just keep listening
            if (!restartScheduled) scheduleRestart(400)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (restartScheduled) return
        restartScheduled = true
        Handler(Looper.getMainLooper()).postDelayed({
            restartScheduled = false
            if (!wakeWordPaused && !isListening) startWakeWordListening()
        }, delayMs)
    }

    // ── COMMAND PROCESSING ──

    private fun processCommand(command: String) {
        serviceScope.launch {
            uiCallback?.invoke("thinking", command, "")
            updateNotification("Processing: $command")

            // First check if it's a phone action command (no AI needed)
            val phoneResult = commandProcessor.tryHandleLocally(command)
            if (phoneResult != null) {
                uiCallback?.invoke("speaking", "", phoneResult)
                speak(phoneResult)
                afterSpeak()
                return@launch
            }

            // Otherwise send to AI
            val now = java.util.Calendar.getInstance()
            val timeStr = String.format("%02d:%02d %s",
                now.get(java.util.Calendar.HOUR),
                now.get(java.util.Calendar.MINUTE),
                if (now.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) "AM" else "PM")
            val dateStr = "${now.get(java.util.Calendar.DAY_OF_MONTH)}/${now.get(java.util.Calendar.MONTH)+1}/${now.get(java.util.Calendar.YEAR)}"

            val systemPrompt = """You are SARASWATI (Self-Aware Responsive A.I. System for Wisdom, Assistance, Tasks & Information).
Current time: $timeStr IST | Date: $dateStr
Personality: calm, warm, wise. Keep responses SHORT (1-2 sentences max) — optimized for voice output.
Never use markdown, asterisks, or bullet points in your response — plain speech only."""

            val reply = openRouterClient.chat(systemPrompt, command)
            uiCallback?.invoke("speaking", "", reply)
            speak(reply)
            afterSpeak()
        }
    }

    private fun afterSpeak() {
        wakeWordPaused = false
        updateNotification("Conversation mode — Say \"stop\" to end")
        scheduleRestart(800)
    }

    // ── TEXT TO SPEECH ──

    fun speak(text: String) {
        tts?.stop()
        uiCallback?.invoke("speaking", "", "")
        updateNotification("Speaking...")
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "saraswati_utt")
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "saraswati_utt")
    }

    // ── NOTIFICATION ──

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SARASWATI Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SARASWATI is running in background"
                setSound(null, null) // NO sound for this notification
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SARASWATI")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true) // KEY: silent notification = no ding sound
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        instance = null
    }
}
