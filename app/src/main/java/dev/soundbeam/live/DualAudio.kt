package dev.soundbeam.live

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings

/**
 * Two Bluetooth headphones on one phone. Apps cannot switch this on themselves - it lives in the
 * phone's own Bluetooth stack - so we detect which flavour the phone has and open the exact
 * system screen for it.
 */
object DualAudio {
    enum class Kind {
        /** Samsung "Dual Audio": tick two headphones in the Media output panel. */
        SAMSUNG,
        /** Android 13+ LE Audio broadcast ("Audio sharing", Auracast). Needs LE Audio headphones. */
        LE_AUDIO_SHARING,
        NONE,
    }

    /** For screenshots/tests. */
    var override: Kind? = null
    var overrideHeadphones: List<String>? = null

    @SuppressLint("MissingPermission", "NewApi")
    fun kind(ctx: Context): Kind {
        override?.let { return it }
        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return Kind.SAMSUNG
        if (Build.VERSION.SDK_INT >= 33) {
            val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            val r = runCatching { adapter?.isLeAudioBroadcastSourceSupported }.getOrNull()
            if (r == BluetoothStatusCodes.FEATURE_SUPPORTED) return Kind.LE_AUDIO_SHARING
        }
        return Kind.NONE
    }

    /** Names of the Bluetooth headphones currently connected for audio. */
    fun connectedHeadphones(ctx: Context): List<String> {
        overrideHeadphones?.let { return it }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val types = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_HEARING_AID,
            26, // TYPE_BLE_HEADSET
            27, // TYPE_BLE_SPEAKER
        )
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in types }
            .map { it.productName?.toString().orEmpty() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    fun openBluetoothSettings(ctx: Context) = launch(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

    /** Opens the system "Media output" picker, where Samsung shows a tick box per headphone. */
    fun openOutputPicker(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 34) {
            val shown = runCatching { MediaRouter2.getInstance(ctx).showSystemOutputSwitcher() }.getOrDefault(false)
            if (shown) return
        }
        val panel = Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
            .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", ctx.packageName)
        if (!launch(ctx, panel)) openBluetoothSettings(ctx)
    }

    /** Opens Android's "Audio sharing" (LE Audio broadcast) settings, falling back to Bluetooth. */
    fun openAudioSharing(ctx: Context) {
        val tries = listOf(
            Intent("android.settings.BLUETOOTH_AUDIO_SHARING_SETTINGS"),
            Intent("com.android.settings.BLUETOOTH_AUDIO_SHARING_SETTINGS"),
        )
        if (tries.none { launch(ctx, it) }) openBluetoothSettings(ctx)
    }

    private fun launch(ctx: Context, i: Intent): Boolean = runCatching {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
