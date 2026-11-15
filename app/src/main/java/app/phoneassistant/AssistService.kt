package app.phoneassistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService

/** Makes the app selectable as the phone's default digital assistant: the assist button / gesture / power-button hold then starts the assistant. */
object AssistLaunch {
    fun start(c: android.content.Context) {
        if (AgentService.instance == null && Protect.paused(c)) Protect.resume(c)
        val svc = AgentService.instance
        if (svc != null) svc.toggleFromUi(false)
        else c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))   // service off: open the app so it can be turned on
    }
}

/** Android may make our placeholder recognizer the system default once we are the default assistant, so always bind to a real one explicitly.
 *  Some phones ship a broken recognizer (errors 11/12 on every try): then rotate through the others and remember the one that works. */
object RecognizerPick {
    private const val ON_DEVICE = "on-device"
    private fun p(c: android.content.Context) = c.getSharedPreferences("p", android.content.Context.MODE_PRIVATE)

    private fun candidates(c: android.content.Context): List<String> {
        val out = ArrayList<String>()
        try {
            val def = android.provider.Settings.Secure.getString(c.contentResolver, "voice_recognition_service").orEmpty()
            if (def.isNotEmpty() && !def.startsWith(c.packageName + "/")) out.add(def)
            val list = c.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0).filter { it.serviceInfo.packageName != c.packageName }
                .sortedBy { if (it.serviceInfo.packageName == "com.google.android.googlequicksearchbox") 0 else 1 }
            list.map { android.content.ComponentName(it.serviceInfo.packageName, it.serviceInfo.name).flattenToString() }.forEach { if (it !in out) out.add(it) }
        } catch (e: Exception) { }
        if (android.os.Build.VERSION.SDK_INT >= 31 && android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(c)) out.add(ON_DEVICE)
        return out
    }

    fun create(c: android.content.Context): android.speech.SpeechRecognizer {
        val all = candidates(c)
        val pick = all.getOrNull(p(c).getInt("rec_idx", 0).mod(all.size.coerceAtLeast(1)))
        try {
            if (pick == ON_DEVICE && android.os.Build.VERSION.SDK_INT >= 31) return android.speech.SpeechRecognizer.createOnDeviceSpeechRecognizer(c)
            if (pick != null) return android.speech.SpeechRecognizer.createSpeechRecognizer(c, android.content.ComponentName.unflattenFromString(pick))
        } catch (e: Exception) { }
        return android.speech.SpeechRecognizer.createSpeechRecognizer(c)
    }

    private val bad = HashSet<String>()   // refused us this run (permission/language): skip

    /** Switch to the next usable recognizer; returns its name for the log. */
    fun next(c: android.content.Context, markBad: Boolean = false): String {
        val all = candidates(c); if (all.isEmpty()) return "default"
        var i = p(c).getInt("rec_idx", 0).mod(all.size)
        if (markBad) bad.add(all[i])
        if (bad.size >= all.size) bad.clear()
        do { i = (i + 1).mod(all.size) } while (all[i] in bad)
        p(c).edit().putInt("rec_idx", i).apply()
        return all[i].substringBefore('/')
    }
}

class AssistService : VoiceInteractionService()

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

class AssistSession(private val c: android.content.Context) : VoiceInteractionSession(c) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        AssistLaunch.start(c)
        hide()
    }
}

/** Required by Android for assistant apps; the app does its own recognition, so this just reports that it is not available. */
class AssistRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, listener: Callback?) { try { listener?.error(android.speech.SpeechRecognizer.ERROR_CLIENT) } catch (e: Exception) { } }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}

/** Fallback for phones that start assistants through the ASSIST intent instead of a voice interaction session. */
class AssistActivity : Activity() {
    override fun onCreate(b: Bundle?) { super.onCreate(b); AssistLaunch.start(this); finish() }
}
