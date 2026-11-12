package dev.soundbeam.airplay

import java.math.BigInteger

/**
 * SRP-6a client as used by HomeKit / AirPlay 2 pair-setup:
 * 3072-bit group from RFC 5054, SHA-512, username "Pair-Setup".
 */
class SrpClient(
    private val username: String,
    private val password: String,
    privateKey: ByteArray = Crypto.randomBytes(32), // injectable for tests
) {

    private val a = BigInteger(1, privateKey)
    val publicA: BigInteger = G.modPow(a, N)
    lateinit var sessionKey: ByteArray
        private set
    private lateinit var m1: ByteArray

    /** Processes the server salt and public value B, returning the client proof M1. */
    fun processChallenge(salt: ByteArray, bBytes: ByteArray): ByteArray {
        val b = BigInteger(1, bBytes)
        require(b.mod(N) != BigInteger.ZERO) { "Invalid SRP server value" }
        val k = BigInteger(1, Crypto.sha512(N.toPadded(N_LEN), G.toPadded(N_LEN)))
        val u = BigInteger(1, Crypto.sha512(publicA.toPadded(N_LEN), b.toPadded(N_LEN)))
        require(u != BigInteger.ZERO) { "Invalid SRP scrambler" }
        val inner = Crypto.sha512("$username:$password".toByteArray())
        val x = BigInteger(1, Crypto.sha512(BigInteger(1, salt).toMinBytes(), inner))
        val v = G.modPow(x, N)
        val base = b.subtract(k.multiply(v)).mod(N)
        val s = base.modPow(a.add(u.multiply(x)), N)
        sessionKey = Crypto.sha512(s.toMinBytes())

        val hN = Crypto.sha512(N.toMinBytes())
        val hG = Crypto.sha512(G.toMinBytes())
        val hXor = ByteArray(hN.size) { (hN[it].toInt() xor hG[it].toInt()).toByte() }
        val hI = Crypto.sha512(username.toByteArray())
        m1 = Crypto.sha512(
            hXor, hI, BigInteger(1, salt).toMinBytes(), publicA.toMinBytes(), b.toMinBytes(), sessionKey,
        )
        return m1
    }

    fun verifyServer(m2: ByteArray): Boolean {
        val expected = Crypto.sha512(publicA.toMinBytes(), m1, sessionKey)
        return expected.contentEquals(m2)
    }

    companion object {
        private const val N_LEN = 384
        private val N = BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B" +
                "139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485" +
                "B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1F" +
                "E649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F83655D23" +
                "DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32" +
                "905E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF69558" +
                "17183995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A85521" +
                "ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7ABF5AE8CDB0933D7" +
                "1E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B1817" +
                "7B200CBBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82" +
                "D120A93AD2CAFFFFFFFFFFFFFFFF",
            16,
        )
        private val G = BigInteger.valueOf(5)
    }
}
