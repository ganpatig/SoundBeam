package dev.soundbeam.receiver

import android.Manifest
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.net.URL
import kotlin.concurrent.thread

/** Full-screen "now playing" display, designed to be readable from the sofa on a TV. */
class ReceiverActivity : Activity() {

    private lateinit var name: TextView
    private lateinit var status: TextView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var art: ImageView
    private var shownArtUrl: String? = null

    private val listener: (ReceiverState.Snapshot) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0E2233"))
            setPadding((48 * dp).toInt(), (32 * dp).toInt(), (48 * dp).toInt(), (32 * dp).toInt())
        }
        art = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams((220 * dp).toInt(), (220 * dp).toInt()).apply { marginEnd = (40 * dp).toInt() }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.ic_launcher_foreground)
            setBackgroundColor(Color.parseColor("#1B5E8C"))
        }
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun tv(size: Float, bold: Boolean = false, color: String = "#FFFFFF") = TextView(this).apply {
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
        }
        name = tv(16f, color = "#9FC3DD")
        title = tv(34f, bold = true)
        artist = tv(22f, color = "#D6E6F2")
        status = tv(15f, color = "#9FC3DD").apply { setPadding(0, (24 * dp).toInt(), 0, 0) }
        listOf(name, title, artist, status).forEach { text.addView(it) }
        root.addView(art)
        root.addView(text)
        setContentView(root)

        ReceiverService.start(this)
    }

    override fun onStart() {
        super.onStart()
        ReceiverState.listen(listener)
    }

    override fun onStop() {
        ReceiverState.unlisten(listener)
        super.onStop()
    }

    private fun render(s: ReceiverState.Snapshot) {
        name.text = s.name
        if (s.playing) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            title.text = s.title ?: "Playing"
            artist.text = listOfNotNull(s.artist, s.album).joinToString(" · ")
            status.text = "Streaming from ${s.sender ?: "your phone"}"
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            title.text = "Ready"
            artist.text = "Open SoundBeam on your phone and pick \"${s.name}\""
            status.text = "Listening on ${s.address}" + (s.error?.let { "\n$it" } ?: "")
        }
        artist.visibility = if (artist.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        loadArt(if (s.playing) s.artworkUrl else null)
    }

    private fun loadArt(url: String?) {
        if (url == shownArtUrl) return
        shownArtUrl = url
        if (url == null || !url.startsWith("http")) {
            art.setImageResource(R.drawable.ic_launcher_foreground)
            return
        }
        thread(isDaemon = true) {
            val bmp: Bitmap? = runCatching {
                URL(url).openStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            runOnUiThread { if (shownArtUrl == url && bmp != null) art.setImageBitmap(bmp) }
        }
    }
}
