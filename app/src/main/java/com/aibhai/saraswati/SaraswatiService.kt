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
    private var lastCommandTime = 0L
    private val COMMAND_COOLDOWN = 2500L
    private var lastTranscript = ""
    private var lastTranscriptTime = 0L
    private val DUPLICATE_GUARD = 3000L

    // Controls mic restart — we restart ONCE after each cycle, not in a loop
    private var restartPending = false

    private val WAKE_WORDS = listOf("hey saraswati", "saraswati", "hi saraswati", "ok saraswati", "hello saraswati")
    private val EXIT_WORDS = listOf("stop", "exit", "goodbye", "go to sleep", "bye", "band karo", "ruko")

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var commandProcessor: CommandProcessor
    private lateinit var openRouterClient: OpenRouterClient

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("SARASWATI — Starting..."))
        tts = TextToSpeech(this, this)
        commandProcessor = CommandProcessor(this)
        openRouterClient = OpenRouterClient(this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Use en-IN — reliable on all phones
            tts?.language = Locale("en", "IN")
            tts?.setSpeechRate(0.88f)
            tts?.setPitch(0.9f)
            ttsReady = true

            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                    destroyRecognizer()
                }
                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!wakeWordPaused && !isProcessingCommand) {
                            uiCallback?.invoke("idle", "", "")
                            scheduleListenRestart(1000)
                        }
                    }, 800)
                }
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!wakeWordPaused && !isProcessingCommand)
                            scheduleListenRestart(500)
                    }, 500)
                }
            })

            // Start listening after 2 seconds
            Handler(Looper.getMainLooper()).postDelayed({
                updateNotification("Say \"Hey Saraswati\" to activate")
                uiCallback?.invoke("idle", "", "")
                scheduleListenRestart(0)
            }, 2000)
        }
    }

    // ── SCHEDULE A SINGLE RESTART — never overlapping ──
    private fun scheduleListenRestart(delayMs: Long) {
        if (restartPending || isSpeaking || isProcessingCommand || wakeWordPaused) return
        restartPending = true
        Handler(Looper.getMainLooper()).postDelayed({
            restartPending = false
            if (!isSpeaking && !isProcessingCommand && !wakeWordPaused && !isListening)
                startListeningSession()
        }, delayMs)
    }

    private fun startListeningSession() {
        if (isSpeaking || isProcessingCommand || wakeWordPaused || isListening) return

        destroyRecognizer()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                updateNotification("Listening...")
            }

            override fun onBeginningOfSpeech() {
                uiCallback?.invoke("listening", "", "")
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val transcript = matches?.firstOrNull()?.trim() ?: run {
                    scheduleListenRestart(300)
                    return
                }
                if (transcript.isNotBlank()) handleTranscript(transcript)
                else scheduleListenRestart(300)
            }

            override fun onError(error: Int) {
                isListening = false
                if (isSpeaking || isProcessingCommand || wakeWordPaused) return
                val delay = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 500L
                    SpeechRecognizer.ERROR_NETWORK -> 3000L
                    9 -> { wakeWordPaused = true; return } // not allowed
                    else -> 800L
                }
                scheduleListenRestart(delay)
            }

            override fun onEndOfSpeech() { isListening = false }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // Use en-IN — works on ALL Android phones without extra downloads
            // Hindi words spoken in English transliteration also get recognized
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
        }

        try {
            recognizer?.startListening(intent)

            // Safety timeout — if stuck listening for 12 seconds, force restart
            Handler(Looper.getMainLooper()).postDelayed({
                if (isListening) {
                    isListening = false
                    destroyRecognizer()
                    scheduleListenRestart(500)
                }
            }, 12000)

        } catch (e: Exception) {
            isListening = false
            scheduleListenRestart(1000)
        }
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
        } catch (e: Exception) {}
        recognizer = null
        isListening = false
    }

    private fun handleTranscript(rawTranscript: String) {
        if (isSpeaking) { scheduleListenRestart(1000); return }

        val transcript = rawTranscript.lowercase().trim()

        // Duplicate guard
        val now = System.currentTimeMillis()
        if (transcript == lastTranscript && (now - lastTranscriptTime) < DUPLICATE_GUARD) {
            scheduleListenRestart(300); return
        }
        lastTranscript = transcript
        lastTranscriptTime = now

        // Exit words
        if (conversationMode && EXIT_WORDS.any { transcript.contains(it) }) {
            conversationMode = false
            isProcessingCommand = false
            uiCallback?.invoke("idle", transcript, "")
            speak("Theek hai. Phir milenge.")
            return
        }

        // Conversation mode — direct command
        if (conversationMode) {
            if (now - lastCommandTime < COMMAND_COOLDOWN) {
                scheduleListenRestart(300); return
            }
            lastCommandTime = now
            isProcessingCommand = true
            wakeWordPaused = true
            uiCallback?.invoke("thinking", rawTranscript, "")
            processCommand(rawTranscript)
            return
        }

        // Check wake word
        val heardWake = WAKE_WORDS.any { transcript.contains(it) }
        if (heardWake) {
            var command = transcript
            WAKE_WORDS.sortedByDescending { it.length }.forEach { w ->
                command = command.replace(w, "").trim()
            }
            conversationMode = true

            if (command.length > 2) {
                lastCommandTime = now
                isProcessingCommand = true
                wakeWordPaused = true
                uiCallback?.invoke("thinking", command, "")
                processCommand(command)
            } else {
                speak("Haan, boliye.")
            }
        } else {
            scheduleListenRestart(300)
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
            val h = cal.get(Calendar.HOUR).let { if (it == 0) 12 else it }
            val m = String.format("%02d", cal.get(Calendar.MINUTE))
            val ap = if (cal.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"

            val systemPrompt = """You are SARASWATI, a personal AI assistant app on Android phone.
STRICT IDENTITY RULES:
- Your name is SARASWATI. Never say you are Claude or any other AI.
- You were created by your user as a personal assistant.
- If asked who made you: "Aapne mujhe banaya hai apni madad ke liye."
- If asked your name: "Main SARASWATI hoon, aapki personal AI assistant."

RESPONSE RULES:
- Reply in the SAME language the user speaks. Hindi → Hindi. English → English.
- Maximum 1-2 short sentences only. Be direct.
- No bullet points. No markdown. Plain speech only.
- Current time: $h:$m $ap IST

User said: $command"""

            val reply = openRouterClient.chat(systemPrompt, command)
            isProcessingCommand = false
            wakeWordPaused = false
            uiCallback?.invoke("speaking", "", reply)
            speak(reply)
        }
    }

    fun speak(text: String) {
        if (!ttsReady) return
        destroyRecognizer()
        wakeWordPaused = true
        isSpeaking = true

        // Always use en-IN for reliable TTS on all phones
        // hi-IN TTS often not installed on budget Android phones
        tts?.language = Locale("en", "IN")

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "s_utt")
        }
        updateNotification("Speaking...")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "s_utt")

        // Fallback timer — force resume if onDone never fires
        val timeout = (text.length * 80L) + 4000L
        Handler(Looper.getMainLooper()).postDelayed({
            if (isSpeaking) {
                isSpeaking = false
                wakeWordPaused = false
                isProcessingCommand = false
                uiCallback?.invoke("idle", "", "")
                scheduleListenRestart(500)
            }
        }, timeout)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "SARASWATI", NotificationManager.IMPORTANCE_MIN
            ).apply {
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
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
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
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
        destroyRecognizer()
        tts?.stop()
        tts?.shutdown()
        instance = null
    }
}
