package com.luming.tray

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class ApiTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile
        if (Build.VERSION.SDK_INT >= 29) {
            tile?.subtitle = "刷新中…"
            tile?.updateTile()
        }

        UsageClient.refresh(this) {
            TrayNotification.show(this)
            updateTile()
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val stats = TrayStore.loadStats(this)
        tile.label = "LuMing API"
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = stats?.balance?.let {
                "余额 ${TrayNotification.money(it)}"
            } ?: "点按刷新"
        }
        tile.state = Tile.STATE_ACTIVE
        tile.updateTile()
    }
}
