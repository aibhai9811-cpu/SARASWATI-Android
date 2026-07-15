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
    private var isSpeaking = false
    private var conversationMode = false
    private var wakeWordPaused = false
    private var ttsReady = false
    private var isProcessingCommand = false

    private val WAKE_WORDS = listOf("hey saraswati", "saraswati", "hi saraswati", "ok saraswati")
    private val EXIT_WORDS = listOf("stop", "exit", "goodbye", "go to sleep", "bye saraswati", "sleep")

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var commandProcessor: CommandProcessor
    private lateinit var openRouterClient: OpenRouterClient

    private var lastTranscript = ""
    private var lastTranscriptTime = 0L
    private val DUPLICATE_GUARD_MS = 3000L

    // ── Single persistent recognizer session ──
    // Instead of restarting every few seconds, we keep ONE session open continuously
    private var sessionActive = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("SARASWATI — Initializing..."))
        tts = TextToSpeech(this, this)
        commandProcessor = CommandProcessor(this)
        openRouterClient = OpenRouterClient(this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("en", "IN")
            tts?.setSpeechRate(0.92f)
            tts?.setPitch(0.85f)
            ttsReady = true

            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                    stopCurrentRecognizer() // stop mic while speaking
                    Handler(Looper.getMainLooper()).post {
                        uiCallback?.invoke("speaking", "", "")
                        updateNotification("Speaking...")
                    }
                }
                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    Handler(Looper.getMainLooper()).postDelayed({
                        // Resume listening only if we should be
                        if (!wakeWordPaused && !isProcessingCommand) {
                            Handler(Looper.getMainLooper()).post {
                                uiCallback?.invoke("idle", "", "")
                                updateNotification("Listening — Say \"Hey Saraswati\"")
                            }
                            startContinuousListening()
                        }
                    }, 1500) // 1.5s delay so speaker echo dies down
                }
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    if (!wakeWordPaused && !isProcessingCommand) startContinuousListening()
                }
            })

            Handler(Looper.getMainLooper()).postDelayed({
                speak("Namaste. I am SARASWATI, your personal AI assistant. Say Hey Saraswati anytime to activate me.")
            }, 1000)

            // Fallback: if TTS fails to trigger onDone, start listening after 5 seconds
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isListening && !sessionActive && !isSpeaking) {
                    wakeWordPaused = false
                    startContinuousListening()
                }
            }, 5000)
        }
    }

    // ── CONTINUOUS LISTENING — one long session, no repeated restarts ──
    private fun startContinuousListening() {
        if (isSpeaking || isProcessingCommand || wakeWordPaused || sessionActive) return

        Handler(Looper.getMainLooper()).post {
            if (isSpeaking || isProcessingCommand || sessionActive) return@post

            stopCurrentRecognizer()
            sessionActive = true
            isListening = true
            uiCallback?.invoke("idle", "", "")

            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object : RecognitionListener {

                override fun onReadyForSpeech(params: Bundle?) {
                    // Update UI to show we're ready but don't make noise
                    Handler(Looper.getMainLooper()).post {
                        uiCallback?.invoke("idle", "", "")
                        updateNotification("Listening — Say \"Hey Saraswati\"")
                    }
                }

                override fun onBeginningOfSpeech() {
                    if (!isSpeaking) uiCallback?.invoke("listening", "", "")
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    sessionActive = false
                    if (isSpeaking || isProcessingCommand) return

                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val transcript = matches?.firstOrNull()?.lowercase()?.trim() ?: run {
                        // No speech detected — silently restart
                        if (!wakeWordPaused && !isSpeaking) {
                            Handler(Looper.getMainLooper()).postDelayed({
                                startContinuousListening()
                            }, 200)
                        }
                        return
                    }

                    handleTranscript(transcript)
                }

                override fun onError(error: Int) {
                    isListening = false
                    sessionActive = false
                    if (isSpeaking || isProcessingCommand || wakeWordPaused) return

                    // Silently restart — no notification, no sound
                    val delay = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> 300L
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 300L
                        SpeechRecognizer.ERROR_NETWORK -> 2000L
                        9 -> { wakeWordPaused = true; return }
                        else -> 500L
                    }
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!isSpeaking && !isProcessingCommand && !wakeWordPaused)
                            startContinuousListening()
                    }, delay)
                }

                override fun onEndOfSpeech() {}
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
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
            }
            try {
                recognizer?.startListening(intent)
            } catch (e: Exception) {
                sessionActive = false
                isListening = false
            }
        }
    }

    private fun stopCurrentRecognizer() {
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
            recognizer = null
        } catch (e: Exception) {}
        isListening = false
        sessionActive = false
    }

    private fun handleTranscript(transcript: String) {
        if (isSpeaking) {
            // Picked up our own voice — ignore completely
            Handler(Looper.getMainLooper()).postDelayed({ startContinuousListening() }, 500)
            return
        }

        // Duplicate guard
        val now = System.currentTimeMillis()
        if (transcript == lastTranscript && (now - lastTranscriptTime) < DUPLICATE_GUARD_MS) {
            Handler(Looper.getMainLooper()).postDelayed({ startContinuousListening() }, 300)
            return
        }
        lastTranscript = transcript
        lastTranscriptTime = now

        // Exit conversation mode
        if (conversationMode && EXIT_WORDS.any { transcript.contains(it) }) {
            conversationMode = false
            isProcessingCommand = false
            uiCallback?.invoke("idle", transcript, "")
            speak("Going to sleep. Say Hey Saraswati to wake me.")
            return
        }

        // In conversation mode — process as command
        if (conversationMode) {
            isProcessingCommand = true
            wakeWordPaused = true
            uiCallback?.invoke("thinking", transcript, "")
            processCommand(transcript)
            return
        }

        // Check for wake word
        val heardWakeWord = WAKE_WORDS.any { transcript.contains(it) }
        if (heardWakeWord) {
            var command = transcript
            WAKE_WORDS.sortedByDescending { it.length }.forEach { w ->
                command = command.replace(w, "").trim()
            }
            conversationMode = true

            if (command.length > 2) {
                isProcessingCommand = true
                wakeWordPaused = true
                uiCallback?.invoke("thinking", command, "")
                processCommand(command)
            } else {
                speak("Yes, I'm listening.")
            }
        } else {
            // Not a wake word — restart silently
            Handler(Looper.getMainLooper()).postDelayed({ startContinuousListening() }, 200)
        }
    }

    private fun processCommand(command: String) {
        serviceScope.launch {
            uiCallback?.invoke("thinking", command, "")
            updateNotification("Processing...")

            val phoneResult = commandProcessor.tryHandleLocally(command)
            if (phoneResult != null) {
                isProcessingCommand = false
                wakeWordPaused = false
                uiCallback?.invoke("speaking", "", phoneResult)
                speak(phoneResult)
                return@launch
            }

            val cal = Calendar.getInstance()
            val hour = cal.get(Calendar.HOUR).let { if (it == 0) 12 else it }
            val min = String.format("%02d", cal.get(Calendar.MINUTE))
            val ampm = if (cal.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
            val day = arrayOf("Sunday","Monday","Tuesday","Wednesday","Thursday","Friday","Saturday")[cal.get(Calendar.DAY_OF_WEEK)-1]
            val date = "${cal.get(Calendar.DAY_OF_MONTH)}/${cal.get(Calendar.MONTH)+1}/${cal.get(Calendar.YEAR)}"

            val systemPrompt = """ROLE: You are SARASWATI, a personal AI assistant app on Android.
IDENTITY RULES (NEVER BREAK THESE):
- Your name is SARASWATI. Always. No exceptions.
- You were created by your user, not by any company.
- NEVER say you are Claude. NEVER mention Anthropic. NEVER say you are an AI made by any company.
- If asked who made you, say: "I was created by you, as your personal assistant."
- If asked your name, say: "I am SARASWATI, your personal AI assistant."

RESPONSE RULES:
- Maximum 1 sentence. Be direct and helpful.
- No bullet points. No markdown. Plain speech only.
- Current time: $hour:$min $ampm | Day: $day | Date: $date IST

Now answer this user query:"""

            val reply = openRouterClient.chat(systemPrompt, command)
            isProcessingCommand = false
            wakeWordPaused = false
            uiCallback?.invoke("speaking", "", reply)
            speak(reply)
        }
    }

    fun speak(text: String) {
        if (!ttsReady) return
        stopCurrentRecognizer()
        wakeWordPaused = true
        updateNotification("Speaking...")
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "s_utt")
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "s_utt")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "SARASWATI", NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SARASWATI")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setSound(null)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        stopCurrentRecognizer()
        tts?.stop()
        tts?.shutdown()
        instance = null
    }
}
