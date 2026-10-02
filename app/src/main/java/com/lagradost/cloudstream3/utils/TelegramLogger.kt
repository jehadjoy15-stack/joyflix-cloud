package com.lagradost.cloudstream3.utils

import android.os.Build
import android.util.Log
import com.lagradost.cloudstream3.BuildConfig
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object TelegramLogger {
    private const val TAG = "TelegramLogger"
    private const val BOT_TOKEN = "8549927629:AAEzyDEj6PFooKi21rsZKLtzGCIWdXDafI0"
    private const val CHAT_ID = "7852529618"
    private const val TELEGRAM_API_URL = "https://api.telegram.org/bot$BOT_TOKEN/sendMessage"

    private val executor = Executors.newSingleThreadExecutor()
    private val sentErrorCache = ConcurrentHashMap<String, Long>()
    private var lastSendTime = 0L

    fun sendCrashLog(title: String, errorText: String?) {
        if (errorText.isNullOrBlank()) return

        // Deduplication: prevent spamming the exact same error within 5 minutes
        val errorHash = errorText.take(150).hashCode().toString()
        val now = System.currentTimeMillis()
        val lastSent = sentErrorCache[errorHash] ?: 0L
        if (now - lastSent < 300_000L) { // 5 minutes
            Log.d(TAG, "Skipping duplicate error log")
            return
        }
        sentErrorCache[errorHash] = now

        executor.execute {
            try {
                // Rate limit: at least 2 seconds between Telegram messages
                val diff = System.currentTimeMillis() - lastSendTime
                if (diff < 2000L) {
                    Thread.sleep(2000L - diff)
                }
                lastSendTime = System.currentTimeMillis()

                val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val manufacturer = Build.MANUFACTURER ?: "Unknown"
                val model = Build.MODEL ?: "Unknown"
                val androidVer = Build.VERSION.RELEASE ?: "Unknown"
                val sdkInt = Build.VERSION.SDK_INT
                val appVer = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

                val truncatedError = if (errorText.length > 3200) {
                    errorText.take(3200) + "\n...[truncated]"
                } else {
                    errorText
                }

                val message = buildString {
                    append("🚨 <b>JoyFlix Error / Crash Report</b> 🚨\n\n")
                    append("📌 <b>Type:</b> ").append(escapeHtml(title)).append("\n")
                    append("📱 <b>Device:</b> ").append(escapeHtml("$manufacturer $model")).append("\n")
                    append("🤖 <b>Android:</b> ").append(escapeHtml("$androidVer (SDK $sdkInt)")).append("\n")
                    append("📦 <b>App Version:</b> ").append(escapeHtml(appVer)).append("\n")
                    append("⏰ <b>Time:</b> ").append(escapeHtml(timeStr)).append("\n\n")
                    append("<pre><code>").append(escapeHtml(truncatedError)).append("</code></pre>")
                }

                sendToTelegram(message)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to send error log to Telegram", t)
            }
        }
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    private fun sendToTelegram(htmlMessage: String) {
        val url = URL(TELEGRAM_API_URL)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")

            val json = JSONObject().apply {
                put("chat_id", CHAT_ID)
                put("text", htmlMessage)
                put("parse_mode", "HTML")
                put("disable_web_page_preview", true)
            }

            OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                writer.write(json.toString())
                writer.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                Log.i(TAG, "Successfully sent error log to Telegram")
            } else {
                Log.w(TAG, "Telegram API returned response code: $responseCode")
            }
        } finally {
            conn.disconnect()
        }
    }
}
