package com.rasel.blocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Overlay permission থাকলেই auto-start
            if (Settings.canDrawOverlays(context)) {
                val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                val serviceIntent = Intent(context, FakeLockService::class.java).apply {
                    putExtra("auto_lock_seconds", prefs.getInt(MainActivity.KEY_AUTO_SECONDS, 30))
                    putExtra("btn_opacity", prefs.getFloat(MainActivity.KEY_OPACITY, 0.7f))
                    putExtra("btn_size", prefs.getInt(MainActivity.KEY_SIZE, 110))
                }
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
