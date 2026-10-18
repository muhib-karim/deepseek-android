package app.dsandroid.launch

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.dsandroid.R
import app.dsandroid.core.launch.QuickAction

class VoiceTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply { state = Tile.STATE_INACTIVE; label = getString(R.string.voice_mode); updateTile() }
    }
    @Suppress("DEPRECATION")
    override fun onClick() {
        super.onClick()
        unlockAndRun {
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(QuickLaunch.pending(this, QuickAction.VOICE))
            else startActivityAndCollapse(QuickLaunch.intent(this, QuickAction.VOICE).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
