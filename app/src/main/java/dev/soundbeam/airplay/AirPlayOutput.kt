package dev.soundbeam.airplay

import dev.soundbeam.Engine
import dev.soundbeam.audio.SAMPLE_RATE
import dev.soundbeam.core.Device
import dev.soundbeam.core.NowPlaying
import dev.soundbeam.core.Output
import dev.soundbeam.core.OutputState
import dev.soundbeam.core.Protocol
import dev.soundbeam.core.StreamEnv
import dev.soundbeam.util.Bplist
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID
import kotlin.concurrent.thread

/**
 * One AirPlay receiver. AirPlay 2 receivers are paired (transient, or PIN for Apple TV),
 * the control channel is encrypted and audio packets are ChaCha20-Poly1305 sealed.
 * AirPlay 1 (RAOP) receivers get the classic ANNOUNCE/SETUP/RECORD flow with unencrypted ALAC.
 */
class AirPlayOutput(device: Device) : Output(device) {

    private var v2 = device.protocol == Protocol.AIRPLAY2
    private var step = "connect"
    private var fallback = false
    private lateinit var rtsp: RtspConnection
    private lateinit var env: StreamEnv
    private lateinit var master: AirPlayMaster
    private lateinit var sessionUrl: String
    private val sessionId = (Crypto.random.nextInt() and 0x7FFFFFFF).toLong()

    private var address: InetAddress? = null
    private var dataSocket: DatagramSocket? = null
    private var dataPort = 0
    private var remoteControlPort = 0
    private var audioKey: ByteArray? = null

    @Volatile var offsetSamples = 0L
        private set
    @Volatile private var active = false
    @Volatile private var stopped = false
    private var volume = 50
    private var lastMetaKey: String? = null

    override fun start(env: StreamEnv) {
        this.env = env
        this.master = env.airplay
        address = InetAddress.getByName(device.host)
        val raopPort = device.txt["raopport"]?.toIntOrNull()

        if (!v2) {
            runStep { connect(device.port); startV1() }
        } else {
            try {
                runStep { connect(device.port); startV2() }
            } catch (e: Exception) {
                // Some AirPlay 2 speakers (notably Sonos) stall on parts of the AirPlay 2 setup.
                // Retry the same speaker with classic AirPlay 1, which they also accept.
                if (stopped || raopPort == null || (e is PairingException && e.needsPin)) throw e
                val first = e.message
                resetSession()
                v2 = false
                fallback = true
                try {
                    runStep { connect(raopPort); startV1() }
                } catch (e2: Exception) {
                    throw IOException("AirPlay 2 ($first), AirPlay 1 (${e2.message})")
                }
            }
        }
        if (stopped) return
        runCatching { sendVolume() }
        master.activate(this)
        active = true
        Engine.setOutputState(device.id, OutputState.Playing)
        if (v2) startFeedbackLoop()
        Engine.nowPlaying.value?.let { onMetadata(it) }
    }

    /** Runs [block], labelling any failure with the protocol step it happened in. */
    private inline fun runStep(block: () -> Unit) {
        try {
            block()
        } catch (e: PairingException) {
            throw PairingException("$step: ${e.message}", e.needsPin)
        } catch (e: Exception) {
            val why = when (e) {
                is java.net.SocketTimeoutException -> "no reply from the speaker"
                else -> e.message ?: e.javaClass.simpleName
            }
            throw IOException("$step: $why", e)
        }
    }

    private fun connect(port: Int) {
        step = "connect"
        rtsp = openRtsp(port)
        sessionUrl = "rtsp://${rtsp.localAddress}/$sessionId"
    }

    private fun resetSession() {
        if (::rtsp.isInitialized) rtsp.close()
        dataSocket?.close()
        dataSocket = null
        dataPort = 0
        remoteControlPort = 0
        audioKey = null
    }

    private fun openRtsp(port: Int): RtspConnection {
        val c = RtspConnection(device.host, port)
        c.connect()
        val clientId = Engine.airplayIdentity().pairingId
        c.commonHeaders["User-Agent"] = "AirPlay/381.13"
        c.commonHeaders["Client-Instance"] = clientId
        c.commonHeaders["DACP-ID"] = clientId
        c.commonHeaders["Active-Remote"] = (sessionId and 0xFFFFFFFFL).toString()
        return c
    }

