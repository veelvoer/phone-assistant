package app.phoneassistant

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Lets the assistant see incoming-call notifications of other apps (WhatsApp, Telegram…). Needs Notification access (Settings → Calls). */
class CallListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) { try { CallManager.onNotification(applicationContext, sbn, true) } catch (e: Exception) { } }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { try { CallManager.onNotification(applicationContext, sbn, false) } catch (e: Exception) { } }
}
