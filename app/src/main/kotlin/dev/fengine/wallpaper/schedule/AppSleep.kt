package dev.fengine.wallpaper.schedule

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.IntentCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import java.util.concurrent.TimeUnit

/**
 * Whether Android may put the app to sleep while it goes unused ("Pause app activity if unused",
 * app hibernation on Android 12+), which cancels the alarms and jobs a Still rotation runs on. Only
 * the selected home live wallpaper is exempt. Reads the public androidx status; no permission, and
 * no battery-optimisation request (docs/product/2026-11-20-product-review.md, item 8).
 */
object AppSleep {
    /** androidx UnusedAppRestrictionsConstants value, or -1 when it could not be read. */
    fun status(context: Context): Int = runCatching {
        PackageManagerCompat.getUnusedAppRestrictionsStatus(context).get(2, TimeUnit.SECONDS)
    }
        .getOrDefault(-1)

    /** The status means Android will pause the app when it goes unused. */
    fun restricted(status: Int): Boolean =
        status == UnusedAppRestrictionsConstants.API_30_BACKPORT ||
            status == UnusedAppRestrictionsConstants.API_30 ||
            status == UnusedAppRestrictionsConstants.API_31

    /** The settings page where the person can let the app keep running. */
    fun settingsIntent(context: Context): Intent = runCatching {
        IntentCompat.createManageUnusedAppRestrictionsIntent(context, context.packageName)
    }
        .getOrElse {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            )
        }
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
