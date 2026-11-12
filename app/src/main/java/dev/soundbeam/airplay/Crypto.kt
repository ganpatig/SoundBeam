package dev.soundbeam.airplay

import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.math.ec.rfc7748.X25519
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

object Crypto {
    val random = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    fun sha512(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun hkdfSha512(ikm: ByteArray, salt: String, info: String, len: Int = 32): ByteArray {
        val gen = HKDFBytesGenerator(SHA512Digest())
        gen.init(HKDFParameters(ikm, salt.toByteArray(), info.toByteArray()))
        return ByteArray(len).also { gen.generateBytes(it, 0, len) }
    }

    /** 12-byte nonce: 4 zero bytes followed by an 8-byte label such as "PS-Msg05". */
    fun labelNonce(label: String): ByteArray {
        val n = ByteArray(12)
        val l = label.toByteArray()
        System.arraycopy(l, 0, n, 12 - l.size, l.size)
        return n
    }

    /** 12-byte nonce with a little-endian 64-bit counter in bytes 4..11. */
    fun counterNonce(counter: Long): ByteArray {
        val n = ByteArray(12)
        for (i in 0 until 8) n[4 + i] = (counter ushr (8 * i)).toByte()
        return n
    }

    /** Returns ciphertext with the 16-byte tag appended. */
    fun chachaSeal(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(c.getOutputSize(plain.size))
        val n = c.processBytes(plain, 0, plain.size, out, 0)
        c.doFinal(out, n)
        return out
    }

    /** Input is ciphertext with the 16-byte tag appended. Throws on authentication failure. */
    fun chachaOpen(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray? = null): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(false, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(c.getOutputSize(sealed.size))
        val n = c.processBytes(sealed, 0, sealed.size, out, 0)
        c.doFinal(out, n)
        return out
    }

    // --- Ed25519 (long-term identity) ---------------------------------------------------------

    fun ed25519Public(seed: ByteArray): ByteArray = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    fun ed25519Sign(seed: ByteArray, msg: ByteArray): ByteArray {
        val s = Ed25519Signer()
        s.init(true, Ed25519PrivateKeyParameters(seed, 0))
        s.update(msg, 0, msg.size)
        return s.generateSignature()
    }

    fun ed25519Verify(pub: ByteArray, msg: ByteArray, sig: ByteArray): Boolean = try {
        val s = Ed25519Signer()
        s.init(false, Ed25519PublicKeyParameters(pub, 0))
        s.update(msg, 0, msg.size)
        s.verifySignature(sig)
    } catch (e: Exception) {
        false
    }

    // --- X25519 (ephemeral key agreement) -----------------------------------------------------

    class X25519KeyPair(val secret: ByteArray, val public: ByteArray)

    fun x25519KeyPair(): X25519KeyPair {
        val sk = ByteArray(X25519.SCALAR_SIZE)
        X25519.generatePrivateKey(random, sk)
        val pk = ByteArray(X25519.POINT_SIZE)
        X25519.generatePublicKey(sk, 0, pk, 0)
        return X25519KeyPair(sk, pk)
    }

    fun x25519(secret: ByteArray, peerPublic: ByteArray): ByteArray {
        val out = ByteArray(X25519.POINT_SIZE)
        X25519.scalarMult(secret, 0, peerPublic, 0, out, 0)
        return out
    }
}

/** Big-endian magnitude without a sign byte. */
fun BigInteger.toMinBytes(): ByteArray {
    val b = toByteArray()
    return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
}

fun BigInteger.toPadded(len: Int): ByteArray {
    val b = toMinBytes()
    if (b.size >= len) return b
    return ByteArray(len - b.size) + b
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
