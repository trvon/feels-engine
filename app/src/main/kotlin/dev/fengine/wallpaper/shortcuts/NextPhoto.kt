package dev.fengine.wallpaper.shortcuts

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import dev.fengine.wallpaper.R
import dev.fengine.wallpaper.data.Ledger
import dev.fengine.wallpaper.schedule.RotationScheduler

/**
 * "Next photo" from outside the app (product review item 5): a Quick Settings tile and a 1x1
 * home-screen widget. Both run the same one-shot rotation as the in-app action, and the ledger
 * records them as their own sources so their use can be counted.
 */
class NextPhotoTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; label = getString(R.string.next_photo); updateTile() }
    }

    override fun onClick() {
        RotationScheduler(this).rotateNow(source = Ledger.Source.TILE)
    }
}

class NextPhotoWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val tap = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, NextPhotoWidget::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val views = RemoteViews(context.packageName, R.layout.widget_next).apply { setOnClickPendingIntent(R.id.widget_root, tap) }
        manager.updateAppWidget(ids, views)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_NEXT) {
            RotationScheduler(context).rotateNow(source = Ledger.Source.WIDGET)
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        const val ACTION_NEXT = "dev.fengine.wallpaper.widget.NEXT"

        fun component(context: Context) = ComponentName(context, NextPhotoWidget::class.java)
    }
}
