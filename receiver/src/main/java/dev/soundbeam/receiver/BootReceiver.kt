package dev.soundbeam.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the receiver when the TV boots, so it is always available like a built-in speaker. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("receiver", Context.MODE_PRIVATE)
            if (prefs.getBoolean("autostart", true)) ReceiverService.start(context)
        }
    }
}
