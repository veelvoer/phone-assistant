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

/** Android may make our placeholder recognizer the system default once we are the default assistant, so always bind to a real one explicitly. */
object RecognizerPick {
    fun create(c: android.content.Context): android.speech.SpeechRecognizer {
        try {
            val list = c.packageManager.queryIntentServices(Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0).filter { it.serviceInfo.packageName != c.packageName }
            val pick = list.firstOrNull { it.serviceInfo.packageName == "com.google.android.googlequicksearchbox" } ?: list.firstOrNull()
            if (pick != null) return android.speech.SpeechRecognizer.createSpeechRecognizer(c, android.content.ComponentName(pick.serviceInfo.packageName, pick.serviceInfo.name))
        } catch (e: Exception) { }
        return android.speech.SpeechRecognizer.createSpeechRecognizer(c)
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