    // ------------------------------------------------------------------------------ AirPlay 2

    private fun startV2() {
        step = "GET /info"
        val info = rtsp.request("GET", "/info")
        val flags = if (info.ok) {
            ((runCatching { Bplist.decode(info.body) }.getOrNull() as? Map<*, *>)?.get("statusFlags") as? Long) ?: 0L
        } else {
            0L
        }
        val needsPin = flags and (FLAG_PIN or FLAG_PASSWORD or FLAG_ONE_TIME_PAIRING) != 0L
        val identity = Engine.airplayIdentity()
        step = "pairing"
        val secret: ByteArray = if (needsPin) {
            pinPairAndVerify(identity)
        } else {
            try {
                Pairing.transient(rtsp)
            } catch (e: PairingException) {
                if (!e.needsPin) throw e
                // Reconnect: the receiver may have dropped the half-done transient attempt
                rtsp.close()
                rtsp = openRtsp(device.port)
                pinPairAndVerify(identity)
            }
        }
        rtsp.enableEncryption(secret)
        audioKey = secret.copyOf(32)

        // Sender description as iOS sends it; some receivers won't answer a bare request.
        step = "SETUP session"
        val setupSession = rtsp.request(
            "SETUP", sessionUrl,
            Bplist.encode(
                linkedMapOf(
                    "deviceID" to Engine.airplayDeviceIdColon(),
                    "macAddress" to Engine.airplayDeviceIdColon(),
                    "sessionUUID" to UUID.randomUUID().toString().uppercase(),
                    "groupUUID" to UUID.randomUUID().toString().uppercase(),
                    "groupContainsGroupLeader" to false,
                    "name" to "SoundBeam",
                    "model" to "Android",
                    "sourceVersion" to "366.0",
                    "timingPort" to master.timingPort,
                    "timingProtocol" to "NTP",
                ),
            ),
            PLIST,
        )
        if (!setupSession.ok) throw IOException("rejected (RTSP ${setupSession.status})")

        step = "RECORD"
        val record = rtsp.request("RECORD", sessionUrl)
        if (!record.ok) throw IOException("rejected (RTSP ${record.status})")

        val latency = Engine.airplayLatencyMs.value.toLong() * SAMPLE_RATE / 1000
        val stream = linkedMapOf<String, Any?>(
            "audioFormat" to 0x40000, // ALAC 44100/16/2
            "audioMode" to "default",
            "controlPort" to master.controlPort,
            "ct" to 2, // ALAC
            "isMedia" to true,
            "latencyMax" to maxOf(88200L, latency + 22050),
            "latencyMin" to minOf(11025L, latency),
            "shk" to audioKey,
            "spf" to 352,
            "sr" to SAMPLE_RATE,
            "type" to 96, // real-time audio
            "supportsDynamicStreamID" to false,
            "streamConnectionID" to sessionId,
        )
        step = "SETUP stream"
        val setupStream = rtsp.request(
            "SETUP", sessionUrl, Bplist.encode(mapOf("streams" to listOf(stream))), PLIST,
        )
        if (!setupStream.ok) throw IOException("rejected (RTSP ${setupStream.status})")
        val resp = Bplist.decode(setupStream.body) as? Map<*, *>
        val s0 = (resp?.get("streams") as? List<*>)?.firstOrNull() as? Map<*, *>
        dataPort = (s0?.get("dataPort") as? Long)?.toInt() ?: 0
        remoteControlPort = (s0?.get("controlPort") as? Long)?.toInt() ?: 0
        if (dataPort == 0) throw IOException("Receiver did not return a data port")
        dataSocket = DatagramSocket().also { it.connect(address, dataPort) }
    }

