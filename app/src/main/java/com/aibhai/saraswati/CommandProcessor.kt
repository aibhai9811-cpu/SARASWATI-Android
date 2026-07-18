package com.aibhai.saraswati

import android.content.*
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.content.pm.PackageManager
import java.util.*

class CommandProcessor(private val context: Context) {

    // Find installed WhatsApp variant once
    private fun getWhatsAppPackage(): String? {
        val packages = listOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.gbwhatsapp",
            "com.poorfish.whatsapp",
            "com.whatsapp.plus"
        )
        return packages.firstOrNull { pkg ->
            try { context.packageManager.getPackageInfo(pkg, 0); true }
            catch (e: Exception) { false }
        }
    }

    fun tryHandleLocally(command: String): String? {
        val cmd = command.lowercase().trim()

        return when {

            // ── WHATSAPP ──
            cmd.contains("whatsapp") -> handleWhatsApp(cmd)

            // ── CALLS ──
            cmd.contains("call") -> {
                val name = cmd
                    .replace("make a call to", "").replace("call to", "")
                    .replace("call", "").trim()
                handleCall(name)
            }

            // ── OPEN APPS ──
            cmd.startsWith("open ") -> handleOpenApp(cmd.removePrefix("open ").trim())

            // ── SEARCH ──
            cmd.startsWith("search") || cmd.startsWith("google") ->
                handleSearch(cmd)

            // ── YOUTUBE directly ──
            cmd.contains("youtube") -> {
                openUrl("https://www.youtube.com")
                "Opening YouTube."
            }

            // ── ALARM ──
            cmd.contains("alarm") || cmd.contains("wake me") -> handleAlarm(cmd)

            // ── TIME ──
            cmd.contains("time") || cmd.contains("kitna baja") -> {
                val now = Calendar.getInstance()
                val h = now.get(Calendar.HOUR).let { if (it == 0) 12 else it }
                val m = String.format("%02d", now.get(Calendar.MINUTE))
                val ap = if (now.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
                "The current time is $h:$m $ap."
            }

            // ── DATE ──
            cmd.contains("date") || cmd.contains("day") && cmd.contains("today") -> {
                val now = Calendar.getInstance()
                val days = arrayOf("Sunday","Monday","Tuesday","Wednesday","Thursday","Friday","Saturday")
                val months = arrayOf("January","February","March","April","May","June",
                    "July","August","September","October","November","December")
                "Today is ${days[now.get(Calendar.DAY_OF_WEEK)-1]}, " +
                    "${now.get(Calendar.DAY_OF_MONTH)} ${months[now.get(Calendar.MONTH)]}."
            }

            // ── FLASHLIGHT — direct hardware control ──
            cmd.contains("flashlight") || cmd.contains("torch") -> {
                val turnOn = cmd.contains("on") || (!cmd.contains("off") && !cmd.contains("band"))
                toggleFlashlight(turnOn)
                if (turnOn) "Flashlight on." else "Flashlight off."
            }

            // ── SCREENSHOT ──
            cmd.contains("screenshot") -> {
                "To take a screenshot, press Power + Volume Down at the same time."
            }

            // ── SETTINGS ──
            cmd.contains("setting") -> {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                "Opening settings."
            }

            // ── WIFI ──
            cmd.contains("wifi") -> {
                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                "Opening WiFi settings."
            }

            // ── BLUETOOTH ──
            cmd.contains("bluetooth") -> {
                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                "Opening Bluetooth settings."
            }

            // ── BROWSER / CHROME ──
            cmd.contains("browser") || cmd.contains("chrome") -> {
                openUrl("https://www.google.com")
                "Opening browser."
            }

            else -> null
        }
    }

    private fun handleCall(nameOrNumber: String): String {
        val cleaned = nameOrNumber
            .replace("and put the call on speaker","")
            .replace("on speaker","")
            .replace("speaker","")
            .trim()

        if (cleaned.isEmpty()) {
            context.startActivity(Intent(Intent.ACTION_DIAL).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK })
            return "Opening dialer."
        }

        val number = if (cleaned.all { it.isDigit() || it == '+' || it == '-' || it == ' ' }) {
            cleaned.replace(" ","")
        } else {
            lookupContact(cleaned)
        }

        return if (number != null) {
            try {
                context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                "Calling $cleaned now."
            } catch (e: Exception) {
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                "Opening dialer for $cleaned."
            }
        } else {
            // Contact not found — open dialer so user can dial manually
            context.startActivity(Intent(Intent.ACTION_DIAL).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK })
            "Contact $cleaned not found. Opening dialer."
        }
    }

    private fun handleWhatsApp(cmd: String): String {
        val whatsappPkg = getWhatsAppPackage()

        // Parse name and message from command
        val cleaned = cmd
            .replace("open whatsapp and text","").replace("open whatsapp and send","")
            .replace("whatsapp message to","").replace("whatsapp text to","")
            .replace("send whatsapp to","").replace("send whatsapp","")
            .replace("open whatsapp","").replace("whatsapp to","").replace("whatsapp","")
            .trim()

        var name = ""
        var message = ""

        // "mummy hello i am fine" or "mummy to hello i am fine"
        val toIdx = cleaned.indexOf(" to ")
        if (toIdx > 0) {
            name = cleaned.substring(0, toIdx).trim()
            message = cleaned.substring(toIdx + 4).trim()
        } else {
            // Try splitting on first space — first word is name, rest is message
            val spaceIdx = cleaned.indexOf(' ')
            if (spaceIdx > 0 && cleaned.length > spaceIdx + 1) {
                name = cleaned.substring(0, spaceIdx).trim()
                message = cleaned.substring(spaceIdx + 1).trim()
            } else {
                name = cleaned
            }
        }

        // Handle family names
        val contactName = when {
            name.contains("mummy") || name.contains("mum") || name.contains("mom") || name == "mother" -> "mom"
            name.contains("dad") || name.contains("papa") || name.contains("father") -> "dad"
            name.contains("bhai") || name.contains("brother") -> "brother"
            name.contains("didi") || name.contains("sister") -> "sister"
            else -> name
        }

        val number = lookupContact(contactName)
            ?: lookupContact(name) // try original name too

        return if (whatsappPkg != null) {
            if (number != null) {
                // Direct WhatsApp message with number
                val cleanNum = number.replace("[^0-9]".toRegex(), "")
                val numWithCode = if (cleanNum.startsWith("91") && cleanNum.length == 12) cleanNum
                                  else if (cleanNum.length == 10) "91$cleanNum"
                                  else cleanNum
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://wa.me/$numWithCode" +
                            if (message.isNotEmpty()) "?text=${Uri.encode(message)}" else "")
                        setPackage(whatsappPkg) // ← KEY: force open in WhatsApp app not browser
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    if (message.isNotEmpty()) "Opening WhatsApp to send message to $name."
                    else "Opening WhatsApp chat with $name."
                } catch (e: Exception) {
                    // Fallback: open WhatsApp directly
                    openApp(whatsappPkg)
                    "Opening WhatsApp."
                }
            } else {
                // No number found — just open WhatsApp
                openApp(whatsappPkg)
                if (name.isNotEmpty()) "Opening WhatsApp. Please find $name manually."
                else "Opening WhatsApp."
            }
        } else {
            "WhatsApp is not installed on this device. Please install it from Play Store."
        }
    }

    private fun handleOpenApp(appName: String): String {
        val name = appName.lowercase().trim()

        // Common app package map — includes Vivo-specific packages
        val packageMap = mapOf(
            "youtube" to listOf("com.google.android.youtube","com.vanced.android.youtube"),
            "whatsapp" to listOf("com.whatsapp","com.whatsapp.w4b"),
            "chrome" to listOf("com.android.chrome","com.chrome.beta"),
            "google" to listOf("com.google.android.googlequicksearchbox","com.google.android.gm"),
            "instagram" to listOf("com.instagram.android"),
            "facebook" to listOf("com.facebook.katana","com.facebook.lite"),
            "twitter" to listOf("com.twitter.android","com.twitter.android.lite"),
            "gmail" to listOf("com.google.android.gm"),
            "maps" to listOf("com.google.android.apps.maps"),
            "camera" to listOf("com.vivo.camera","com.android.camera","com.mediatek.camera"),
            "calculator" to listOf("com.android.calculator2","com.vivo.calculator"),
            "clock" to listOf("com.android.deskclock","com.vivo.alarmclock"),
            "spotify" to listOf("com.spotify.music"),
            "telegram" to listOf("org.telegram.messenger","org.telegram.messenger.web"),
            "netflix" to listOf("com.netflix.mediaclient"),
            "settings" to listOf("com.android.settings","com.vivo.settings"),
            "play store" to listOf("com.android.vending"),
            "contacts" to listOf("com.android.contacts","com.vivo.contacts"),
            "messages" to listOf("com.google.android.apps.messaging","com.vivo.message"),
            "paytm" to listOf("net.one97.paytm"),
            "phonepe" to listOf("com.phonepe.app"),
            "gpay" to listOf("com.google.android.apps.nbu.paisa.user"),
            "photos" to listOf("com.google.android.apps.photos"),
            "files" to listOf("com.google.android.apps.nbu.files","com.vivo.filemanager"),
            "amazon" to listOf("in.amazon.mShop.android.shopping"),
            "flipkart" to listOf("com.flipkart.android"),
            "zomato" to listOf("com.application.zomato"),
            "swiggy" to listOf("in.swiggy.android"),
            "hotstar" to listOf("in.startv.hotstar"),
            "mx player" to listOf("com.mxtech.videoplayer.ad","com.mxtech.videoplayer.pro"),
            "jio" to listOf("com.jio.web","com.jio.myjio"),
            "phone" to listOf("com.android.dialer","com.vivo.dialer"),
            "dialer" to listOf("com.android.dialer","com.vivo.dialer"),
            "gallery" to listOf("com.vivo.gallery","com.android.gallery3d"),
            "music" to listOf("com.vivo.music","com.android.music","com.spotify.music")
        )

        // Find by keyword match
        val matchedKey = packageMap.keys.firstOrNull { name.contains(it) }
        val packages = matchedKey?.let { packageMap[it] } ?: emptyList()

        // Try each package in order
        for (pkg in packages) {
            val result = openApp(pkg)
            if (result != null) return result
        }

        // Fuzzy search all installed apps
        return try {
            val pm = context.packageManager
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val match = installed.firstOrNull {
                pm.getApplicationLabel(it).toString().lowercase().contains(name)
            }
            if (match != null) {
                openApp(match.packageName) ?: "Could not open $appName."
            } else {
                // Last resort: open Play Store search
                openUrl("https://play.google.com/store/search?q=$name")
                "$appName not found. Searching Play Store."
            }
        } catch (e: Exception) {
            "Could not find $appName on this device."
        }
    }

    private fun handleSearch(cmd: String): String {
        val query = cmd
            .replace("search for","").replace("search on google","")
            .replace("search","").replace("google","").trim()
        openUrl("https://www.google.com/search?q=${Uri.encode(query)}")
        return "Searching for $query."
    }

    private fun handleAlarm(cmd: String): String {
        val timeRegex = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm|baje|baj)?""", RegexOption.IGNORE_CASE)
        val match = timeRegex.find(cmd) ?: return "Please say the alarm time. For example: Set alarm for 7 AM."

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
            val h = if (hour > 12) hour-12 else if (hour == 0) 12 else hour
            "Alarm set for $h:${String.format("%02d",min)} ${if(hour>=12)"PM" else "AM"}."
        } catch (e: Exception) { "Could not set alarm. Please try manually." }
    }

    // ── HELPERS ──

    private fun openApp(packageName: String): String? {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return null
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
            val label = try {
                context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(packageName, 0)).toString()
            } catch (e: Exception) { packageName }
            "Opening $label."
        } catch (e: Exception) { null }
    }

    private fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (e: Exception) { }
    }

    private fun lookupContact(name: String): String? {
        if (name.isBlank()) return null
        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"), null)
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
