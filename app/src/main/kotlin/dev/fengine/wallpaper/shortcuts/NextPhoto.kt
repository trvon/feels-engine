package dev.fengine.wallpaper.shortcuts

import android.app.PendingIntent
import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import dev.fengine.wallpaper.FengineApp
import dev.fengine.wallpaper.R
import dev.fengine.wallpaper.data.Ledger
import dev.fengine.wallpaper.schedule.RotationScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * "Next photo" from outside the app (product review item 5): a Quick Settings tile and a 1x1
 * home-screen widget. Both run the same one-shot rotation as the in-app action, and the ledger
 * records them as their own sources so their use can be counted.
 */
class NextPhotoTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = getString(R.string.next_photo)
        tile.updateTile()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            scope.launch {
                val activeName = runCatching {
                    (application as FengineApp).settings.read().homeGroup?.name
                }
                    .getOrNull()
                qsTile?.apply {
                    subtitle = activeName
                    updateTile()
                }
            }
        }
    }

    override fun onClick() {
        RotationScheduler(this).rotateNow(source = Ledger.Source.TILE)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun component(context: Context) = ComponentName(context, NextPhotoTileService::class.java)

        fun canRequestAdd(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        /** Prompts the user to add the Quick Settings tile directly (Android 13+). */
        fun requestAdd(context: Context, onResult: (Boolean) -> Unit = {}) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
            val sbm = context.getSystemService(StatusBarManager::class.java) ?: return
            sbm.requestAddTileService(
                component(context),
                context.getString(R.string.next_photo),
                Icon.createWithResource(context, R.drawable.ic_next_photo),
                context.mainExecutor,
            ) { result ->
                onResult(
                    result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                        result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                )
            }
        }
    }
}

class NextPhotoWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, buildViews(context))
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

        fun buildViews(context: Context, label: String? = null): RemoteViews {
            val tap =
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(context, NextPhotoWidget::class.java).setAction(ACTION_NEXT),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            return RemoteViews(context.packageName, R.layout.widget_next).apply {
                setOnClickPendingIntent(R.id.widget_root, tap)
                if (!label.isNullOrBlank()) setTextViewText(R.id.widget_label, label)
            }
        }

        fun canPin(context: Context): Boolean = runCatching {
            AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported
        }
            .getOrDefault(false)

        /** Asks the launcher to pin the widget to the home screen (API 26+). */
        fun requestPin(context: Context): Boolean = runCatching {
            val manager = AppWidgetManager.getInstance(context)
            manager.isRequestPinAppWidgetSupported &&
                manager.requestPinAppWidget(component(context), null, null)
        }
            .getOrDefault(false)

        /**
         * Publishes a generated preview to the Android 15+ widget picker so the widget shows the
         * user's active group and live Material You colours. Rate-limited by Android (~2/hr).
         */
        fun publishPreview(context: Context, activeGroupName: String?) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
            runCatching {
                val manager = AppWidgetManager.getInstance(context)
                val views = buildViews(context, activeGroupName)
                manager.setWidgetPreview(
                    component(context),
                    AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
                    views,
                )
            }
        }
    }
}