    private fun pinPairAndVerify(identity: PairingIdentity): ByteArray {
        var accessory = Engine.airplayPairing(device.id)
        if (accessory == null) {
            Pairing.pinStart(rtsp)
            Engine.setOutputState(device.id, OutputState.WaitingForPin(device.name))
            val pin = Engine.awaitPin(device) ?: throw IOException("Pairing cancelled")
            Engine.setOutputState(device.id, OutputState.Connecting)
            accessory = Pairing.pinSetup(rtsp, pin, identity)
            Engine.saveAirplayPairing(device.id, accessory)
        }
        return try {
            Pairing.verify(rtsp, identity, accessory)
        } catch (e: PairingException) {
            if (e.needsPin) Engine.forgetAirplayPairing(device.id)
            throw e
        }
    }

    private fun startFeedbackLoop() {
        thread(isDaemon = true, name = "airplay-feedback") {
            while (!stopped) {
                try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
                if (stopped) break
                try {
                    rtsp.request("POST", "/feedback")
                } catch (e: Exception) {
                    if (!stopped) Engine.setOutputState(device.id, OutputState.Failed("connection lost"))
                    break
                }
            }
        }
    }

    // ------------------------------------------------------------------------------ AirPlay 1

    private fun startV1() {
        if (fallback || needsAuthSetup()) {
            // Sonos and some AirPort firmware reject ANNOUNCE (403) unless auth-setup came first.
            // We send the "no encryption" flag plus a Curve25519 public key and ignore the reply.
            step = "auth-setup"
            rtsp.request("POST", "/auth-setup", byteArrayOf(1) + Crypto.x25519KeyPair().public, "application/octet-stream")
        }
        step = "ANNOUNCE"
        val local = rtsp.localAddress
        val sdp = "v=0\r\n" +
            "o=iTunes $sessionId 0 IN IP4 $local\r\n" +
            "s=iTunes\r\n" +
            "c=IN IP4 ${device.host}\r\n" +
            "t=0 0\r\n" +
            "m=audio 0 RTP/AVP 96\r\n" +
            "a=rtpmap:96 AppleLossless\r\n" +
            "a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100\r\n"
        val announce = rtsp.request("ANNOUNCE", sessionUrl, sdp.toByteArray(), "application/sdp")
        if (!announce.ok) throw IOException("rejected (RTSP ${announce.status})")

        step = "SETUP"
        val setup = rtsp.request(
            "SETUP", sessionUrl,
            headers = mapOf(
                "Transport" to "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;" +
                    "control_port=${master.controlPort};timing_port=${master.timingPort}",
            ),
        )
        if (!setup.ok) throw IOException("rejected (RTSP ${setup.status})")
        setup.header("Session")?.substringBefore(';')?.trim()?.let { rtsp.commonHeaders["Session"] = it }
        val transport = setup.header("Transport").orEmpty()
        fun param(name: String) = Regex("$name=(\\d+)").find(transport)?.groupValues?.get(1)?.toInt() ?: 0
        dataPort = param("server_port")
        remoteControlPort = param("control_port")
        if (dataPort == 0) throw IOException("Receiver did not return a server port")
        dataSocket = DatagramSocket().also { it.connect(address, dataPort) }

        step = "RECORD"
        val (seq, rtp) = master.nextPosition()
        val record = rtsp.request(
            "RECORD", sessionUrl,
            headers = mapOf("Range" to "npt=0-", "RTP-Info" to "seq=$seq;rtptime=$rtp"),
        )
        if (!record.ok) throw IOException("rejected (RTSP ${record.status})")
    }

    /** Features bit 26 ("Authentication_8") or a Sonos speaker means auth-setup is expected. */
    private fun needsAuthSetup(): Boolean {
        val f = (device.txt["features"] ?: device.txt["ft"])?.split(',')?.firstOrNull()
            ?.trim()?.removePrefix("0x")?.removePrefix("0X")?.toLongOrNull(16) ?: 0L
        val sonos = device.txt["manufacturer"]?.contains("Sonos", true) == true ||
            device.model?.contains("Sonos", true) == true
        return f and (1L shl 26) != 0L || sonos
    }

    // ------------------------------------------------------------------------------ streaming

    fun controlTarget(): Pair<InetAddress, Int>? {
        val a = address ?: return null
        return if (remoteControlPort > 0) a to remoteControlPort else null
    }

    fun remoteAddress(): InetAddress? = address

    fun sendPacket(seq: Int, plain: ByteArray) {
        if (!active) return
        val sock = dataSocket ?: return
        val out = audioKey?.let { encrypt(it, seq, plain) } ?: plain
        try {
            sock.send(DatagramPacket(out, out.size))
        } catch (_: Exception) {
        }
    }

