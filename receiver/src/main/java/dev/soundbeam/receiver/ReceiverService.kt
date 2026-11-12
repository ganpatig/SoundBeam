package dev.soundbeam.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import kotlin.concurrent.thread

/** Keeps the AriaCast receiver running in the background and advertises it on the network. */
class ReceiverService : Service() {

    private var server: AriaServer? = null
    private val player = PcmPlayer()
    private var udp: DatagramSocket? = null
    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) startReceiver()
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startReceiver() {
        running = true
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, notification)
        }

        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "soundbeam-receiver").apply { acquire() }
        multicastLock = wifi.createMulticastLock("soundbeam-receiver").apply { setReferenceCounted(false); acquire() }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "soundbeam:receiver").apply { acquire() }

        val name = receiverName()
        val ip = localIp() ?: "?"
        ReceiverState.update { it.copy(name = name, address = "$ip:$PORT", error = null) }

        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        server = AriaServer(PORT, player, am).also { it.start() }
        thread(isDaemon = true, name = "aria-udp") { udpDiscovery(name) }
        thread(isDaemon = true, name = "aria-stats") {
            while (running) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                server?.pushStats()
            }
        }
        registerNsd(name)
    }

    /** Answers `DISCOVER_AUDIOCAST` broadcasts on UDP 12888. */
    private fun udpDiscovery(name: String) {
        try {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.broadcast = true
            s.bind(java.net.InetSocketAddress(DISCOVERY_PORT))
            udp = s
            val buf = ByteArray(512)
            while (running) {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                if (String(p.data, 0, p.length).trim() != "DISCOVER_AUDIOCAST") continue
                val reply = JSONObject()
                    .put("server_name", name)
                    .put("ip", localIp() ?: "")
                    .put("port", PORT)
                    .put("samplerate", PcmPlayer.RATE)
                    .put("channels", 2)
                    .put("platform", PLATFORM)
                    .toString().toByteArray()
                s.send(DatagramPacket(reply, reply.size, p.address, p.port))
            }
        } catch (e: SocketException) {
            if (running) ReceiverState.update { it.copy(error = "Discovery port busy: ${e.message}") }
        } catch (_: Exception) {
        }
    }

    private fun registerNsd(name: String) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = "_audiocast._tcp"
            port = PORT
            setAttribute("version", "1.0")
            setAttribute("samplerate", PcmPlayer.RATE.toString())
            setAttribute("channels", "2")
            setAttribute("platform", PLATFORM)
            setAttribute("codecs", "PCM")
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {}
        }
        nsd = (getSystemService(Context.NSD_SERVICE) as NsdManager).also {
            runCatching { it.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
        }
        nsdListener = l
    }

    override fun onDestroy() {
        running = false
        runCatching { nsdListener?.let { nsd?.unregisterService(it) } }
        runCatching { server?.stop(1000) }
        player.stop()
        udp?.close()
        wifiLock?.let { if (it.isHeld) it.release() }
        multicastLock?.let { if (it.isHeld) it.release() }
        wakeLock?.let { if (it.isHeld) it.release() }
        ReceiverState.update { it.copy(sender = null, playing = false) }
        super.onDestroy()
    }

    private fun receiverName(): String {
        val model = Build.MODEL.orEmpty().ifEmpty { "Android" }
        return "SoundBeam ($model)"
    }

    private fun localIp(): String? = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address }?.hostAddress

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("receiver", "Receiver", NotificationManager.IMPORTANCE_LOW))
        }
        val flags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val open = PendingIntent.getActivity(this, 0, Intent(this, ReceiverActivity::class.java), flags)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "receiver") else Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("SoundBeam Receiver is on")
            .setContentText("Waiting for audio from your phone")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val PORT = 12889
        const val DISCOVERY_PORT = 12888
        const val PLATFORM = "SoundBeam Receiver"
        const val ACTION_STOP = "dev.soundbeam.receiver.STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, ReceiverService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}
