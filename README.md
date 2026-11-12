# SoundBeam

Free, open-source Android app that streams **whatever your phone is playing** (Spotify, YouTube Music, a browser, a game…) to speakers and receivers on your Wi-Fi. No account, no ads, no subscription, no root.

> **Status: early alpha (v0.1).** The pipeline below works by design but has had limited device testing. Issues and PRs welcome.

## What it does

| Feature | Status |
|---|---|
| System-audio capture of other apps (Android 10+ `AudioPlaybackCapture`) | ✅ |
| **DLNA / UPnP** receivers (smart TVs, AV receivers, Denon/Yamaha/Onkyo/Pioneer, many streamers) | ✅ discovery via SSDP, playback via AVTransport |
| Multiple receivers at once, per-receiver volume | ✅ (no cross-device sync, see below) |
| **HTTP stream** URL for VLC and similar players | ✅ `http://<phone-ip>:8765/stream.wav` |
| **Save to file** (WAV) | ✅ |
| Built-in **10-band equalizer** with presets, applied before every output | ✅ |
| AirPlay / AirPlay 2 (HomePod, Apple TV, Roku) | 🔜 planned |
| Google Cast / Chromecast | 🔜 planned |
| Sonos, HEOS, Fire TV, AriaCast, Samsung Multiroom | 🔜 planned |
| Cover art / metadata | 🔜 planned |

Not every app allows its audio to be captured: apps that opt out of Android's playback capture (or play DRM-protected audio) come through silent. That is an Android restriction and applies to any app using this API.

## How it works

```
 other apps ──► AudioPlaybackCapture ──► 10-band EQ ──┬─► HttpStreamServer ──► DLNA renderers (via SetAVTransportURI + Play)
                (CaptureService)                       │                  └─► VLC / any HTTP client
                                                       └─► WavFileSink
```

* `CaptureService`: foreground service, owns the `MediaProjection` + `AudioRecord`, 44.1 kHz stereo 16-bit.
* `audio/Equalizer`: RBJ peaking biquads (31 Hz – 16 kHz), software, applies to every output.
* `net/Dlna.kt`: SSDP multicast discovery, device-description parsing, minimal UPnP SOAP client.
* `net/HttpStreamServer`: endless-WAV HTTP server with a bounded per-client queue (slow clients drop audio rather than stalling the capture).

DLNA renderers buffer several seconds, so expect a few seconds of latency. Mixed receivers will not be sample-synchronised.

## Build

Requires JDK 17 and the Android SDK (API 35).

```bash
gradle wrapper --gradle-version 8.10.2   # once, or just open the folder in Android Studio
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. GitHub Actions builds it on every push (see `.github/workflows/build.yml`).

## Using it

1. Open SoundBeam, tap **Scan**, tick one or more receivers (and/or enable **HTTP stream** / **Save to file**).
2. Tap **Start streaming** and accept the microphone and screen-capture prompts. (Android shows these for the audio-capture API; SoundBeam records no microphone and no screen.)
3. Play audio in any app.

## Clean-room statement

SoundBeam is an independent implementation written from public documentation: the UPnP Device Architecture and AV specs, the Android `AudioPlaybackCapture` docs, and the feature list published on the AirMusic website. It contains no code, assets, or text from AirMusic or any decompiled app, and is not affiliated with it. "AirPlay", "Sonos", "Chromecast", "DLNA" etc. are trademarks of their owners and are used only to describe compatibility.

## Contributing

Protocol back-ends are small, self-contained "sinks"/controllers, which makes new ones a good first contribution (AirPlay RAOP, Cast v2, Sonos UPnP extensions, AriaCast). Open an issue first so work isn't duplicated.

## License

MIT, see [LICENSE](LICENSE).
