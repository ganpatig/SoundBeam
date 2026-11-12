package dev.soundbeam.airplay

import dev.soundbeam.util.Tlv8
import java.io.IOException

/** Our long-term HomeKit identity (persisted), used for PIN pairing with Apple TVs and similar. */
class PairingIdentity(val pairingId: String, val seed: ByteArray) {
    val publicKey: ByteArray by lazy { Crypto.ed25519Public(seed) }
}

/** What we remember about a receiver after a successful PIN pairing. */
class PairedAccessory(val accessoryId: String, val publicKey: ByteArray)

class PairingException(message: String, val needsPin: Boolean = false) : IOException(message)

/**
 * AirPlay 2 / HomeKit pairing over an [RtspConnection] (before encryption is enabled).
 * - [transient]: SRP with the fixed PIN 3939, used by HomePod and most AirPlay 2 speakers.
 * - [pinSetup] + [verify]: one-time PIN pairing followed by per-session verification.
 */
object Pairing {
    private const val CT = "application/octet-stream"

    private fun post(rtsp: RtspConnection, path: String, body: ByteArray, hkp: String): Map<Int, ByteArray> {
        val r = rtsp.request("POST", path, body, CT, mapOf("X-Apple-HKP" to hkp))
        if (r.status == 470 || r.status == 401) throw PairingException("Receiver requires a PIN", needsPin = true)
        if (!r.ok) throw PairingException("$path failed (RTSP ${r.status})")
        val tlv = Tlv8.decode(r.body)
        tlv[Tlv8.ERROR]?.let {
            val code = it.firstOrNull()?.toInt() ?: 0
            throw PairingException("Pairing error $code", needsPin = code == 2 || code == 3)
        }
        return tlv
    }

    /** Returns the 64-byte shared secret. */
    fun transient(rtsp: RtspConnection): ByteArray {
        val m2 = post(
            rtsp, "/pair-setup",
            Tlv8.encode(
                Tlv8.METHOD to Tlv8.byte(0),
                Tlv8.STATE to Tlv8.byte(1),
                Tlv8.FLAGS to Tlv8.byte(0x10),
            ),
            "4",
        )
        val srp = SrpClient("Pair-Setup", "3939")
        val salt = m2[Tlv8.SALT] ?: throw PairingException("No salt in pair-setup M2")
        val b = m2[Tlv8.PUBLIC_KEY] ?: throw PairingException("No public key in pair-setup M2")
        val proof = srp.processChallenge(salt, b)
        val m4 = post(
            rtsp, "/pair-setup",
            Tlv8.encode(
                Tlv8.STATE to Tlv8.byte(3),
                Tlv8.PUBLIC_KEY to srp.publicA.toMinBytes(),
                Tlv8.PROOF to proof,
            ),
            "4",
        )
        val m2Proof = m4[Tlv8.PROOF] ?: throw PairingException("No proof in pair-setup M4")
        if (!srp.verifyServer(m2Proof)) throw PairingException("Receiver proof did not verify")
        return srp.sessionKey
    }

    /** Asks the receiver (e.g. Apple TV) to show a PIN on screen. */
    fun pinStart(rtsp: RtspConnection) {
        val r = rtsp.request("POST", "/pair-pin-start", null, null, mapOf("X-Apple-HKP" to "3"))
        if (!r.ok) throw PairingException("pair-pin-start failed (RTSP ${r.status})")
    }

