package dev.soundbeam

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import dev.soundbeam.core.NowPlaying
import java.io.ByteArrayOutputStream

/**
 * Reads title, artist, album, artwork and position from whichever app is playing, using the
 * media sessions API. Android only exposes other apps' sessions to notification listeners,
 * so the user has to grant SoundBeam notification access once.
 */
class NowPlayingListener : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    private var current: MediaController? = null
    private var lastArtKey: Any? = null
    private var lastArt: ByteArray? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // switch to another session if this one stopped and a different one is playing
            if (state?.state != PlaybackState.STATE_PLAYING) {
                msm?.let { m -> runCatching { pick(m.getActiveSessions(component())) } }
            }
            publish()
        }
        override fun onSessionDestroyed() {
            msm?.let { m -> runCatching { pick(m.getActiveSessions(component())) } }
        }
    }

    private fun component() = ComponentName(this, NowPlayingListener::class.java)

    override fun onListenerConnected() {
        Engine.init(applicationContext)
        Engine.notificationAccess.value = true
        val m = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        msm = m
        runCatching {
            m.addOnActiveSessionsChangedListener(sessionsListener, component(), handler)
            pick(m.getActiveSessions(component()))
        }
    }

    override fun onListenerDisconnected() {
        runCatching { msm?.removeOnActiveSessionsChangedListener(sessionsListener) }
        current?.unregisterCallback(callback)
        current = null
        Engine.mediaController = null
    }

    private fun pick(list: List<MediaController>) {
        val playing = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { it.packageName == current?.packageName }
            ?: list.firstOrNull()
        if (playing?.sessionToken == current?.sessionToken) return
        current?.unregisterCallback(callback)
        current = playing
        Engine.mediaController = playing
        playing?.registerCallback(callback, handler)
        publish()
    }

    private fun publish() {
        val c = current
        if (c == null) {
            Engine.nowPlaying.value = null
            return
        }
        val md = c.metadata
        val st = c.playbackState
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val artKey = bmp?.let { System.identityHashCode(it) to (md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "") }
        if (artKey != lastArtKey) {
            lastArtKey = artKey
            lastArt = bmp?.let { toJpeg(it) }
        }
        Engine.nowPlaying.value = NowPlaying(
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            positionMs = st?.position ?: 0L,
            positionUpdatedAt = st?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            playing = st?.state == PlaybackState.STATE_PLAYING,
            artworkJpeg = lastArt,
            sourcePackage = c.packageName,
        )
    }

    private fun toJpeg(src: Bitmap): ByteArray? = runCatching {
        val max = 600
        val scale = minOf(1f, max.toFloat() / maxOf(src.width, src.height))
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        } else {
            src
        }
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }.getOrNull()

    companion object {
        fun hasAccess(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.split(':').any { ComponentName.unflattenFromString(it)?.packageName == ctx.packageName }
        }
    }
}
