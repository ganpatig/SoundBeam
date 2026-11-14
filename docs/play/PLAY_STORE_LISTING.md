# Google Play listing – copy & paste sheet

Everything below is ready to paste into [Play Console](https://play.google.com/console). Files to upload are in this folder (`docs/play/`); the app bundle is built locally (see *Upload*).

---

## Store listing

**App name** (max 30): `SoundBeam: Audio to Speakers`

**Short description** (max 80):
`Play any app's sound on AirPlay, Chromecast, Sonos & more. Watch together too.`

**Full description** (max 4000):

```
Play the sound of ANY app on your speakers, TVs and headphones – music, podcasts, YouTube, movies, games.

SoundBeam sends your phone's sound to the speakers you already own: AirPlay and AirPlay 2 speakers (HomePod, Apple TV, Roku, AirPlay receivers), Chromecast and Google Nest, Sonos, Denon/Marantz HEOS, smart TVs and receivers with DLNA, Music Assistant (AriaCast) and Fire TV / Android TV.

SIMPLE
• Tap the speakers you want, press Play, then play anything in any app.
• Every speaker shows up once with its own on/off switch and volume.
• Clear status on each speaker and plain-language help for every setting.

MULTI-ROOM
• Play to several speakers at the same time – kitchen, living room and bedroom together.
• AirPlay speakers stay in sync with each other automatically.
• Mixing systems? A per-speaker delay slider lines them up.
• Add or remove rooms while the music keeps playing.

WATCH TOGETHER
• Two people, one movie, each with their own headphones – perfect for flights and trips.
• Two headphones on one phone: SoundBeam opens Samsung Dual Audio or Android Audio Sharing for you in one tap.
• Or use a friend's phone: SoundBeam streams the movie's sound to it with a tiny delay, over Wi-Fi or your phone's hotspot.

GREAT SOUND
• 10-band equalizer with one-tap presets (Bass boost, Vocal, Loudness…).
• Lossless audio to AirPlay and DLNA.
• Song titles and cover art on speakers and TVs that can show them.

EXTRAS
• Listen in a browser or VLC on any computer on your Wi-Fi.
• Record what you stream to a high-quality WAV file.
• Adjustable AirPlay buffer: quicker response or smoother playback.

PRIVATE AND FREE
• No account, no ads, no subscription, no tracking.
• Your audio only goes to the devices you choose on your own network.
• Open source: github.com/ganpatig/SoundBeam

GOOD TO KNOW
• Requires Android 10 or newer.
• Android asks for "screen recording" permission – that is how Android lets apps capture sound. SoundBeam only uses the sound, never your screen.
• Some apps block sound capture to protect content; those stay silent.
• Network speakers add a short delay, so for movies use Watch Together.

AirPlay, HomePod and Apple TV are trademarks of Apple Inc. Chromecast and Google Nest are trademarks of Google LLC. Sonos is a trademark of Sonos, Inc. HEOS is a trademark of D&M Holdings. Fire TV is a trademark of Amazon. SoundBeam is an independent app and is not affiliated with or endorsed by these companies.
```

**Graphics** (in this folder)
| Field | File |
|---|---|
| App icon (512×512) | `icon-512.png` |
| Feature graphic (1024×500) | `feature-graphic-1024x500.png` |
| Phone screenshots (2–8, upload in this order) | `phone-1-…png` … `phone-8-…png` |

**Category:** Music & Audio · **Tags:** Music, Audio, Casting
**Contact email:** your own email (Play shows it publicly – use one you're happy to share)
**Website:** `https://github.com/ganpatig/SoundBeam`
**Privacy policy URL:** `https://github.com/ganpatig/SoundBeam/blob/main/docs/PRIVACY.md`

---

## App content (Policy → App content)

**Privacy policy:** URL above.

**Ads:** No, my app does not contain ads.

**App access:** All functionality is available without special access (no login).

**Content rating questionnaire:** Category *All other app types* →
- Violence, sexuality, language, controlled substances, gambling: **No** to all
- Does the app allow users to interact or exchange content? **No** (audio only goes to the user's own devices on their local network)
- Does the app share the user's location? **No** · Digital purchases? **No**
→ Expected rating: Everyone / PEGI 3

**Target audience:** 13–15, 16–17 and 18+ (do **not** tick under-13 – that triggers the Families program).

**News app:** No · **Government app:** No · **Financial features:** none · **Health:** none

**Data safety** – recommended answers:
- *Does your app collect or share any of the required user data types?* **No.**
- Reason (for your own notes): SoundBeam has no servers or SDKs; nothing is sent to the developer or third parties. Audio and track info go only to devices the user picks on their own local network, at their request.
- *Is all user data encrypted in transit?* Answered only if you declared collection – skip.
- *Do you provide a way to request deletion?* Not applicable (no data collected; uninstalling removes everything).

**Foreground service permissions** (Play asks because the app targets Android 14+):

1. `FOREGROUND_SERVICE_MEDIA_PROJECTION` – *Type: Media projection*
   > SoundBeam's core feature is playing the sound of other apps on the user's speakers. Android only allows this through AudioPlaybackCapture, which requires a MediaProjection. The service starts only when the user presses Play, shows a persistent notification with a Stop button, captures audio only (never the screen image) and stops when the user presses Stop. The audio is sent only to speakers the user selected on their local network.

2. `FOREGROUND_SERVICE_MEDIA_PLAYBACK` – *Type: Media playback*
   > In "Watch together → I'm listening" mode, SoundBeam plays audio received from a friend's phone on this phone's headphones. It must keep playing with the screen off during a movie. Started and stopped by the user; shows a notification with a Stop button.

   Play may ask for a short video of each feature in use: screen-record your phone pressing Play/Start and the notification appearing, upload it as an unlisted YouTube video and paste the link.

**Notification access** (not a restricted permission, but explain it if asked):
> Optional. If the user turns it on, SoundBeam reads the title, artist and artwork of the playing app's media session to show them on speakers that support it. Nothing else from notifications is read or stored.

---

## Release

**Play App Signing:** Play Console will ask how to sign the app.
- *Recommended:* **Use your own key** ("Export and upload a key from Java keystore") so the Play version and the GitHub APKs are the same app and can update each other. Play gives you `pepk.jar` and an encryption key; the keystore is `C:\Dev\SoundBeam-release.jks`, alias `soundbeam` (password in `C:\Dev\SoundBeam-release.password.txt`).
- *Simplest:* let Google generate the key. Then people who installed a GitHub APK must uninstall it before installing from Play.

**Upload:** `C:\Dev\SoundBeam-play\SoundBeam-0.4.1.aab` (targets Android 16 / API 36, signed with the upload key above).

**Testing requirement** (new personal developer accounts): publish to a **Closed testing** track first and keep **at least 12 testers opted in for 14 days in a row**, then apply for production access in Play Console.

**Release notes (en-US):**
```
First Play release: play any app's sound on AirPlay, Chromecast, Sonos, HEOS, DLNA speakers and Fire TV; multi-room; Watch Together with two headphones or a friend's phone; 10-band equalizer.
```
