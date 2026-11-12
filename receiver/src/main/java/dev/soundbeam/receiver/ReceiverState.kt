package dev.soundbeam.receiver

import android.os.Handler
import android.os.Looper

/** Observable receiver state shared between the service and the on-screen UI. */
object ReceiverState {
    data class Snapshot(
        val name: String = "",
        val address: String = "",
        val sender: String? = null,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val artworkUrl: String? = null,
        val playing: Boolean = false,
        val error: String? = null,
    )

    @Volatile var current = Snapshot()
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<(Snapshot) -> Unit>()

    fun update(f: (Snapshot) -> Snapshot) {
        synchronized(this) { current = f(current) }
        val s = current
        main.post { listeners.toList().forEach { it(s) } }
    }

    fun listen(l: (Snapshot) -> Unit) {
        main.post { listeners.add(l); l(current) }
    }

    fun unlisten(l: (Snapshot) -> Unit) {
        main.post { listeners.remove(l) }
    }
}
