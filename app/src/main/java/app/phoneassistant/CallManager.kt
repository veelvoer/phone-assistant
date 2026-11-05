package app.phoneassistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Call assistance, opt-in per app (Settings → Calls). For a call you don't pick up it can
 *  - "text": decline it and have a SILENT text conversation with the caller (SMS, or WhatsApp/Telegram… through their notifications), then tell you what it was about;
 *  - "speaker": answer and talk to the caller over the loudspeaker (best effort: Android gives apps no access to call audio, so this is not silent and the microphone may be blocked; it then falls back to the text conversation).
 * You can also press your shortcut during a call to let the assistant talk to the other person.
 */
object CallManager {
    const val PHONE = "phone"
    class Ring(val key: String, val pkg: String, val label: String, var number: String?, val answer: PendingIntent?, val decline: PendingIntent?, val since: Long = System.currentTimeMillis())
    class Chat(val key: String, val pkg: String, val label: String, val number: String?, val history: JSONArray = JSONArray(), var lastSent: String = "", var lastSeen: String = "", var started: Long = System.currentTimeMillis(), var replies: Int = 0)

    private val main = Handler(Looper.getMainLooper())
    private val rings = HashMap<String, Ring>()
    private val timers = HashMap<String, Runnable>()
    private val chats = HashMap<String, Chat>()
    private val chatTimers = HashMap<String, Runnable>()
    private var session: Session? = null
    private var phoneState = TelephonyManager.CALL_STATE_IDLE
    private var lastNumber: String? = null
    private var listener: PhoneStateListener? = null
    private var watching = false
    private val ANSWER = Regex("answer|accept|beantwoord|opnemen|antwoord|décroch|repond|répond|accepter|annehmen|atender|rispondi", RegexOption.IGNORE_CASE)
    private val DECLINE = Regex("decline|reject|weiger|afwijzen|ophangen|raccroch|refuser|ablehnen|hang up|dismiss|rechazar|rifiuta", RegexOption.IGNORE_CASE)
    private val events = ArrayList<String>()

    private fun svc() = AgentService.instance
    private fun p(c: Context) = c.getSharedPreferences("p", Context.MODE_PRIVATE)
    fun enabled(c: Context) = p(c).getBoolean("call_on", false)
    fun sessionActive() = session != null

