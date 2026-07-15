package com.aibhai.saraswati

import android.content.*
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.os.Build
import java.util.*

class CommandProcessor(private val context: Context) {

    fun tryHandleLocally(command: String): String? {
        val cmd = command.lowercase().trim()

        return when {
            cmd.startsWith("call ") -> handleCall(cmd.removePrefix("call ").trim())

            cmd.startsWith("whatsapp ") || cmd.contains("send whatsapp") || cmd.contains("message on whatsapp") ->
                handleWhatsApp(cmd)

            cmd.startsWith("open ") -> handleOpenApp(cmd.removePrefix("open ").trim())

            cmd.startsWith("search ") || cmd.startsWith("google ") || cmd.startsWith("search for ") ->
                handleSearch(cmd)

            cmd.contains("set alarm") || cmd.contains("alarm for") || cmd.contains("wake me") ->
                handleAlarm(cmd)

            cmd.contains("what time") || cmd.contains("current time") || cmd == "time" -> {
                val now = Calendar.getInstance()
                val hour = now.get(Calendar.HOUR)
                val min = now.get(Calendar.MINUTE)
                val ampm = if (now.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
                "The current time is ${if (hour == 0) 12 else hour}:${String.format("%02d", min)} $ampm."
            }

            cmd.contains("what date") || cmd.contains("today's date") || cmd.contains("what day") -> {
                val now = Calendar.getInstance()
                val days = arrayOf("Sunday","Monday","Tuesday","Wednesday","Thursday","Friday","Saturday")
                val months = arrayOf("January","February","March","April","May","June","July","August","September","October","November","December")
                "Today is ${days[now.get(Calendar.DAY_OF_WEEK)-1]}, ${now.get(Calendar.DAY_OF_MONTH)} ${months[now.get(Calendar.MONTH)]} ${now.get(Calendar.YEAR)}."
            }

            cmd.contains("flashlight on") || cmd.contains("torch on") -> {
                toggleFlashlight(true)
                "Flashlight turned on."
            }
            cmd.contains("flashlight off") || cmd.contains("torch off") -> {
                toggleFlashlight(false)
                "Flashlight turned off."
            }

            cmd.contains("open settings") || cmd.contains("go to settings") -> {
                val intent = Intent(Settings.ACTION_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                context.startActivity(intent)
                "Opening settings."
            }

            cmd.contains("wifi") && cmd.contains("setting") -> {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                context.startActivity(intent)
                "Opening WiFi settings."
            }

            cmd.contains("bluetooth") && cmd.contains("setting") -> {
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                context.startActivity(intent)
                "Opening Bluetooth settings."
            }

            cmd.contains("open youtube") || cmd.startsWith("youtube") -> {
                launchApp("com.google.android.youtube") ?: run {
                    openUrl("https://youtube.com")
                    "Opening YouTube."
                }
            }

            cmd.contains("play music") || cmd.contains("open spotify") -> {
                launchApp("com.spotify.music") ?: launchApp("com.google.android.music") ?: "I couldn't find a music app."
            }

            else -> null
        }
    }

    private fun handleCall(nameOrNumber: String): String {
        val number = if (nameOrNumber.all { it.isDigit() || it == '+' || it == '-' }) {
            nameOrNumber
        } else {
            lookupContact(nameOrNumber) ?: nameOrNumber
        }
        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "Calling $nameOrNumber now."
        } catch (e: Exception) {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "Opening dialer for $nameOrNumber."
        }
    }

    private fun handleWhatsApp(cmd: String): String {
        return try {
            var name = ""
            var message = ""

            when {
                cmd.contains(":") -> {
                    val parts = cmd.substringAfter("whatsapp ").split(":", limit = 2)
                    name = parts[0].trim()
                    message = if (parts.size > 1) parts[1].trim() else ""
                }
                cmd.contains(" to ") -> {
                    val afterTo = cmd.substringAfter(" to ").trim()
                    val spaceIdx = afterTo.indexOf(" ")
                    name = if (spaceIdx > 0) afterTo.substring(0, spaceIdx) else afterTo
                    message = if (spaceIdx > 0) afterTo.substring(spaceIdx).trim() else ""
                }
                else -> {
                    name = cmd.removePrefix("whatsapp ").trim()
                }
            }

            val number = lookupContact(name)
            if (number != null) {
                val cleanNumber = number.replace("[^0-9+]".toRegex(), "")
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber&text=${Uri.encode(message)}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                if (message.isNotEmpty()) "Opening WhatsApp to send message to $name."
                else "Opening WhatsApp chat with $name."
            } else {
                launchApp("com.whatsapp") ?: "WhatsApp is not installed."
            }
        } catch (e: Exception) {
            "I couldn't open WhatsApp. Please make sure it's installed."
        }
    }

    private fun handleOpenApp(appName: String): String {
        val packageMap = mapOf(
            "chrome" to "com.android.chrome",
            "google" to "com.google.android.googlequicksearchbox",
            "youtube" to "com.google.android.youtube",
            "whatsapp" to "com.whatsapp",
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "twitter" to "com.twitter.android",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
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
            "photos" to "com.google.android.apps.photos",
            "files" to "com.google.android.apps.nbu.files"
        )

        val pkg = packageMap[appName.lowercase()]
            ?: packageMap.entries.firstOrNull { appName.lowercase().contains(it.key) }?.value

        return if (pkg != null) {
            launchApp(pkg) ?: "App not found on this device."
        } else {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val match = apps.firstOrNull {
                pm.getApplicationLabel(it).toString().lowercase().contains(appName.lowercase())
            }
            if (match != null) {
                launchApp(match.packageName) ?: "Could not open $appName."
            } else {
                "I couldn't find $appName on your device."
            }
        }
    }

    private fun handleSearch(cmd: String): String {
        val query = cmd
            .removePrefix("search for ")
            .removePrefix("search ")
            .removePrefix("google ")
            .trim()
        return try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra("query", query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "Searching Google for: $query"
        } catch (e: Exception) {
            openUrl("https://www.google.com/search?q=${Uri.encode(query)}")
            "Searching for $query."
        }
    }

    private fun handleAlarm(cmd: String): String {
        val timeRegex = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""", RegexOption.IGNORE_CASE)
        val match = timeRegex.find(cmd)

        return if (match != null) {
            var hour = match.groupValues[1].toInt()
            val min = if (match.groupValues[2].isNotEmpty()) match.groupValues[2].toInt() else 0
            val ampm = match.groupValues[3].lowercase()

            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0

            try {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, min)
                    putExtra(AlarmClock.EXTRA_MESSAGE, "SARASWATI Alarm")
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                "Alarm set for ${if (hour > 12) hour-12 else if (hour == 0) 12 else hour}:${String.format("%02d", min)} ${if (hour >= 12) "PM" else "AM"}."
            } catch (e: Exception) {
                "I couldn't set the alarm. Please try manually."
            }
        } else {
            "Please tell me the time for the alarm. For example: Set alarm for 7 AM."
        }
    }

    private fun lookupContact(name: String): String? {
        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"), null
            )
            cursor?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) { null }
    }

    private fun launchApp(packageName: String): String? {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent != null) {
                context.startActivity(intent)
                val appName = try {
                    context.packageManager.getApplicationLabel(
                        context.packageManager.getApplicationInfo(packageName, 0)
                    ).toString()
                } catch (e: Exception) { packageName }
                "Opening $appName."
            } else null
        } catch (e: Exception) { null }
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    private fun toggleFlashlight(on: Boolean) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE)
                    as android.hardware.camera2.CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, on)
        } catch (e: Exception) { }
    }
}
