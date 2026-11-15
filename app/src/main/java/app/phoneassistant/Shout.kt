package app.phoneassistant

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Says something out loud on the alarm stream at full volume: works with the ringer on silent or the media volume at 0. */
object Shout {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null

    fun dutch(c: Context) = c.getSharedPreferences("p", Context.MODE_PRIVATE).getString("lang", "nl-BE").orEmpty().startsWith("nl")
    fun ownerName(c: Context) = c.getSharedPreferences("p", Context.MODE_PRIVATE).getString("call_owner", "").orEmpty().trim()

    fun say(c: Context, title: String, text: String, repeats: Int = 3, done: () -> Unit = {}) {
        val app = c.applicationContext
        notify(app, title, text, true)
        main.post {
            val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val saved = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            try { audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) } catch (e: Exception) { }
            vibrate(app)
            var finished = false
            fun end() {
                if (finished) return; finished = true
                try { audio.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0) } catch (e: Exception) { }
                tts?.shutdown(); tts = null; done()
            }
            tts?.shutdown()
            var t: TextToSpeech? = null
            t = TextToSpeech(app) { status ->
                val e = t ?: return@TextToSpeech
                if (status != TextToSpeech.SUCCESS) { end(); return@TextToSpeech }
                val lang = app.getSharedPreferences("p", Context.MODE_PRIVATE).getString("lang", "nl-BE").orEmpty().ifEmpty { "nl-BE" }
                e.language = Locale.forLanguageTag(lang)
                e.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { if (id == "last") main.post { end() } }
                    @Deprecated("old api") override fun onError(id: String?) { main.post { end() } }
                })
                for (k in 1..repeats) {
                    e.speak(text, TextToSpeech.QUEUE_ADD, null, if (k == repeats) "last" else "s$k")
                    if (k < repeats) e.playSilentUtterance(1200, TextToSpeech.QUEUE_ADD, "p$k")
                }
            }
            tts = t
            main.postDelayed({ end() }, 60_000)   // never leave the alarm volume at max
        }
    }

    private fun vibrate(c: Context) {
        try {
            val v = (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
            v.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 600, 300, 600, 300, 600), -1))
        } catch (e: Exception) { }
    }

    fun notify(c: Context, title: String, text: String, loud: Boolean) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = if (loud) "urgent" else "reminders"
            nm.createNotificationChannel(NotificationChannel(ch, if (loud) (if (dutch(c)) "Dringend" else "Urgent") else (if (dutch(c)) "Herinneringen" else "Reminders"), NotificationManager.IMPORTANCE_HIGH)
                .apply { if (loud) setSound(null, null) })   // loud ones are spoken, no extra ding
            val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            nm.notify((System.currentTimeMillis() % 100000).toInt(), Notification.Builder(c, ch).setSmallIcon(R.drawable.ic_tile).setContentTitle(title).setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text)).setCategory(if (loud) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER).setContentIntent(open).setAutoCancel(true).build())
        } catch (e: Exception) { }
    }
}

/** Reminders set by the assistant: exact alarms, kept in prefs so they survive a reboot. */
object Reminders {
    private fun p(c: Context) = c.getSharedPreferences("p", Context.MODE_PRIVATE)
    private fun all(c: Context) = try { JSONArray(p(c).getString("reminders", "[]")) } catch (e: Exception) { JSONArray() }
    private fun save(c: Context, a: JSONArray) = p(c).edit().putString("reminders", a.toString()).apply()

    fun add(c: Context, at: Long, text: String, loud: Boolean): Int {
        val id = (System.currentTimeMillis() % 1_000_000).toInt()
        val a = all(c); a.put(JSONObject().put("id", id).put("at", at).put("text", text).put("loud", loud)); save(c, a)
        arm(c, id, at); return id
    }

    fun take(c: Context, id: Int): JSONObject? {
        val a = all(c); val out = JSONArray(); var hit: JSONObject? = null
        for (i in 0 until a.length()) a.getJSONObject(i).let { if (it.optInt("id") == id) hit = it else out.put(it) }
        save(c, out); return hit
    }

    fun rearm(c: Context) {
        val a = all(c); val now = System.currentTimeMillis()
        for (i in 0 until a.length()) a.getJSONObject(i).let { arm(c, it.optInt("id"), maxOf(it.optLong("at"), now + 5000)) }
    }

    private fun arm(c: Context, id: Int, at: Long) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(c, id, Intent(c, ReminderReceiver::class.java).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED || i.action == Intent.ACTION_MY_PACKAGE_REPLACED) { Reminders.rearm(c); return }
        val r = Reminders.take(c, i.getIntExtra("id", -1)) ?: return
        val text = r.optString("text"); val nl = Shout.dutch(c)
        val title = if (nl) "Herinnering" else "Reminder"
        if (!r.optBoolean("loud")) { Shout.notify(c, title, text, false); return }
        val name = Shout.ownerName(c)
        val say = (if (name.isNotEmpty()) "$name! " else "") + (if (nl) "Herinnering: $text" else "Reminder: $text")
        val pending = goAsync()
        Shout.say(c, title, say) { try { pending.finish() } catch (e: Exception) { } }
    }
}
