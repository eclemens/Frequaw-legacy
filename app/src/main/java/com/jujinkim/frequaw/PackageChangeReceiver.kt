package com.jujinkim.frequaw

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Invalidates the icon caches when any package is installed, replaced or removed.
 *
 * The icon caches key on package name, so a same-name reinstall/update (icon pack or a launched
 * app changing its icon) would otherwise serve stale pixels until process death. Clearing on
 * package change keeps the cache correct and refreshes the widgets.
 */
class PackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // REPLACED fires with EXTRA_REPLACING on the ADDED/REMOVED pair too; ignore those dupes.
        if (intent?.action == Intent.ACTION_PACKAGE_REMOVED &&
            intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        ) return

        Log.d(FrequawApp.TAG_DEBUG, "Package changed (${intent?.action}); clearing icon caches")
        IconPackCache.clear()
        IconBitmapCache.clear()
        Utils.sendUpdateWidgetBr(context.applicationContext)
    }
}
