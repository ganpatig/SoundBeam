package dev.soundbeam.live

/**
 * Small adaptive jitter buffer for the live protocol.
 *
 * - Waits until [targetMs] of audio is queued before playing ("priming").
 * - Lost packets (small sequence gaps) are concealed with silence so timing is preserved.
 * - If the queue grows past target + 30 ms (sender clock slightly faster, or a burst), the oldest
 *   packet is dropped, so delay can never creep up.
 * - On an underrun the target grows by 10 ms (up to [maxTargetMs]); after 30 s without problems
 *   it shrinks again by 5 ms towards [minTargetMs]. Good Wi-Fi therefore settles near the minimum.
 */
class JitterBuffer(
    private val sampleRate: Int,
    private val channels: Int,
    private val minTargetMs: Int = 40,
    private val maxTargetMs: Int = 160,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val queue = ArrayDeque<ShortArray>()
    private var queuedFrames = 0
    private var expectedSeq = -1L
    private var primed = false
    private var lastProblemAt = now()

    var targetMs = minTargetMs
        private set
    var underruns = 0L
        private set
    var dropped = 0L
        private set

    private fun ms(frames: Int) = frames * 1000 / sampleRate
    private fun frames(ms: Int) = ms * sampleRate / 1000

    @Synchronized
    fun push(seq: Long, pcm: ShortArray) {
        if (expectedSeq >= 0) {
            val gap = seq - expectedSeq
            when {
                gap < 0 && gap > -64 -> return            // late or duplicate packet
                gap in 1..8 -> repeat(gap.toInt()) { add(ShortArray(pcm.size)) } // conceal loss
                gap != 0L -> clear()                       // stream restarted
            }
        }
        expectedSeq = seq + 1
        add(pcm)
        if (queuedFrames > frames(targetMs + 30) && queue.size > 1) {
            queuedFrames -= queue.removeFirst().size / channels
            dropped++
        }
        adapt()
    }

    /** Next chunk to play, or null while priming / on underrun. */
    @Synchronized
    fun pull(): ShortArray? {
        if (!primed) {
            if (queuedFrames >= frames(targetMs)) primed = true else return null
        }
        val c = queue.removeFirstOrNull()
        if (c == null) {
            primed = false
            underruns++
            targetMs = minOf(maxTargetMs, targetMs + 10)
            lastProblemAt = now()
            return null
        }
        queuedFrames -= c.size / channels
        return c
    }

    @Synchronized
    fun bufferedMs(): Int = ms(queuedFrames)

    @Synchronized
    fun clear() {
        queue.clear()
        queuedFrames = 0
        primed = false
        expectedSeq = -1
    }

    private fun add(pcm: ShortArray) {
        queue.addLast(pcm)
        queuedFrames += pcm.size / channels
    }

    private fun adapt() {
        val t = now()
        if (targetMs > minTargetMs && t - lastProblemAt > 30_000) {
            targetMs = maxOf(minTargetMs, targetMs - 5)
            lastProblemAt = t
        }
    }
}
