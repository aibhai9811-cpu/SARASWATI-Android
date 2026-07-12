package com.aibhai.saraswati

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var chatContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var apiKeyInput: EditText
    private lateinit var saveKeyBtn: Button
    private lateinit var setupLayout: LinearLayout
    private lateinit var mainLayout: LinearLayout
    private lateinit var arcReactorView: ArcReactorView
    private lateinit var stateLabel: TextView
    private lateinit var clockText: TextView

    private val PERMISSIONS = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_PHONE_STATE,
    ).let {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            it + Manifest.permission.POST_NOTIFICATIONS
        else it
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        startClock()

        val prefs = getSharedPreferences("saraswati", MODE_PRIVATE)
        val savedKey = prefs.getString("api_key", "") ?: ""

        if (savedKey.isEmpty()) {
            setupLayout.visibility = View.VISIBLE
            mainLayout.visibility = View.GONE
        } else {
            showMain()
            requestPermissionsAndStart()
        }

        saveKeyBtn.setOnClickListener {
            val key = apiKeyInput.text.toString().trim()
            if (key.startsWith("sk-or-") || key.startsWith("sk-")) {
                prefs.edit().putString("api_key", key).apply()
                showMain()
                requestPermissionsAndStart()
            } else {
                Toast.makeText(this, "Invalid key. Must start with sk-or-", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun bindViews() {
        statusText     = findViewById(R.id.statusText)
        chatContainer  = findViewById(R.id.chatContainer)
        scrollView     = findViewById(R.id.scrollView)
        apiKeyInput    = findViewById(R.id.apiKeyInput)
        saveKeyBtn     = findViewById(R.id.saveKeyBtn)
        setupLayout    = findViewById(R.id.setupLayout)
        mainLayout     = findViewById(R.id.mainLayout)
        arcReactorView = findViewById(R.id.arcReactor)
        stateLabel     = findViewById(R.id.stateLabel)
        clockText      = findViewById(R.id.clockText)
    }

    private fun showMain() {
        setupLayout.visibility = View.GONE
        mainLayout.visibility  = View.VISIBLE
        addMessage("SARASWATI", "Namaste. I am SARASWATI, your personal A.I. system. All systems are online.\n\nSay \"Hey Saraswati\" to activate me.\n\nYou can say things like:\n• Call [name or number]\n• WhatsApp [name]: [message]\n• Open [app name]\n• Search [query]\n• What time is it\n• Set alarm for [time]", false)
    }

    private fun startClock() {
        val handler = android.os.Handler(mainLooper)
        val runnable = object : Runnable {
            override fun run() {
                val now = java.util.Calendar.getInstance()
                clockText.text = String.format("%02d:%02d:%02d",
                    now.get(java.util.Calendar.HOUR_OF_DAY),
                    now.get(java.util.Calendar.MINUTE),
                    now.get(java.util.Calendar.SECOND))
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(runnable)
    }

    private fun requestPermissionsAndStart() {
        val missing = PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startSaraswatiService()
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) startSaraswatiService()
    }

    private fun startSaraswatiService() {
        val intent = Intent(this, SaraswatiService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        SaraswatiService.uiCallback = { state, userText, aiText ->
            runOnUiThread {
                updateState(state)
                if (userText.isNotEmpty()) addMessage("You", userText, true)
                if (aiText.isNotEmpty()) addMessage("SARASWATI", aiText, false)
            }
        }
    }

    fun updateState(state: String) {
        stateLabel.text = state
        arcReactorView.setState(state)
        statusText.text = when (state) {
            "listening" -> "VOICE INPUT ACTIVE"
            "thinking"  -> "PROCESSING QUERY..."
            "speaking"  -> "AUDIO OUTPUT ACTIVE"
            else        -> "ALL SYSTEMS NOMINAL"
        }
    }

    fun addMessage(sender: String, text: String, isUser: Boolean) {
        val tv = TextView(this).apply {
            this.text = if (isUser) text else "[$sender]\n$text"
            textSize = 13f
            setTextColor(if (isUser) 0xFF99CCFF.toInt() else 0xFFC5EAFF.toInt())
            setPadding(24, 16, 24, 16)
            setBackgroundResource(if (isUser) R.drawable.bg_user_bubble else R.drawable.bg_ai_bubble)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (isUser) android.view.Gravity.END else android.view.Gravity.START
                setMargins(if (isUser) 80 else 0, 8, if (isUser) 0 else 80, 8)
            }
            layoutParams = lp
        }
        chatContainer.addView(tv)
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        SaraswatiService.uiCallback = null
    }
}
