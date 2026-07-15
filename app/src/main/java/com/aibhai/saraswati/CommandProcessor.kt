package com.aibhai.saraswati

import android.content.*
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.content.pm.PackageManager
import java.util.*

class CommandProcessor(private val context: Context) {

    fun tryHandleLocally(command: String): String? {
        val cmd = command.lowercase().trim()

        return when {

            // ── WHATSAPP (check before "open" so it catches "open whatsapp and text...") ──
            cmd.contains("whatsapp") -> handleWhatsApp(cmd)

            // ── CALLS ──
            cmd.contains("call") && (cmd.contains("mom") || cmd.contains("mum") ||
                cmd.contains("mummy") || cmd.contains("dad") || cmd.contains("papa") ||
                cmd.startsWith("call ") || cmd.contains(" call ")) -> {
                val name = cmd.replace("make a call to","").replace("call","").trim()
                handleCall(name)
            }

            // ── OPEN APP ──
            cmd.startsWith("open ") && !cmd.contains("whatsapp") ->
                handleOpenApp(cmd.removePrefix("open ").trim())

            // ── SEARCH ──
            cmd.startsWith("search ") || cmd.startsWith("google ") ||
                cmd.startsWith("search for ") || cmd.contains("search on google") ->
                handleSearch(cmd)

            // ── ALARM ──
            cmd.contains("set alarm") || cmd.contains("alarm for") ||
                cmd.contains("wake me") || cmd.contains("alarm at") ->
                handleAlarm(cmd)

            // ── TIME ──
            cmd.contains("what time") || cmd.contains("current time") ||
                cmd == "time" || cmd.contains("kitna baja") -> {
                val now = Calendar.getInstance()
                val hour = now.get(Calendar.HOUR).let { if (it == 0) 12 else it }
                val min = String.format("%02d", now.get(Calendar.MINUTE))
                val ampm = if (now.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
                "The current time is $hour:$min $ampm."
            }

            // ── DATE ──
            cmd.contains("what date") || cmd.contains("today") && cmd.contains("date") ||
                cmd.contains("what day") -> {
                val now = Calendar.getInstance()
                val days = arrayOf("Sunday","Monday","Tuesday","Wednesday","Thursday","Friday","Saturday")
                val months = arrayOf("January","February","March","April","May","June",
                    "July","August","September","October","November","December")
                "Today is ${days[now.get(Calendar.DAY_OF_WEEK)-1]}, " +
                    "${now.get(Calendar.DAY_OF_MONTH)} ${months[now.get(Calendar.MONTH)]}."
            }

            // ── FLASHLIGHT ──
            cmd.contains("flashlight on") || cmd.contains("torch on") -> {
                toggleFlashlight(true); "Flashlight on."
            }
            cmd.contains("flashlight off") || cmd.contains("torch off") -> {
                toggleFlashlight(false); "Flashlight off."
            }

            // ── SETTINGS ──
            cmd.contains("open settings") || cmd.contains("open setting") -> {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                "Opening settings."
            }

            // ── WIFI ──
            cmd.contains("wifi") && (cmd.contains("setting") || cmd.contains("on") || cmd.contains("off")) -> {
                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                "Opening WiFi settings."
            }

            // Not handled locally — send to AI
            else -> null
        }
    }

    private fun handleCall(nameOrNumber: String): String {
        val cleaned = nameOrNumber.replace("to","").replace("and","").trim()
        val number = if (cleaned.all { it.isDigit() || it == '+' || it == '-' || it == ' ' }) {
            cleaned.replace(" ","")
        } else {
            lookupContact(cleaned)
        }

        return if (number != null) {
            try {
                context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                "Calling $cleaned now."
            } catch (e: Exception) {
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                "Opening dialer for $cleaned."
            }
        } else {
            // Try to dial anyway with contact name
            context.startActivity(Intent(Intent.ACTION_DIAL).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            "Contact not found. Opening dialer."
        }
    }

    private fun handleWhatsApp(cmd: String): String {
        // Parse: "whatsapp mummy to hai i am there" or "open whatsapp and text mummy to hai"
        var name = ""
        var message = ""

        // Remove command prefixes
        val cleaned = cmd
            .replace("open whatsapp and text", "")
            .replace("open whatsapp and send", "")
            .replace("open whatsapp", "")
            .replace("send whatsapp to", "")
            .replace("send whatsapp", "")
            .replace("whatsapp message to", "")
            .replace("whatsapp text to", "")
            .replace("whatsapp to", "")
            .replace("whatsapp", "")
            .trim()

        // Find message separator — "to" keyword separates name from message
        // e.g. "mummy to hai i am there" → name=mummy, message=hai i am there
        val toIndex = cleaned.indexOf(" to ")
        if (toIndex > 0) {
            name = cleaned.substring(0, toIndex).trim()
            message = cleaned.substring(toIndex + 4).trim()
        } else {
            name = cleaned.trim()
        }

        // Normalize common names
        name = name.replace("mummy","").replace("mum","").replace("mom","")
            .replace("dad","").replace("papa","").trim().let {
            if (it.isEmpty()) {
                when {
                    cmd.contains("mummy") || cmd.contains("mum") || cmd.contains("mom") -> "mom"
                    cmd.contains("dad") || cmd.contains("papa") -> "dad"
                    else -> cleaned.split(" ").firstOrNull() ?: ""
                }
            } else it
        }

        return try {
            val number = lookupContact(name)
            if (number != null) {
                val cleanNumber = number.replace("[^0-9+]".toRegex(), "")
                val encodedMsg = Uri.encode(message)
                val uri = "https://api.whatsapp.com/send?phone=$cleanNumber" +
                    if (message.isNotEmpty()) "&text=$encodedMsg" else ""
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                if (message.isNotEmpty()) "Opening WhatsApp to send \"$message\" to $name."
                else "Opening WhatsApp chat with $name."
            } else {
                // Try WhatsApp directly
                val intent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
                    ?: context.packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
                if (intent != null) {
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    "Opening WhatsApp. Contact $name not found in contacts."
                } else {
                    "WhatsApp is not installed on this device."
                }
            }
        } catch (e: Exception) {
            // Last resort — open WhatsApp via store or direct
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=com.whatsapp")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                "Please install WhatsApp first."
            } catch (e2: Exception) {
                "Could not open WhatsApp."
            }
        }
    }

    private fun handleOpenApp(appName: String): String {
        val name = appName.lowercase().trim()
        val packageMap = mapOf(
            "chrome" to "com.android.chrome",
            "google" to "com.google.android.googlequicksearchbox",
            "youtube" to "com.google.android.youtube",
            "whatsapp" to "com.whatsapp",
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "camera" to "com.android.camera",
            "calculator" to "com.android.calculator2",
            "clock" to "com.android.deskclock",
            "spotify" to "com.spotify.music",
            "telegram" to "org.telegram.messenger",
            "netflix" to "com.netflix.mediaclient",
            "settings" to "com.android.settings",
            "play store" to "com.android.vending",
            "contacts" to "com.android.contacts",
            "messages" to "com.google.android.apps.messaging",
            "paytm" to "net.one97.paytm",
            "phonepe" to "com.phonepe.app",
            "gpay" to "com.google.android.apps.nbu.paisa.user",
            "google pay" to "com.google.android.apps.nbu.paisa.user",
            "photos" to "com.google.android.apps.photos",
            "files" to "com.google.android.apps.nbu.files",
            "amazon" to "in.amazon.mShop.android.shopping",
            "flipkart" to "com.flipkart.android",
            "zomato" to "com.application.zomato",
            "swiggy" to "in.swiggy.android",
            "ola" to "com.olacabs.customer",
            "uber" to "com.ubercab"
        )

        val pkg = packageMap[name]
            ?: packageMap.entries.firstOrNull { name.contains(it.key) }?.value
            ?: findAppByName(name)

        return if (pkg != null) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                "Opening $appName."
            } else {
                "App not found on this device."
            }
        } else {
            "I couldn't find $appName on your device."
        }
    }

    private fun findAppByName(query: String): String? {
        val pm = context.packageManager
        return try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .firstOrNull { pm.getApplicationLabel(it).toString().lowercase().contains(query) }
                ?.packageName
        } catch (e: Exception) { null }
    }

    private fun handleSearch(cmd: String): String {
        val query = cmd
            .replace("search for", "").replace("search on google", "")
            .replace("search", "").replace("google", "").trim()
        return try {
            context.startActivity(Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra("query", query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            "Searching for $query."
        } catch (e: Exception) {
            context.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            "Searching for $query."
        }
    }

    private fun handleAlarm(cmd: String): String {
        val timeRegex = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm|बजे)?""", RegexOption.IGNORE_CASE)
        val match = timeRegex.find(cmd) ?: return "Please say the time. For example: Set alarm for 7 AM."

        var hour = match.groupValues[1].toInt()
        val min = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
        val ampm = match.groupValues[3].lowercase()
        if (ampm == "pm" && hour < 12) hour += 12
        if (ampm == "am" && hour == 12) hour = 0

        return try {
            context.startActivity(Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, min)
                putExtra(AlarmClock.EXTRA_MESSAGE, "SARASWATI")
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            val h = if (hour > 12) hour - 12 else if (hour == 0) 12 else hour
            "Alarm set for $h:${String.format("%02d", min)} ${if (hour >= 12) "PM" else "AM"}."
        } catch (e: Exception) {
            "Could not set alarm. Please try manually."
        }
    }

    private fun lookupContact(name: String): String? {
        if (name.isBlank()) return null
        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"), null
            )
            cursor?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) { null }
    }

    private fun toggleFlashlight(on: Boolean) {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE)
                    as android.hardware.camera2.CameraManager
            cm.setTorchMode(cm.cameraIdList[0], on)
        } catch (e: Exception) { }
    }
}