    /** Full HomeKit pair-setup (M1..M6) with a user-entered PIN. */
    fun pinSetup(rtsp: RtspConnection, pin: String, id: PairingIdentity): PairedAccessory {
        val m2 = post(
            rtsp, "/pair-setup",
            Tlv8.encode(Tlv8.METHOD to Tlv8.byte(0), Tlv8.STATE to Tlv8.byte(1)),
            "3",
        )
        val srp = SrpClient("Pair-Setup", pin)
        val proof = srp.processChallenge(
            m2[Tlv8.SALT] ?: throw PairingException("No salt"),
            m2[Tlv8.PUBLIC_KEY] ?: throw PairingException("No public key"),
        )
        val m4 = post(
            rtsp, "/pair-setup",
            Tlv8.encode(Tlv8.STATE to Tlv8.byte(3), Tlv8.PUBLIC_KEY to srp.publicA.toMinBytes(), Tlv8.PROOF to proof),
            "3",
        )
        if (!srp.verifyServer(m4[Tlv8.PROOF] ?: ByteArray(0))) throw PairingException("Wrong PIN")

        val k = srp.sessionKey
        val deviceX = Crypto.hkdfSha512(k, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
        val idBytes = id.pairingId.toByteArray()
        val signature = Crypto.ed25519Sign(id.seed, deviceX + idBytes + id.publicKey)
        val sub = Tlv8.encode(
            Tlv8.IDENTIFIER to idBytes,
            Tlv8.SIGNATURE to signature,
            Tlv8.PUBLIC_KEY to id.publicKey,
        )
        val encKey = Crypto.hkdfSha512(k, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
        val sealed = Crypto.chachaSeal(encKey, Crypto.labelNonce("PS-Msg05"), sub)
        val m6 = post(
            rtsp, "/pair-setup",
            Tlv8.encode(Tlv8.STATE to Tlv8.byte(5), Tlv8.ENCRYPTED_DATA to sealed),
            "3",
        )
        val enc = m6[Tlv8.ENCRYPTED_DATA] ?: throw PairingException("No data in pair-setup M6")
        val acc = Tlv8.decode(Crypto.chachaOpen(encKey, Crypto.labelNonce("PS-Msg06"), enc))
        val accId = acc[Tlv8.IDENTIFIER]?.let { String(it) } ?: throw PairingException("No accessory id")
        val accPk = acc[Tlv8.PUBLIC_KEY] ?: throw PairingException("No accessory key")
        return PairedAccessory(accId, accPk)
    }

    /** HomeKit pair-verify (M1..M4). Returns the 32-byte shared secret. */
    fun verify(rtsp: RtspConnection, id: PairingIdentity, accessory: PairedAccessory?): ByteArray {
        val eph = Crypto.x25519KeyPair()
        val m2 = post(
            rtsp, "/pair-verify",
            Tlv8.encode(Tlv8.STATE to Tlv8.byte(1), Tlv8.PUBLIC_KEY to eph.public),
            "3",
        )
        val accEph = m2[Tlv8.PUBLIC_KEY] ?: throw PairingException("No public key in pair-verify M2")
        val shared = Crypto.x25519(eph.secret, accEph)
        val key = Crypto.hkdfSha512(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
        val sub = Tlv8.decode(
            Crypto.chachaOpen(key, Crypto.labelNonce("PV-Msg02"), m2[Tlv8.ENCRYPTED_DATA] ?: ByteArray(0)),
        )
        if (accessory != null) {
            val accId = sub[Tlv8.IDENTIFIER] ?: ByteArray(0)
            val sig = sub[Tlv8.SIGNATURE] ?: ByteArray(0)
            if (!Crypto.ed25519Verify(accessory.publicKey, accEph + accId + eph.public, sig)) {
                throw PairingException("Receiver identity changed - pair again", needsPin = true)
            }
        }
        val idBytes = id.pairingId.toByteArray()
        val signature = Crypto.ed25519Sign(id.seed, eph.public + idBytes + accEph)
        val sealed = Crypto.chachaSeal(
            key, Crypto.labelNonce("PV-Msg03"),
            Tlv8.encode(Tlv8.IDENTIFIER to idBytes, Tlv8.SIGNATURE to signature),
        )
        post(rtsp, "/pair-verify", Tlv8.encode(Tlv8.STATE to Tlv8.byte(3), Tlv8.ENCRYPTED_DATA to sealed), "3")
        return shared
    }
}
