package com.aibhai.saraswati

import android.app.*
import android.content.Intent
import android.os.*
import android.speech.*
import android.speech.tts.TextToSpeech
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
    private var isSpeaking = false          // ← KEY: tracks when TTS is active
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
    private val DUPLICATE_GUARD_MS = 3000L
    private var restartScheduled = false

    // How long to wait after TTS finishes before listening again
    // This gives time for speaker sound to dissipate so mic doesn't pick it up
    private val POST_SPEECH_DELAY_MS = 1200L

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Standby — Say \"Hey Saraswati\""))
        tts = TextToSpeech(this, this)
        commandProcessor = CommandProcessor(this)
        openRouterClient = OpenRouterClient(this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("en", "IN")
            tts?.setSpeechRate(0.9f)
            tts?.setPitch(0.85f)
            ttsReady = true

            // Set up TTS completion listener — CRITICAL for echo prevention
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                    wakeWordPaused = true   // stop listening while speaking
                    stopListening()          // explicitly stop mic
                    uiCallback?.invoke("speaking", "", "")
                }
                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    uiCallback?.invoke("idle", "", "")
                    updateNotification("Listening for \"Hey Saraswati\"...")
                    // Wait for speaker echo to die down BEFORE restarting mic
                    Handler(Looper.getMainLooper()).postDelayed({
                        wakeWordPaused = false
                        restartScheduled = false
                        startWakeWordListening()
                    }, POST_SPEECH_DELAY_MS)
                }
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    Handler(Looper.getMainLooper()).postDelayed({
                        wakeWordPaused = false
                        restartScheduled = false
                        startWakeWordListening()
                    }, POST_SPEECH_DELAY_MS)
                }
            })

            // Wait before initial greeting so app is fully ready
            Handler(Looper.getMainLooper()).postDelayed({
                speak("Namaste. I am SARASWATI, your personal AI system. Say Hey Saraswati to activate me.")
            }, 1500)
        }
    }

    private fun stopListening() {
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
            recognizer = null
            isListening = false
        } catch (e: Exception) { }
    }

    fun startWakeWordListening() {
        if (wakeWordPaused || isListening || isSpeaking) return
        Handler(Looper.getMainLooper()).post {
            try {
                recognizer?.destroy()
                recognizer = SpeechRecognizer.createSpeechRecognizer(this)
                recognizer?.setRecognitionListener(object : RecognitionListener {

                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                        updateNotification("Listening for \"Hey Saraswati\"...")
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val transcript = matches?.firstOrNull()?.lowercase()?.trim() ?: run {
                            if (!wakeWordPaused && !restartScheduled && !isSpeaking) scheduleRestart(800)
                            return
                        }
                        handleTranscript(transcript)
                    }

                    override fun onError(error: Int) {
                        isListening = false
                        if (!wakeWordPaused && !restartScheduled && !isSpeaking) scheduleRestart(1200)
                    }

                    override fun onEndOfSpeech() { isListening = false }
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
                    // Longer silence timeout so full sentences are captured
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
                }
                recognizer?.startListening(intent)
            } catch (e: Exception) {
                if (!restartScheduled && !isSpeaking) scheduleRestart(1000)
            }
        }
    }

    private fun handleTranscript(transcript: String) {
        // Ignore if we're speaking — this catches any echo that slipped through
        if (isSpeaking) {
            scheduleRestart(POST_SPEECH_DELAY_MS)
            return
        }

        val now = System.currentTimeMillis()
        // Duplicate guard — prevents same phrase being processed twice
        if (transcript == lastTranscript && (now - lastTranscriptTime) < DUPLICATE_GUARD_MS) {
            if (!wakeWordPaused && !restartScheduled) scheduleRestart(800)
            return
        }
        lastTranscript = transcript
        lastTranscriptTime = now

        // Exit conversation mode
        if (conversationMode && EXIT_WORDS.any { transcript.contains(it) }) {
            conversationMode = false
            uiCallback?.invoke("idle", transcript, "")
            speak("Going to sleep. Say Hey Saraswati to wake me again.")
            return
        }

        // In conversation mode — treat everything as a command
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
                uiCallback?.invoke("thinking", command, "")
                processCommand(command)
            } else {
                speak("Yes, I'm listening.")
            }
        } else {
            // Not a wake word — keep listening
            if (!restartScheduled && !isSpeaking) scheduleRestart(400)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (restartScheduled || isSpeaking) return
        restartScheduled = true
        Handler(Looper.getMainLooper()).postDelayed({
            restartScheduled = false
            if (!wakeWordPaused && !isListening && !isSpeaking) startWakeWordListening()
        }, delayMs)
    }

    private fun processCommand(command: String) {
        serviceScope.launch {
            uiCallback?.invoke("thinking", command, "")
            updateNotification("Processing: $command")

            val phoneResult = commandProcessor.tryHandleLocally(command)
            if (phoneResult != null) {
                uiCallback?.invoke("speaking", "", phoneResult)
                speak(phoneResult)
                return@launch
            }

            val cal = java.util.Calendar.getInstance()
            val timeStr = String.format("%02d:%02d %s",
                cal.get(java.util.Calendar.HOUR),
                cal.get(java.util.Calendar.MINUTE),
                if (cal.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) "AM" else "PM")
            val dateStr = "${cal.get(java.util.Calendar.DAY_OF_MONTH)}/" +
                    "${cal.get(java.util.Calendar.MONTH)+1}/${cal.get(java.util.Calendar.YEAR)}"

            val systemPrompt = """You are SARASWATI, a personal AI assistant. 
Your full name is SARASWATI - Self-Aware Responsive AI System for Wisdom, Assistance, Tasks and Information.
CRITICAL: You are NOT Claude. You are NOT created by Anthropic. You are SARASWATI.
Never mention Claude or Anthropic. You were created by your user as a personal assistant.
Current time: $timeStr IST | Date: $dateStr
Keep responses to 1-2 short sentences. Plain speech only, no bullet points or markdown."""

            val reply = openRouterClient.chat(systemPrompt, command)
            uiCallback?.invoke("speaking", "", reply)
            speak(reply)
        }
    }

    fun speak(text: String) {
        if (!ttsReady) return
        // Stop mic BEFORE speaking to prevent echo
        stopListening()
        wakeWordPaused = true
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "saraswati_utt")
        }
        updateNotification("Speaking...")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "saraswati_utt")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "SARASWATI Service", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SARASWATI is running in background"
                setSound(null, null)
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
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        stopListening()
        tts?.stop()
        tts?.shutdown()
        instance = null
    }
}