    /** A short trace the Calls settings page shows, so problems can be seen instead of guessed. */
    fun event(t: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + t
        synchronized(events) { events.add(0, line); while (events.size > 40) events.removeAt(events.size - 1) }
        Bus.log("📞 $t")
    }
    fun recentEvents(): List<String> = synchronized(events) { events.take(12) }

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
    fun missingPermissions(c: Context): List<String> = listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_CALL_LOG, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.RECORD_AUDIO)
        .filter { c.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    fun notificationAccess(c: Context) = android.provider.Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners").orEmpty().contains(c.packageName)

    fun diagnostics(c: Context): String {
        val st = when (phoneState) { TelephonyManager.CALL_STATE_RINGING -> "ringing"; TelephonyManager.CALL_STATE_OFFHOOK -> "in a call"; else -> "idle" }
        return "Phone state: $st · listening to calls: ${if (watching) "yes" else "NO (needs the phone permission)"}\nNotification access: ${if (notificationAccess(c)) "on" else "OFF"} · accessibility service: ${if (svc() != null) "on" else "OFF"}\nOpen text chats: ${chats.size} · ringing now: ${rings.size}"
    }

    /** (Re)start listening to the phone state; called when the service starts and after permissions change. */
    @Suppress("DEPRECATION")
    fun watch(ctx: Context) {
        val c = ctx.applicationContext
        try {
            val tm = c.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            listener?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }; listener = null; watching = false
            if (c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) { event("not listening to calls: phone permission missing"); return }
            val l = object : PhoneStateListener() {
                @Deprecated("") override fun onCallStateChanged(state: Int, phoneNumber: String?) { main.post { onPhoneState(c, state, phoneNumber) } }
            }
            listener = l; tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE); watching = true
            event("listening to phone calls")
        } catch (e: Exception) { event("call watch failed: ${e.message}") }
    }

    // ---------- events ----------
    private fun onPhoneState(c: Context, state: Int, number: String?) {
        val old = phoneState; phoneState = state
        if (!number.isNullOrBlank()) lastNumber = number
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> { event("phone ringing" + (number?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")); if (enabled(c)) ringStart(c, Ring(PHONE, PHONE, if (dutch(c)) "Telefoon" else "Phone", number ?: lastNumber, null, null)) else event("call assistant is off") }
            TelephonyManager.CALL_STATE_IDLE -> { if (old == TelephonyManager.CALL_STATE_RINGING) event("ringing stopped (answered by you, or the caller hung up)"); ringEnd(PHONE); if (session?.info?.pkg == PHONE) endSession("call ended") }
            TelephonyManager.CALL_STATE_OFFHOOK -> { ringEnd(PHONE) }
        }
    }

    /** From CallListener: a notification of any app appeared or disappeared. */
    fun onNotification(c: Context, sbn: StatusBarNotification, posted: Boolean) {
        val n = sbn.notification ?: return
        if (sbn.packageName == c.packageName) return
        if (n.category != Notification.CATEGORY_CALL) { if (posted) onChatNotification(c, sbn); return }
        val telecom = sbn.packageName.contains("dialer") || sbn.packageName.contains("incallui") || sbn.packageName.contains("telecom") || sbn.packageName.contains("phone")
        val key = "n:" + sbn.key
        if (!posted) { ringEnd(key); if (session?.info?.key == key) endSession("call ended"); return }
        if (!enabled(c)) return
        val ex = n.extras
        val type = ex.getInt("android.callType", 0)   // 1 = incoming (CallStyle)
        var answer: PendingIntent? = null; var decline: PendingIntent? = null
        n.actions?.forEach { a -> val t = a.title?.toString().orEmpty(); if (ANSWER.containsMatchIn(t)) answer = a.actionIntent else if (DECLINE.containsMatchIn(t)) decline = a.actionIntent }
        val incoming = type == 1 || (type == 0 && answer != null)
        if (!incoming || telecom) return   // regular calls come through the telephony state; the dialer notification only has a name
        val label = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().ifBlank { sbn.packageName }
        event("${sbn.packageName} is calling: $label")
        ringStart(c, Ring(key, sbn.packageName, label, null, answer, decline))
    }

    private fun ringStart(c: Context, r: Ring) {
        if (rings.containsKey(r.key)) { rings[r.key]?.let { if (r.number != null) it.number = r.number }; return }
        val mode = modeFor(c, r.pkg)
        if (mode == "off") { event("${r.label}: no action set for this app (Settings → Calls → Per app)"); return }
        rings[r.key] = r
        val delay = p(c).getInt("call_delay", 20) * 1000L
        event("${r.label} is calling: ${if (mode == "speaker") "I answer" else "I decline and text"} after ${delay / 1000} s if you don't")
        val run = Runnable { timers.remove(r.key); if (rings.containsKey(r.key)) fire(c, rings[r.key]!!, mode) }
        timers[r.key] = run; main.postDelayed(run, delay)
    }

    private fun ringEnd(key: String) { timers.remove(key)?.let { main.removeCallbacks(it) }; rings.remove(key) }

    private fun fire(c: Context, r: Ring, mode: String) {
        try {
            if (session != null) return
            if (mode == "speaker") {
                if (!answer(c, r)) { event("couldn't answer (permission missing?): texting instead"); decline(c, r); main.postDelayed({ startTextChat(c, r) }, 800); return }
                event("answered"); main.postDelayed({ startSession(c, Session(c, r, false)) }, if (r.pkg == PHONE) 900L else 1800L)
            } else { decline(c, r); main.postDelayed({ startTextChat(c, r) }, 800) }
        } catch (e: Exception) { event("call handling failed: ${e.message}") }
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
        if (r.pkg == PHONE) { if (c.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) { tm(c).endCall(); event("declined the call") } else event("could not decline (permission missing); the call keeps ringing") }
        else { try { r.decline?.send(); event("declined the ${r.pkg} call") } catch (e: Exception) { } }
        ringEnd(r.key)
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun hangUp(c: Context, info: Ring?) {
        try { if (info == null || info.pkg == PHONE) { if (c.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) tm(c).endCall() } else svc()?.clickByLabel(Regex("hang up|end call|ophangen|gesprek beëindigen|raccrocher|auflegen|leave", RegexOption.IGNORE_CASE)) } catch (e: Exception) { }
    }

    // ---------- silent text conversation ----------
    private fun firstText(c: Context): String {
        val custom = p(c).getString("call_text", "").orEmpty().trim()
        if (custom.isNotEmpty()) return custom
        return if (dutch(c)) "Hoi, dit is ${aiName(c)}, de digitale assistent van ${owner(c)}. ${owner(c)} kan nu niet opnemen. Waarmee kan ik helpen? Je kan hier gewoon antwoorden."
        else "Hi, this is ${aiName(c)}, ${owner(c)}'s AI assistant. ${owner(c)} can't pick up right now. How can I help? You can simply reply here."
    }

    private fun norm(s: String?) = s.orEmpty().filter { it.isLetterOrDigit() }.lowercase()
    private fun sameNumber(a: String?, b: String?): Boolean { val x = a.orEmpty().filter { it.isDigit() }; val y = b.orEmpty().filter { it.isDigit() }; return x.length >= 6 && y.length >= 6 && x.takeLast(8) == y.takeLast(8) }

    /** The caller's number for a call that was just declined: from the phone state, otherwise from the call log. */
    @Suppress("MissingPermission")
    private fun numberFor(c: Context, r: Ring): String? {
        (r.number ?: lastNumber)?.takeIf { it.isNotBlank() }?.let { return it }
        if (c.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            c.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE), null, null, CallLog.Calls.DATE + " DESC LIMIT 1")?.use { cur -> if (cur.moveToFirst() && System.currentTimeMillis() - cur.getLong(1) < 120_000L) cur.getString(0) else null }
        } catch (e: Exception) { null }
    }

    private fun startTextChat(c: Context, r: Ring) {
        val msg = firstText(c)
        if (r.pkg == PHONE) {
            val num = numberFor(c, r)
            if (num.isNullOrBlank()) { event("missed call: number unknown, nothing sent"); log(c, r.label, if (dutch(c)) "Gemiste oproep (nummer onbekend, niets verstuurd)." else "Missed call (number unknown, nothing sent)."); notify(c, r.label, if (dutch(c)) "Gemiste oproep. Ik kon het nummer niet zien." else "Missed call. I couldn't see the number."); return }
            val chat = Chat("sms|" + num.takeLast(8), PHONE, num, num); chat.history.put(JSONObject().put("role", "assistant").put("content", msg)); chat.lastSent = msg
            if (!sendSms(c, num, msg)) return
            chats[chat.key] = chat; armChat(c, chat)
            event("sent a text to $num and I'm waiting for the answer")
        } else {
            val app = try { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(r.pkg, 0)).toString() } catch (e: Exception) { r.pkg }
            val chat = Chat(r.pkg + "|" + norm(r.label), r.pkg, r.label, null); chat.history.put(JSONObject().put("role", "assistant").put("content", msg)); chat.lastSent = msg
            chats[chat.key] = chat; armChat(c, chat)
            event("missed $app call from ${r.label}: sending the first message in the chat")
            svc()?.runTask("In $app, open the chat with \"${r.label}\" (use the message option of the missed-call notification if there is one) and send exactly this message, then finish: $msg")
        }
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun sendSms(c: Context, num: String, msg: String): Boolean {
        if (c.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) { event("no SMS permission: can't text $num"); notify(c, num, if (dutch(c)) "Gemiste oproep. Geen sms-toestemming." else "Missed call. No SMS permission."); return false }
        return try {
            val sms = if (android.os.Build.VERSION.SDK_INT >= 31) c.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            sms.sendMultipartTextMessage(num, null, sms.divideMessage(msg), null, null); true
        } catch (e: Exception) { event("SMS failed: ${e.message}"); false }
    }

    private fun armChat(c: Context, chat: Chat) {
        chatTimers.remove(chat.key)?.let { main.removeCallbacks(it) }
        val r = Runnable { finishChat(c, chat, "no answer for 45 minutes") }
        chatTimers[chat.key] = r; main.postDelayed(r, 45 * 60_000L)
    }

    /** An SMS arrived (from SmsReceiver). */
    fun onSms(c: Context, sender: String?, body: String?) {
        if (body.isNullOrBlank()) return
        val chat = chats.values.firstOrNull { it.pkg == PHONE && sameNumber(it.number, sender) } ?: return
        event("text from ${chat.label}: $body")
        chatReply(c, chat, body)
    }

    /** A message notification of another app: is it the caller we are chatting with? */
    private fun onChatNotification(c: Context, sbn: StatusBarNotification) {
        if (chats.isEmpty()) return
        val n = sbn.notification ?: return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val chat = chats.values.firstOrNull { it.pkg == sbn.packageName && it.pkg != PHONE && (norm(title).contains(norm(it.label)) || norm(it.label).contains(norm(title))) && norm(title).isNotEmpty() } ?: return
        val action = n.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true } ?: return
        val text = lastMessageText(n) ?: return
        if (text == chat.lastSeen || text == chat.lastSent || text.startsWith("You:") || text.startsWith("Jij:")) return
        chat.lastSeen = text
        event("message from ${chat.label}: $text")
        chatReply(c, chat, text, action)
    }

    private fun lastMessageText(n: Notification): String? {
        @Suppress("DEPRECATION")
        val msgs = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (msgs != null && msgs.isNotEmpty()) { val b = msgs.last() as? Bundle; b?.getCharSequence("text")?.toString()?.let { return it } }
        return n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    }

    private fun chatReply(c: Context, chat: Chat, incoming: String, action: Notification.Action? = null) {
        armChat(c, chat)
        chat.history.put(JSONObject().put("role", "user").put("content", incoming))
        thread {
            val reply = try { chatAsk(c, chat) } catch (e: Exception) { event("chat AI error: ${e.message}"); return@thread }
            main.post {
                val end = reply.contains("[END]"); val say = reply.replace("[END]", "").trim()
                if (say.isNotEmpty()) {
                    chat.history.put(JSONObject().put("role", "assistant").put("content", say)); chat.lastSent = say; chat.replies++
                    if (chat.pkg == PHONE) sendSms(c, chat.number!!, say) else replyViaNotification(c, action, say)
                }
                if (end || chat.replies >= 8) finishChat(c, chat, "conversation complete")
            }
        }
    }

    private fun replyViaNotification(c: Context, a: Notification.Action?, text: String) {
        try {
            val ri = a?.remoteInputs ?: return; val intent = Intent(); val b = Bundle()
            ri.forEach { b.putCharSequence(it.resultKey, text) }
            RemoteInput.addResultsToIntent(ri, intent, b)
            a.actionIntent.send(c, 0, intent)
        } catch (e: Exception) { event("couldn't reply through the notification: ${e.message}") }
    }

    private fun chatAsk(c: Context, chat: Chat): String {
        val cfg = svc()?.callConfig() ?: throw RuntimeException("no API key")
        val nl = dutch(c); val who = owner(c); val ai = aiName(c)
        val rules = p(c).getString("call_rules", "").orEmpty().trim().ifEmpty { if (nl) "Vraag wie er is en waarom hij/zij belde, noteer een boodschap en zeg dat $who zo snel mogelijk antwoordt." else "Ask who it is and why they called, take a message and say $who will reply as soon as possible." }
        val sys = "You are $ai, the AI assistant of $who, chatting by text message with someone who just called $who and didn't get an answer. You already told them you are an AI assistant. Write ${if (nl) "Dutch" else "English"}, short and natural (one or two sentences, no markdown). Your job: $rules " +
            "Never share personal information about $who (address, schedule, whereabouts, finances, health), never agree to payments, purchases, links or codes, never promise anything except passing on the message. If it is spam or sales, politely end it. When you have what you need (or they say goodbye), end with a short goodbye and add the exact token [END] at the very end."
        val body = JSONObject().put("model", if (cfg.hearth) cfg.model else "claude-haiku-5-5").put("max_tokens", 160).put("system", sys).put("messages", chat.history)
        val resp = Api.post(cfg.url, cfg.key, body); Usage.record(c, "claude-haiku-5-5", resp, cfg.hearth)
        val content = resp.getJSONArray("content")
        return (0 until content.length()).map { content.getJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }.trim()
    }

    private fun finishChat(c: Context, chat: Chat, why: String) {
        if (chats.remove(chat.key) == null) return
        chatTimers.remove(chat.key)?.let { main.removeCallbacks(it) }
        event("text chat with ${chat.label} ended ($why)")
        val transcript = (0 until chat.history.length()).joinToString("\n") { val o = chat.history.getJSONObject(it); (if (o.optString("role") == "user") "Caller: " else "Assistant: ") + o.optString("content") }
        thread {
            val summary = try { summarize(c, transcript) } catch (e: Exception) { transcript.takeLast(300) }
            main.post { val s = if (chat.history.length() <= 1) (if (dutch(c)) "Gemiste oproep. Ik stuurde een bericht, maar er kwam geen antwoord." else "Missed call. I sent a message but got no reply.") else summary; log(c, chat.label, s); notify(c, chat.label, s) }
        }
    }

    private fun summarize(c: Context, t: String): String {
        if (t.isBlank()) return ""
        val cfg = svc()?.callConfig() ?: return t.takeLast(300)
        val body = JSONObject().put("model", if (cfg.hearth) cfg.model else "claude-haiku-5-5").put("max_tokens", 160)
            .put("system", "Summarize this conversation for the owner in ${if (dutch(c)) "Dutch" else "English"}, 1-2 sentences: who it was (name/number if said), what they want, and whether a call back is needed. No preamble.")
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", t)))
        val resp = Api.post(cfg.url, cfg.key, body); Usage.record(c, "claude-haiku-5-5", resp, cfg.hearth)
        val content = resp.getJSONArray("content"); return (0 until content.length()).map { content.getJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString(" ") { it.optString("text") }.trim()
    }

    // ---------- tests from Settings ----------
    fun testSession(c: Context) { if (session != null) return; startSession(c, Session(c, Ring("test", "test", if (dutch(c)) "Test" else "Test", null, null, null), true)) }
    fun testText(c: Context): String { val m = firstText(c); event("test: this is the first text it would send: $m"); return m }

    // ---------- speaker conversation ----------
    fun startFromKey(c: Context) {
        if (session != null) return
        val info = Ring("key", if (phoneState != TelephonyManager.CALL_STATE_IDLE) PHONE else "voip", if (dutch(c)) "Lopend gesprek" else "Current call", lastNumber, null, null)
        startSession(c, Session(c, info, true))
    }

    private fun startSession(c: Context, s: Session) { session = s; s.begin() }
    fun endSession(why: String) { session?.finish(why) }

    /** One spoken conversation with the person on the other end. */
    class Session(val c: Context, val info: Ring, val userInitiated: Boolean) {
        private val h = Handler(Looper.getMainLooper())
        private val history = JSONArray()
        private var rec: SpeechRecognizer? = null
        private var silent = 0
        private var turns = 0
        private var done = false
        private var errs = 0
        private val startedAt = System.currentTimeMillis()
        private val transcript = StringBuilder()
        private val audio = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        private val nl = dutch(c)

        fun begin() {
            event("conversation started with ${info.label}")
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
                override fun onError(error: Int) { if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) noSpeech() else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS || ++errs >= 4) cannotHear("recognizer error $error") else h.postDelayed({ listen() }, 500) }
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

        /** Nothing was heard: the microphone is probably blocked during the call. Switch to texting the caller. */
        private fun cannotHear(why: String) {
            event("I can't hear the caller ($why)")
            if (done) return
            if (turns == 0 && !userInitiated && info.pkg != "test") {
                say(if (nl) "Sorry, ik kan u niet goed verstaan. Ik stuur u een bericht. Tot zo." else "Sorry, I can't hear you well. I'll send you a text. Bye for now.") { finishToText() }
            } else say(if (nl) "Ik hoor u niet meer. Ik geef door dat u gebeld heeft. Tot ziens." else "I can't hear you anymore. I'll let them know you called. Goodbye.") { finish("no one answered") }
        }

        private fun finishToText() {
            if (done) return; done = true
            h.removeCallbacksAndMessages(null); try { rec?.destroy() } catch (e: Exception) { }; rec = null; svc()?.onSpeechDone = null
            hangUp(c, info); speakerOn(false); session = null
            main.postDelayed({ startTextChat(c, info) }, 800)
        }

        private fun noSpeech() {
            silent++
            if (turns == 0 && silent >= 2) { cannotHear("no speech picked up"); return }
            if (silent >= 3) { say(if (nl) "Ik hoor u niet meer. Ik geef door dat u gebeld heeft. Tot ziens." else "I can't hear you anymore. I'll let them know you called. Goodbye.") { finish("no one answered") }; return }
            if (silent == 2) say(if (nl) "Bent u er nog?" else "Are you still there?") { listen() } else listen()
        }

        private fun heard(text: String) {
            transcript.append("Caller: ").append(text).append('\n'); turns++
            event("caller said: $text")
            history.put(JSONObject().put("role", "user").put("content", text))
            thread {
                val reply = try { ask() } catch (e: Exception) { event("call AI error: ${e.message}"); if (nl) "Sorry, er ging iets mis. Ik geef uw boodschap door. Tot ziens. [END]" else "Sorry, something went wrong. I'll pass on your message. Goodbye. [END]" }
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
            event("conversation ended ($why)")
            if (!userInitiated) hangUp(c, info)   // when you asked it to help during your call, the call stays yours
            speakerOn(false)
            session = null
            val t = transcript.toString()
            thread {
                val summary = try { summarize(c, t) } catch (e: Exception) { if (t.isBlank()) "" else t.takeLast(300) }
                main.post {
                    val s = if (summary.isBlank()) (if (nl) "Geen gesprek gevoerd ($why)." else "No conversation ($why).") else summary
                    log(c, info.label, s); notify(c, info.label, s)
                }
            }
        }
    }

    // ---------- log + notification ----------
    private fun log(c: Context, who: String, what: String) {
        val a = try { JSONArray(p(c).getString("call_log", "[]")) } catch (e: Exception) { JSONArray() }
        val out = JSONArray(); out.put(JSONObject().put("t", System.currentTimeMillis()).put("who", who).put("what", what))
        for (i in 0 until minOf(a.length(), 19)) out.put(a.get(i))
        p(c).edit().putString("call_log", out.toString()).apply(); event("$who: $what")
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
