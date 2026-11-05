package app.phoneassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/** Text messages from a caller the assistant is chatting with (Settings → Calls). Needs the SMS permission. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        try {
            val msgs = Telephony.Sms.Intents.getMessagesFromIntent(i) ?: return
            val sender = msgs.firstOrNull()?.originatingAddress
            val body = msgs.joinToString("") { it.messageBody.orEmpty() }
            CallManager.onSms(c.applicationContext, sender, body)
        } catch (e: Exception) { }
    }
}
