package dev.soundbeam

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.soundbeam.ui.AppActions
import dev.soundbeam.ui.SoundBeamApp
import dev.soundbeam.ui.SoundBeamTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Engine.init(applicationContext)
        setContent {
            SoundBeamTheme {
                val ctx = LocalContext.current
                val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
                    val data = r.data
                    if (r.resultCode == Activity.RESULT_OK && data != null) {
                        ctx.startForegroundService(
                            Intent(ctx, CaptureService::class.java)
                                .setAction(CaptureService.ACTION_START)
                                .putExtra(CaptureService.EXTRA_CODE, r.resultCode)
                                .putExtra(CaptureService.EXTRA_DATA, data),
                        )
                    } else {
                        Engine.status.value = "Audio capture was not allowed"
                    }
                }
                val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
                    if (grants[Manifest.permission.RECORD_AUDIO] == true) {
                        val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        projectionLauncher.launch(mpm.createScreenCaptureIntent())
                    } else {
                        Engine.status.value = "SoundBeam needs the audio permission to pick up sound from other apps"
                    }
                }
                val actions = remember {
                    AppActions(
                        startStreaming = {
                            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
                        },
                        stopStreaming = {
                            ctx.startService(Intent(ctx, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
                        },
                        openNotificationAccess = {
                            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        openUrl = { url ->
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        },
                    )
                }
                SoundBeamApp(actions)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Engine.notificationAccess.value = NowPlayingListener.hasAccess(this)
    }
}
