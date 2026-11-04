package app.phoneassistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Phone-call assistance. Three things, all opt-in per app (Settings → Calls):
 *  - "speaker": answer a call you didn't pick up, switch to the loudspeaker and talk to the caller (it always says it is an AI assistant);
 *  - "text": silently decline and send the caller a short message instead;
 *  - during any call you can press your assistant shortcut to let it talk to the other person.
 * Android gives apps no access to call audio, so the conversation works over the loudspeaker + microphone (best effort).
 */
object CallManager {
    const val PHONE = "phone"
    class Ring(val key: String, val pkg: String, val label: String, var number: String?, val answer: PendingIntent?, val decline: PendingIntent?, val since: Long = System.currentTimeMillis())

    private val main = Handler(Looper.getMainLooper())
    private val rings = HashMap<String, Ring>()
    private val timers = HashMap<String, Runnable>()
    private var session: Session? = null
    private var phoneState = TelephonyManager.CALL_STATE_IDLE
    private var lastNumber: String? = null
    private var listener: PhoneStateListener? = null
    private val ANSWER = Regex("answer|accept|beantwoord|opnemen|antwoord|décroch|repond|répond|accepter|annehmen|atender|rispondi", RegexOption.IGNORE_CASE)
    private val DECLINE = Regex("decline|reject|weiger|afwijzen|ophangen|raccroch|refuser|ablehnen|hang up|dismiss|rechazar|rifiuta", RegexOption.IGNORE_CASE)

    private fun svc() = AgentService.instance
    private fun p(c: Context) = c.getSharedPreferences("p", Context.MODE_PRIVATE)
    fun enabled(c: Context) = p(c).getBoolean("call_on", false)
    fun sessionActive() = session != null

    // ---------- settings model ----------
    fun modes(c: Context): MutableMap<String, String> {
        val m = LinkedHashMap<String, String>(); val j = try { JSONObject(p(c).getString("call_modes", "{}")) } catch (e: Exception) { JSONObject() }
        j.keys().forEach { m[it] = j.optString(it, "off") }; return m
    }
    fun setMode(c: Context, pkg: String, mode: String) { val m = modes(c); m[pkg] = mode; p(c).edit().putString("call_modes", JSONObject(m as Map<*, *>).toString()).apply() }
    private fun modeFor(c: Context, pkg: String): String {
        var m = modes(c)[pkg] ?: "off"
        if (m == "speaker" && svc()?.isQuiet() == true) m = "text"   // never talk out loud during quiet hours
        return m
    }
    private fun dutch(c: Context) = p(c).getString("lang", "nl-BE").orEmpty().startsWith("nl")
    private fun owner(c: Context) = p(c).getString("call_owner", "").orEmpty().ifBlank { if (dutch(c)) "mijn eigenaar" else "my owner" }
    private fun aiName(c: Context) = p(c).getString("ai_name", "Assistant").orEmpty().ifBlank { "Assistant" }