    private fun encrypt(key: ByteArray, seq: Int, plain: ByteArray): ByteArray {
        val nonce = ByteArray(12)
        nonce[4] = seq.toByte()
        nonce[5] = (seq shr 8).toByte()
        val aad = plain.copyOfRange(4, 12) // timestamp + SSRC
        val sealed = Crypto.chachaSeal(key, nonce, plain.copyOfRange(12, plain.size), aad)
        val out = ByteArray(12 + sealed.size + 8)
        System.arraycopy(plain, 0, out, 0, 12)
        System.arraycopy(sealed, 0, out, 12, sealed.size)
        System.arraycopy(nonce, 4, out, 12 + sealed.size, 8)
        return out
    }

    override fun setDelayMs(ms: Int) {
        offsetSamples = ms.toLong() * SAMPLE_RATE / 1000
    }

    override fun setVolume(percent: Int) {
        volume = percent
        if (active) Engine.scope.launch { runCatching { sendVolume() } }
    }

    private fun sendVolume() {
        val db = if (volume <= 0) -144.0 else -30.0 + 30.0 * volume / 100.0
        rtsp.request(
            "SET_PARAMETER", sessionUrl,
            String.format(java.util.Locale.US, "volume: %.6f\r\n", db).toByteArray(),
            "text/parameters",
        )
    }

    override fun onMetadata(np: NowPlaying) {
        if (!active) return
        Engine.scope.launch {
            runCatching {
                val rtpInfo = mapOf("RTP-Info" to "rtptime=${master.currentRtpTime()}")
                val key = "${np.title}|${np.artist}|${np.album}|${np.artworkJpeg?.size}"
                if (key != lastMetaKey) {
                    lastMetaKey = key
                    rtsp.request("SET_PARAMETER", sessionUrl, dmap(np), "application/x-dmap-tagged", rtpInfo)
                    np.artworkJpeg?.let { rtsp.request("SET_PARAMETER", sessionUrl, it, "image/jpeg", rtpInfo) }
                }
                if (np.durationMs > 0) {
                    val now = master.currentRtpTime()
                    val pos = np.currentPositionMs(android.os.SystemClock.elapsedRealtime()) * SAMPLE_RATE / 1000
                    val start = (now - pos) and 0xFFFFFFFFL
                    val end = (start + np.durationMs * SAMPLE_RATE / 1000) and 0xFFFFFFFFL
                    rtsp.request(
                        "SET_PARAMETER", sessionUrl,
                        "progress: $start/$now/$end\r\n".toByteArray(), "text/parameters", rtpInfo,
                    )
                }
            }
        }
    }

    private fun dmap(np: NowPlaying): ByteArray {
        val inner = ByteArrayOutputStream()
        fun tag(name: String, value: String?) {
            val v = (value ?: "").toByteArray()
            inner.write(name.toByteArray())
            inner.write(byteArrayOf((v.size shr 24).toByte(), (v.size shr 16).toByte(), (v.size shr 8).toByte(), v.size.toByte()))
            inner.write(v)
        }
        tag("minm", np.title)
        tag("asar", np.artist)
        tag("asal", np.album)
        val body = inner.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("mlit".toByteArray())
        out.write(byteArrayOf((body.size shr 24).toByte(), (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()))
        out.write(body)
        return out.toByteArray()
    }

    override fun stop() {
        stopped = true
        active = false
        if (::master.isInitialized) master.deactivate(this)
        if (::rtsp.isInitialized) {
            runCatching {
                if (v2) rtsp.request("TEARDOWN", sessionUrl, Bplist.encode(emptyMap<String, Any>()), PLIST)
                else rtsp.request("TEARDOWN", sessionUrl)
            }
            rtsp.close()
        }
        dataSocket?.close()
    }

    companion object {
        private const val PLIST = "application/x-apple-binary-plist"
        private const val FLAG_PIN = 1L shl 3
        private const val FLAG_PASSWORD = 1L shl 7
        private const val FLAG_ONE_TIME_PAIRING = 1L shl 9
    }
}
