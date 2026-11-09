package app.phoneassistant

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: a shortcut that works on every phone, no special key needed. */
class AssistantTile : TileService() {
    override fun onStartListening() { qsTile?.apply { state = Tile.STATE_INACTIVE; label = "Assistant"; updateTile() } }

    override fun onClick() {
        if (AgentService.instance == null && Protect.paused(this) && Protect.resume(this)) { android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ AgentService.instance?.toggleFromUi(true) }, 1200); return }
        val svc = AgentService.instance
        if (svc != null) { svc.toggleFromUi(true); return }
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION") startActivityAndCollapse(i)
    }
}