    // ---------- permission + status ----------
    fun missingPermissions(c: Context): List<String> = listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_CALL_LOG, Manifest.permission.SEND_SMS, Manifest.permission.RECORD_AUDIO)
        .filter { c.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    fun notificationAccess(c: Context) = android.provider.Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners").orEmpty().contains(c.packageName)

    /** (Re)start listening to the phone state; called when the service starts and after permissions change. */
    @Suppress("DEPRECATION")
    fun watch(c: Context) {
        try {
            val tm = c.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            listener?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }; listener = null
            if (c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
            val l = object : PhoneStateListener() {
                @Deprecated("") override fun onCallStateChanged(state: Int, phoneNumber: String?) { main.post { onPhoneState(c, state, phoneNumber) } }
            }
            listener = l; tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: Exception) { Bus.log("Call watch failed: ${e.message}") }
    }

    // ---------- events ----------
    private fun onPhoneState(c: Context, state: Int, number: String?) {
        phoneState = state
        if (!number.isNullOrBlank()) lastNumber = number
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> if (enabled(c)) ringStart(c, Ring(PHONE, PHONE, if (dutch(c)) "Telefoon" else "Phone", number ?: lastNumber, null, null))
            TelephonyManager.CALL_STATE_IDLE -> { ringEnd(PHONE); if (session?.info?.pkg == PHONE) endSession("call ended") }
            TelephonyManager.CALL_STATE_OFFHOOK -> ringEnd(PHONE)
        }
    }

    /** From CallListener: a notification of any app appeared or disappeared. */
    fun onNotification(c: Context, sbn: StatusBarNotification, posted: Boolean) {
        val n = sbn.notification ?: return
        if (sbn.packageName == c.packageName) return
        val telecom = sbn.packageName.contains("dialer") || sbn.packageName.contains("incallui") || sbn.packageName.contains("telecom") || sbn.packageName.contains("phone")
        val isCall = n.category == Notification.CATEGORY_CALL
        if (!isCall) return
        val key = "n:" + sbn.key
        if (!posted) { ringEnd(key); if (session?.info?.key == key) endSession("call ended"); return }
        if (!enabled(c)) return
        val ex = n.extras
        val type = ex.getInt("android.callType", 0)   // 1 = incoming (CallStyle)
        var answer: PendingIntent? = null; var decline: PendingIntent? = null
        n.actions?.forEach { a -> val t = a.title?.toString().orEmpty(); if (ANSWER.containsMatchIn(t)) answer = a.actionIntent else if (DECLINE.containsMatchIn(t)) decline = a.actionIntent }
        val incoming = type == 1 || (type == 0 && answer != null)
        if (!incoming) return
        if (telecom) return   // regular calls are handled through the telephony state (the notification only gives a name)
        val label = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().ifBlank { sbn.packageName }
        ringStart(c, Ring(key, sbn.packageName, label, null, answer, decline))
    }

    private fun ringStart(c: Context, r: Ring) {
        if (rings.containsKey(r.key)) { rings[r.key]?.let { if (r.number != null) it.number = r.number }; return }
        val mode = modeFor(c, r.pkg); if (mode == "off") return
        rings[r.key] = r
        val delay = p(c).getInt("call_delay", 20) * 1000L
        Bus.log("📞 ${r.label} is calling: ${if (mode == "speaker") "I answer after ${delay / 1000} s if you don't" else "I send a text after ${delay / 1000} s if you don't answer"}")
        val run = Runnable { timers.remove(r.key); if (rings.containsKey(r.key)) fire(c, rings[r.key]!!, mode) }
        timers[r.key] = run; main.postDelayed(run, delay)
    }

    private fun ringEnd(key: String) { timers.remove(key)?.let { main.removeCallbacks(it) }; rings.remove(key) }

    private fun fire(c: Context, r: Ring, mode: String) {
        try {
            if (session != null) return
            if (mode == "speaker") { if (!answer(c, r)) { Bus.log("📞 couldn't answer this call"); return }; main.postDelayed({ startSession(c, Session(c, r, false)) }, if (r.pkg == PHONE) 900L else 1800L) }
            else { decline(c, r); main.postDelayed({ sendText(c, r) }, 800) }
        } catch (e: Exception) { Bus.log("Call handling failed: ${e.message}") }
    }

    private fun tm(c: Context) = c.getSystemService(Context.TELECOM_SERVICE) as TelecomManager

    @Suppress("DEPRECATION", "MissingPermission")
    private fun answer(c: Context, r: Ring): Boolean {
        if (r.pkg == PHONE) { if (c.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) return false; tm(c).acceptRingingCall(); return true }
        val a = r.answer ?: return false
        return try { a.send(); true } catch (e: Exception) { false }
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun decline(c: Context, r: Ring) {
        if (r.pkg == PHONE) { if (c.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) tm(c).endCall() }
        else try { r.decline?.send() } catch (e: Exception) { }
        ringEnd(r.key)
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun hangUp(c: Context, info: Ring?) {
        try { if (info == null || info.pkg == PHONE) { if (c.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) tm(c).endCall() } else svc()?.clickByLabel(Regex("hang up|end call|ophangen|gesprek beëindigen|raccrocher|auflegen|leave", RegexOption.IGNORE_CASE)) } catch (e: Exception) { }
    }

    // ---------- silent text reply ----------
    private fun textFor(c: Context): String {
        val custom = p(c).getString("call_text", "").orEmpty().trim()
        if (custom.isNotEmpty()) return custom
        return if (dutch(c)) "Hoi, dit is ${aiName(c)}, de digitale assistent van ${owner(c)}. ${owner(c)} kan nu niet opnemen. Laat gerust een bericht achter, dan wordt er zo snel mogelijk teruggebeld."
        else "Hi, this is ${aiName(c)}, ${owner(c)}'s AI assistant. ${owner(c)} can't pick up right now. Please leave a message and you'll get a call back as soon as possible."
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun sendText(c: Context, r: Ring) {
        val msg = textFor(c)
        if (r.pkg == PHONE) {
            val num = r.number ?: lastNumber
            if (num.isNullOrBlank() || c.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) { notify(c, r.label, if (dutch(c)) "Gemiste oproep. Geen sms verstuurd (nummer onbekend of geen toestemming)." else "Missed call. No text sent (unknown number or no permission)."); return }
            try {
                val sms = if (android.os.Build.VERSION.SDK_INT >= 31) c.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
                sms.sendMultipartTextMessage(num, null, sms.divideMessage(msg), null, null)
                log(c, r.label, if (dutch(c)) "Gemiste oproep: sms met uitleg verstuurd." else "Missed call: sent a text explaining.")
                notify(c, r.label, if (dutch(c)) "Gemiste oproep. Ik heb een sms gestuurd naar $num." else "Missed call. I sent a text to $num.")
            } catch (e: Exception) { Bus.log("SMS failed: ${e.message}") }
        } else {
            // another app (WhatsApp, Telegram…): let the normal agent write the message in that chat
            val app = try { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(r.pkg, 0)).toString() } catch (e: Exception) { r.pkg }
            log(c, r.label, if (dutch(c)) "Gemiste $app-oproep: bericht versturen." else "Missed $app call: sending a message.")
            svc()?.runTask("In $app, open the chat with \"${r.label}\" (use the open chat/message option from the missed call if there is one) and send exactly this message, then finish: $msg")
        }
    }

    // ---------- speaker conversation ----------
    fun startFromKey(c: Context) {
        if (session != null) return
        val info = Ring("key", if (phoneState != TelephonyManager.CALL_STATE_IDLE) PHONE else "voip", if (dutch(c)) "Lopend gesprek" else "Current call", lastNumber, null, null)
        startSession(c, Session(c, info, true))
    }

    private fun startSession(c: Context, s: Session) { session = s; s.begin() }
    fun endSession(why: String) { session?.finish(why); }

    /** One spoken conversation with the person on the other end. */
    class Session(val c: Context, val info: Ring, val userInitiated: Boolean) {
        private val h = Handler(Looper.getMainLooper())
        private val history = JSONArray()
        private var rec: SpeechRecognizer? = null
        private var silent = 0
        private var turns = 0
        private var done = false
        private val startedAt = System.currentTimeMillis()
        private val transcript = StringBuilder()
        private val audio = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        private val nl = dutch(c)

        fun begin() {
            Bus.log("📞 conversation started with ${info.label}")
            speakerOn(true)
            val who = owner(c); val ai = aiName(c)
            val greet = if (nl) (if (userInitiated) "Goedendag, ik ben $ai, de digitale assistent van $who. $who heeft mij gevraagd dit gesprek verder te helpen. Waarmee kan ik u helpen?"
                else "Goedendag, u spreekt met $ai, de digitale assistent van $who. $who kan het gesprek nu niet zelf aannemen. Waarmee kan ik u helpen?")
            else (if (userInitiated) "Hello, I'm $ai, $who's AI assistant. $who asked me to help with this call. How can I help you?" else "Hello, this is $ai, $who's AI assistant. $who can't take the call right now. How can I help you?")
            say(greet) { listen() }
        }

        private fun speakerOn(on: Boolean) {
            try {
                @Suppress("DEPRECATION") audio.isSpeakerphoneOn = on
                if (on) { val mx = audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL); audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (mx * 0.85).toInt(), 0) }
            } catch (e: Exception) { }
            if (on) h.postDelayed({ @Suppress("DEPRECATION") if (!audio.isSpeakerphoneOn) svc()?.clickByLabel(Regex("speaker|luidspreker|haut-parleur|lautsprecher|altavoz", RegexOption.IGNORE_CASE)) }, 700)
        }

        private fun say(t: String, then: () -> Unit) {
            transcript.append("Assistant: ").append(t).append('\n')
            val s = svc() ?: run { finish("service gone"); return }
            s.onSpeechDone = { if (!done) then() }
            s.speak(t, force = true)
        }

        private fun listen() {
            if (done) return
            if (System.currentTimeMillis() - startedAt > 4 * 60_000L) { say(if (nl) "Ik moet dit gesprek nu afronden. Ik geef uw boodschap door. Tot ziens." else "I have to wrap up now. I'll pass on your message. Goodbye.") { finish("time limit") }; return }
            if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { finish("no microphone permission"); return }
            rec?.destroy()
            val r = SpeechRecognizer.createSpeechRecognizer(c); rec = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle?) {
                    val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                    if (t.isEmpty()) { noSpeech(); return }
                    silent = 0; heard(t)
                }
                override fun onError(error: Int) { if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) noSpeech() else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS || ++errs >= 4) finish("microphone not available during the call (error $error)") else h.postDelayed({ listen() }, 500) }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(b: Bundle?) {}
                override fun onEvent(t: Int, b: Bundle?) {}
            })
            val lang = p(c).getString("lang", "nl-BE").orEmpty().ifEmpty { Locale.getDefault().toLanguageTag() }
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang).putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L))
        }
        private var errs = 0

        private fun noSpeech() {
            silent++
            if (silent >= 3) { say(if (nl) "Ik hoor u niet meer. Ik geef door dat u gebeld heeft. Tot ziens." else "I can't hear you anymore. I'll let them know you called. Goodbye.") { finish("no one answered") }; return }
            if (silent == 2) say(if (nl) "Bent u er nog?" else "Are you still there?") { listen() } else listen()
        }

        private fun heard(text: String) {
            transcript.append("Caller: ").append(text).append('\n'); turns++
            Bus.log("📞 caller: $text")
            history.put(JSONObject().put("role", "user").put("content", text))
            thread {
                val reply = try { ask() } catch (e: Exception) { Bus.log("Call AI error: ${e.message}"); if (nl) "Sorry, er ging iets mis. Ik geef uw boodschap door. Tot ziens. [END]" else "Sorry, something went wrong. I'll pass on your message. Goodbye. [END]" }
                main.post {
                    val end = reply.contains("[END]"); val say = reply.replace("[END]", "").trim()
                    history.put(JSONObject().put("role", "assistant").put("content", say.ifEmpty { "..." }))
                    if (say.isEmpty()) { if (end) finish("conversation complete") else listen() }
                    else say(say) { if (end || turns >= 14) finish("conversation complete") else listen() }
                }
            }
        }

        private fun ask(): String {
            val cfg = svc()?.callConfig() ?: throw RuntimeException("no API key")
            val who = owner(c); val ai = aiName(c)
            val rules = p(c).getString("call_rules", "").orEmpty().trim().ifEmpty { if (nl) "Vraag wie er belt en waarom, noteer een boodschap en zeg dat $who zo snel mogelijk terugbelt." else "Ask who is calling and why, take a message and say $who will call back as soon as possible." }
            val sys = "You are $ai, the AI phone assistant of $who. You are on a live phone call with a caller; everything you write is spoken aloud. You already told the caller you are an AI assistant. Speak ${if (nl) "Dutch" else "English"}, natural and warm, ONE or TWO short sentences per turn, no lists, no markdown. " +
                "Your job: $rules " +
                "Never share personal information about $who (address, schedule, whereabouts, finances, health), never confirm or deny who $who is with, never agree to payments, purchases, links or codes, and never promise anything on behalf of $who except passing on the message. If the caller is a sales or spam call, politely end it. If you do not understand, ask them to repeat. " +
                (if (userInitiated) "$who is on the line too and asked you to help with this call: when you are done, say you are handing the call back to $who, then add the exact token [END]. " else "") + "When the conversation is finished (you have the name and the reason, or the caller wants to hang up) say a short goodbye and add the exact token [END] at the very end."
            val body = JSONObject().put("model", if (cfg.hearth) cfg.model else "claude-haiku-5-5").put("max_tokens", 160).put("system", sys).put("messages", history)
            val resp = Api.post(cfg.url, cfg.key, body)
            Usage.record(c, "claude-haiku-5-5", resp, cfg.hearth)
            val content = resp.getJSONArray("content"); val sb = StringBuilder()
            for (i in 0 until content.length()) content.getJSONObject(i).let { if (it.optString("type") == "text") sb.append(it.optString("text")) }
            return sb.toString().trim()
        }

        fun finish(why: String) {
            if (done) return; done = true
            h.removeCallbacksAndMessages(null)
            try { rec?.destroy() } catch (e: Exception) { }; rec = null
            svc()?.onSpeechDone = null
            Bus.log("📞 conversation ended ($why)")
            if (!userInitiated) hangUp(c, info)   // when you asked it to help during your call, the call stays yours
            speakerOn(false)
            session = null
            val t = transcript.toString()
            thread {
                val summary = try { summarize(t) } catch (e: Exception) { if (t.isBlank()) "" else t.takeLast(300) }
                main.post {
                    val s = if (summary.isBlank()) (if (nl) "Geen gesprek gevoerd ($why)." else "No conversation ($why).") else summary
                    log(c, info.label, s); notify(c, info.label, s)
                }
            }
        }

        private fun summarize(t: String): String {
            if (t.isBlank()) return ""
            val cfg = svc()?.callConfig() ?: return t.takeLast(300)
            val body = JSONObject().put("model", if (cfg.hearth) cfg.model else "claude-haiku-5-5").put("max_tokens", 160)
                .put("system", "Summarize this phone conversation for the owner in ${if (nl) "Dutch" else "English"}, 1-2 sentences: who called (name/number if said), what they want, and whether a call back is needed. No preamble.")
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", t)))
            val resp = Api.post(cfg.url, cfg.key, body); Usage.record(c, "claude-haiku-5-5", resp, cfg.hearth)
            val content = resp.getJSONArray("content"); return (0 until content.length()).map { content.getJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString(" ") { it.optString("text") }.trim()
        }
    }

    // ---------- log + notification ----------
    private fun log(c: Context, who: String, what: String) {
        val a = try { JSONArray(p(c).getString("call_log", "[]")) } catch (e: Exception) { JSONArray() }
        val out = JSONArray(); out.put(JSONObject().put("t", System.currentTimeMillis()).put("who", who).put("what", what))
        for (i in 0 until minOf(a.length(), 19)) out.put(a.get(i))
        p(c).edit().putString("call_log", out.toString()).apply(); Bus.log("📞 $who: $what")
    }

    fun recent(c: Context): List<Triple<Long, String, String>> {
        val a = try { JSONArray(p(c).getString("call_log", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).map { val o = a.getJSONObject(it); Triple(o.optLong("t"), o.optString("who"), o.optString("what")) }
    }

    private fun notify(c: Context, title: String, text: String) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("calls", if (dutch(c)) "Oproepen" else "Calls", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            nm.notify((System.currentTimeMillis() % 100000).toInt(), Notification.Builder(c, "calls").setSmallIcon(R.drawable.ic_cat_call).setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build())
        } catch (e: Exception) { }
    }
}
