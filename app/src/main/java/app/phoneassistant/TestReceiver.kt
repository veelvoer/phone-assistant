package app.phoneassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** adb automation: `adb shell am broadcast -a app.phoneassistant.RUN --es task "..." -n app.phoneassistant/.TestReceiver`. Guarded by the DUMP permission, which only adb/shell holds. */
class TestReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        android.util.Log.d("PAlog", "TestReceiver ${i.action} service=${AgentService.instance != null}")
        val svc = AgentService.instance ?: return
        when (i.action) {
            "app.phoneassistant.RUN" -> i.getStringExtra("task")?.let { svc.startFromPrefs(it) }
            "app.phoneassistant.STOP" -> svc.stop()
        }
    }
}
