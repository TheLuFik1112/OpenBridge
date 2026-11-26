package app.openbridge.tile

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.openbridge.MainActivity

// Буфер обмена недоступен из фона (Android 10+), поэтому плитка открывает Activity.
class ClipboardTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "OpenBridge"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = "Отправить буфер"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { launch() } else launch()
    }

    private fun launch() {
        val intent = MainActivity.clipboardIntent(this)
        runCatching { startActivityAndCollapse(intent) }
            .onFailure { runCatching { startActivity(intent) } }
    }
}
