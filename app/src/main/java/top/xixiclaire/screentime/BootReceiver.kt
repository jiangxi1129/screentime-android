package top.xixiclaire.screentime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms the AlarmManager schedule when the device finishes booting OR when
 * our app is updated/replaced. Without this, alarms would be lost on every
 * reboot — the user would have to open the app once to re-arm.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                if (MainActivity.hasUsageAccess(context)) {
                    AlarmReceiver.scheduleNext(context)
                }
            }
        }
    }
}
