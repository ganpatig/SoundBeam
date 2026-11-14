package dev.soundbeam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.soundbeam.core.Device
import dev.soundbeam.core.NowPlaying
import dev.soundbeam.core.OutputState
import dev.soundbeam.core.Protocol
import dev.soundbeam.ui.AppActions
import dev.soundbeam.ui.SoundBeamApp
import dev.soundbeam.ui.SoundBeamTheme
import dev.soundbeam.ui.Tab
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Renders the real UI with demo speakers into docs/screenshots (used in the README and releases).
 * Run: ./gradlew :app:testDebugUnitTest --tests dev.soundbeam.ScreenshotTest
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi")
class ScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
    private val noActions = AppActions({}, {}, {}, {})

    private val livingRoom = Device("airplay:1", "Living Room", Protocol.AIRPLAY2, "10.0.0.11", 7000, model = "Apple HomePod")
    private val kitchenAp = Device("airplay:2", "Kitchen", Protocol.AIRPLAY2, "10.0.0.12", 7000, model = "Sonos Move 2")
    private val kitchenSonos = Device("upnp:2", "10.0.0.12 - Sonos Move 2", Protocol.SONOS, "10.0.0.12", 0, model = "Sonos Move 2")
    private val tv = Device("cast:3", "Upstairs TV", Protocol.CAST, "10.0.0.13", 8009, model = "Chromecast with Google TV")
    private val denon = Device("heos:4", "Office Receiver", Protocol.HEOS, "10.0.0.14", 1255, model = "Denon AVR-X1700H")
    private val fire = Device("aria:5", "Workshop Fire TV", Protocol.RECEIVER, "10.0.0.15", 12889, model = "SoundBeam Receiver")

    @Before
    fun setUp() {
        Engine.init(ApplicationProvider.getApplicationContext())
        Engine.phoneName = "Priya's Pixel 9"
        Engine.devices.value = listOf(livingRoom, kitchenAp, kitchenSonos, tv, denon, fire)
        Engine.selected.value = emptySet()
        Engine.running.value = false
        Engine.outputStates.value = emptyMap()
        Engine.notificationAccess.value = true
        Engine.welcomeSeen.value = true
        Engine.captureExplained.value = true
        Engine.via.value = emptyMap()
        Engine.volumes.value = mapOf(livingRoom.id to 65, kitchenAp.id to 45)
        Engine.nowPlaying.value = null
        Engine.setEq(List(10) { 0f })
    }

    private fun shoot(name: String, tab: Tab = Tab.SPEAKERS, dark: Boolean = false, after: () -> Unit = {}) {
        System.getProperty("screenshots.qualifiers")?.takeIf { it.isNotBlank() }?.let { RuntimeEnvironment.setQualifiers(it) }
        compose.setContent { SoundBeamTheme(dark = dark) { SoundBeamApp(noActions, initialTab = tab) } }
        compose.waitForIdle()
        after()
        compose.waitForIdle()
        @OptIn(ExperimentalRoborazziApi::class)
        captureScreenRoboImage(File(outDir, "$name.png").path)
    }

    @Test
    fun s1_welcome() {
        Engine.welcomeSeen.value = false
        Engine.selected.value = setOf(livingRoom.id)
        shoot("1-welcome")
    }

    @Test
    fun s2_playing() {
        Engine.selected.value = setOf(livingRoom.id, kitchenAp.id)
        Engine.running.value = true
        Engine.outputStates.value = mapOf(livingRoom.id to OutputState.Playing, kitchenAp.id to OutputState.Playing)
        Engine.nowPlaying.value = NowPlaying(
            "Golden Hour", "Kacey Musgraves", "Golden Hour", 210_000, 64_000,
            SystemClock.elapsedRealtime(), true, demoArtwork(), "com.spotify.music",
        )
        shoot("2-playing")
    }

    @Test
    fun s3_speakerSettings() {
        Engine.selected.value = setOf(kitchenAp.id)
        shoot("3-speaker-settings") {
            compose.onNodeWithContentDescription("Settings for Kitchen").performClick()
        }
    }

    @Test
    fun s4_sound() {
        Engine.setEq(dev.soundbeam.audio.Equalizer.PRESETS.getValue("Bass boost"))
        shoot("4-sound", Tab.SOUND)
    }

    @Test
    fun s5_more() {
        shoot("5-more", Tab.MORE)
    }

    @Test
    fun s6_dark() {
        Engine.selected.value = setOf(livingRoom.id, kitchenAp.id)
        Engine.running.value = true
        Engine.outputStates.value = mapOf(livingRoom.id to OutputState.Playing, kitchenAp.id to OutputState.Connecting)
        Engine.nowPlaying.value = NowPlaying(
            "Midnight City", "M83", "Hurry Up, We're Dreaming", 243_000, 30_000,
            SystemClock.elapsedRealtime(), true, demoArtwork(0xFF3A1C71.toInt(), 0xFFD76D77.toInt()), null,
        )
        shoot("6-dark", dark = true)
    }

    private fun demoArtwork(a: Int = 0xFFF7971E.toInt(), b: Int = 0xFFE94E77.toInt()): ByteArray {
        val bmp = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint().apply { shader = LinearGradient(0f, 0f, 300f, 300f, a, b, Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, 300f, 300f, p)
        p.shader = null
        p.color = 0x66FFFFFF
        c.drawCircle(150f, 150f, 80f, p)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    @Test
    fun s7_togetherShare() {
        dev.soundbeam.live.DualAudio.override = dev.soundbeam.live.DualAudio.Kind.SAMSUNG
        dev.soundbeam.live.DualAudio.overrideHeadphones = listOf("Galaxy Buds3 Pro", "Sony WH-1000XM5")
        val friend = Device("live:10.0.0.42", "Priya's Pixel 9", Protocol.LIVE, "10.0.0.42", 12890, model = "Listening")
        Engine.devices.value = Engine.devices.value + friend
        Engine.selected.value = setOf(friend.id)
        shoot("7-together", Tab.TOGETHER)
    }

    @Test
    fun s8_togetherListen() {
        dev.soundbeam.live.DualAudio.override = dev.soundbeam.live.DualAudio.Kind.NONE
        dev.soundbeam.live.DualAudio.overrideHeadphones = emptyList()
        dev.soundbeam.live.LiveReceiverState.listening.value = true
        dev.soundbeam.live.LiveReceiverState.sender.value = "Ganpati's Galaxy S24"
        dev.soundbeam.live.LiveReceiverState.delayMs.value = 58
        shoot("8-together-listening", Tab.TOGETHER) {
            compose.onNodeWithText("I'm listening").performClick()
        }
        dev.soundbeam.live.LiveReceiverState.listening.value = false
        dev.soundbeam.live.LiveReceiverState.sender.value = null
    }

    @Test
    fun s9_autoSync() {
        Engine.selected.value = setOf(livingRoom.id, kitchenAp.id)
        Engine.running.value = true
        dev.soundbeam.sync.AutoSync.state.value = dev.soundbeam.sync.SyncState.Done(
            listOf("Living Room: held back 2.10 s", "Upstairs TV: held back 0.80 s", "Kitchen: no delay (slowest)"),
            emptyList(),
        )
        shoot("9-auto-sync")
        dev.soundbeam.sync.AutoSync.state.value = dev.soundbeam.sync.SyncState.Idle
    }
}
