package dev.watchbridge.galaxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 재부팅 뒤에도 브리지를 켜 두었던 상태라면 자동으로 다시 시작한다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .getBoolean(MainActivity.KEY_ENABLED, false)
        if (enabled && BridgeService.hasBlePermissions(context)) {
            context.startForegroundService(Intent(context, BridgeService::class.java))
        }
    }
}
