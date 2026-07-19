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
    private var ttsReady = false

    // ── VOSK wake word detector ──
    private var wakeWordDetector: WakeWordDetector? = null
    private var voskReady = false

    // ── Command recognizer (SpeechRecognizer — only used AFTER wake word) ──
    private var commandRecognizer: SpeechRecognizer? = null
    private var isCapturingCommand = false
    private var isSpeaking = false
    private var isProcessingCommand = false

    private val EXIT_WORDS = listOf("stop", "exit", "goodbye", "go to sleep",
        "bye", "band karo", "ruko", "sleep", "saraswati stop")

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var commandProcessor: CommandProcessor
    private lateinit var openRouterClient: OpenRouterClient

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("SARASWATI — Initializing..."))

        commandProcessor = CommandProcessor(this)
        openRouterClient = OpenRouterClient(this)
        tts = TextToSpeech(this, this)

        // Initialize Vosk wake word detector
        initVosk()
    }

    // ── VOSK INITIALIZATION ──
    private fun initVosk() {
        updateNotification("Loading wake word model...")
        wakeWordDetector = WakeWordDetector(
            context = this,
            onWakeWordDetected = { command ->
                if (!isSpeaking && !isProcessingCommand && !isCapturingCommand) {
                    handleWakeWordDetected(command)
                }
            },
            onError = { error ->
                // Vosk failed — fall back to tap-to-talk only
                updateNotification("Say tap mic to talk | Vosk: $error")
                voskReady = false
            }
        )

        wakeWordDetector?.initialize {
            // Model loaded — start silent listening
            voskReady = true
            updateNotification("Say \"Hey Saraswati\" to activate")
            wakeWordDetector?.startListening()
            uiCallback?.invoke("idle", "", "")
        }
    }

    // ── WAKE WORD DETECTED by Vosk ──
    private fun handleWakeWordDetected(commandAfterWakeWord: String) {
        wakeWordDetector?.pause() // pause vosk while we handle command
        uiCallback?.invoke("listening", "", "")

        if (commandAfterWakeWord.length > 2) {
            // Full command in one breath — process directly
            uiCallback?.invoke("thinking", commandAfterWakeWord, "")
            processCommand(commandAfterWakeWord)
        } else {
            // Just wake word heard — immediately open mic for command
            // DON'T speak first (causes delay) — just open mic right away
            Handler(Looper.getMainLooper()).postDelayed({
                captureCommand()
            }, 300)
        }
    }

    // ── CAPTURE COMMAND via SpeechRecognizer (only after wake word) ──
    fun captureCommand() {
        if (isCapturingCommand || isSpeaking) return
        isCapturingCommand = true
        uiCallback?.invoke("listening", "", "")
        updateNotification("Listening for your command...")

        commandRecognizer?.destroy()
        commandRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        commandRecognizer?.setRecognitionListener(object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(e: Int, p: Bundle?) {}
            override fun onEndOfSpeech() {}

            override fun onResults(results: Bundle?) {
                isCapturingCommand = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val transcript = matches?.firstOrNull()?.trim() ?: ""

                if (transcript.isNotBlank()) {
                    val lower = transcript.lowercase()
                    if (EXIT_WORDS.any { lower.contains(it) }) {
                        // User said stop/exit — go back to wake word mode
                        uiCallback?.invoke("idle", "", "")
                        speak("Theek hai. Jab zarurat ho, Hey Saraswati boliye.")
                        // resumeVosk() called from TTS onDone after speaking
                    } else {
                        // Process command — mic will reopen after response via TTS onDone
                        uiCallback?.invoke("thinking", transcript, "")
                        processCommand(transcript)
                    }
                } else {
                    // Nothing heard — stay in command mode, try again
                    uiCallback?.invoke("listening", "", "")
                    Handler(Looper.getMainLooper()).postDelayed({
                        captureCommand()
                    }, 500)
                }
            }

            override fun onError(error: Int) {
                isCapturingCommand = false
                uiCallback?.invoke("idle", "", "")
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // No speech — try again once more before going back to vosk
                        Handler(Looper.getMainLooper()).postDelayed({
                            captureCommand()
                        }, 500)
                    }
                    else -> resumeVosk()
                }
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
        }

        try {
            commandRecognizer?.startListening(intent)
            // 10 second hard timeout
            Handler(Looper.getMainLooper()).postDelayed({
                if (isCapturingCommand) {
                    isCapturingCommand = false
                    commandRecognizer?.stopListening()
                    uiCallback?.invoke("idle", "", "")
                    resumeVosk()
                }
            }, 10000)
        } catch (e: Exception) {
            isCapturingCommand = false
            resumeVosk()
        }
    }

    private fun resumeVosk() {
        isProcessingCommand = false
        isSpeaking = false
        updateNotification("Say \"Hey Saraswati\" to activate")
        uiCallback?.invoke("idle", "", "")
        wakeWordDetector?.resume()
    }

    // ── PROCESS COMMAND ──
    private fun processCommand(command: String) {
        isProcessingCommand = true
        serviceScope.launch {
            uiCallback?.invoke("thinking", command, "")
            updateNotification("Processing...")

            val phoneResult = commandProcessor.tryHandleLocally(command)
            if (phoneResult != null) {
                uiCallback?.invoke("speaking", "", phoneResult)
                speak(phoneResult)
                return@launch
            }

            val cal = Calendar.getInstance()
            val h = cal.get(Calendar.HOUR).let { if (it == 0) 12 else it }
            val m = String.format("%02d", cal.get(Calendar.MINUTE))
            val ap = if (cal.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"

            val systemPrompt = """You are SARASWATI, a personal AI assistant.
IDENTITY: Your name is SARASWATI. Never say you are Claude or made by Anthropic.
If asked who made you: "Aapne mujhe banaya hai."
RESPONSE: 1 sentence only. No markdown. Plain speech.
Time: $h:$m $ap IST"""

            val reply = openRouterClient.chat(systemPrompt, command)
            isProcessingCommand = false
            uiCallback?.invoke("speaking", "", reply)
            speak(reply)
        }
    }

    // ── TTS ──
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("en", "IN")
            tts?.setSpeechRate(0.9f)
            tts?.setPitch(0.9f)
            ttsReady = true

            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                    uiCallback?.invoke("speaking", "", "")
                }
                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    isProcessingCommand = false
                    Handler(Looper.getMainLooper()).postDelayed({
                        // After speaking — keep mic open for follow-up command
                        // This enables conversation mode without repeating wake word
                        if (!isCapturingCommand) captureCommand()
                    }, 800)
                }
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    isProcessingCommand = false
                    Handler(Looper.getMainLooper()).postDelayed({
                        // Fallback: if TTS fails, resume Vosk wake word mode
                        if (!isCapturingCommand) resumeVosk()
                    }, 500)
                }
            })
        }
    }

    fun speak(text: String) {
        if (!ttsReady) return
        isSpeaking = true
        tts?.language = Locale("en", "IN")
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "s_utt")
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "s_utt")

        // Safety fallback — if TTS onDone never fires on this phone,
        // force-reset after estimated speech duration + 3 seconds
        val timeout = (text.length * 80L) + 3000L
        Handler(Looper.getMainLooper()).postDelayed({
            if (isSpeaking) {
                isSpeaking = false
                isProcessingCommand = false
                uiCallback?.invoke("idle", "", "")
                // Open mic for follow-up command
                if (!isCapturingCommand) captureCommand()
            }
        }, timeout)
    }

    // ── NOTIFICATION ──
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

    fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        wakeWordDetector?.stop()
        commandRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        instance = null
    }
}
