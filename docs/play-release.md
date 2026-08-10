# Shipping Flick on Google Play

Two apps, two listings, one developer account. This is the runbook: what is already
done in the repo, what only you can do in Play Console, and the exact text to paste
into each declaration.

Researched against Google's own policy pages on 7 August 2026. Where a rule has a
date attached, the date is given — Play's requirements move, and a stale checklist is
worse than none.

---

## 1. The shape of the release

`:sender` and `:receiver` have different `applicationId`s, different permissions and
different UIs. They ship as **two separate Play listings**:

| | Phone | TV |
| --- | --- | --- |
| Package | `com.flick.sender` | `com.flick.receiver` |
| Store title | Flick: Cast Videos to TV | Flick TV |
| Form factor | Phone / tablet | Android TV (opt-in required) |
| Reviewed against | Core app quality | Core **and** Android TV app quality |

Google [recommends](https://developer.android.com/training/tv/publishing/distribute)
sharing one package name across form factors and using the dedicated TV track. That
would collapse this to one listing, one review and one closed test — but it means
merging two apps into one binary, which is weeks of work and a real regression risk
against a system that is finished and proven on hardware. Two listings costs
paperwork, not calendar time: the two closed tests run **concurrently** with the same
testers.

---

## 2. The critical path

The schedule is set by one thing and nothing else:

> **12 testers, opted in continuously for 14 days**, before you may apply for
> production access. Opting out and back in resets that tester's clock to zero.

This applies to personal developer accounts created after 13 November 2023.
Organisation accounts are exempt. Google's own page describes the rule in terms of
"your app" and does not state plainly whether production access, once granted, covers
the account or only the tested app; third-party guides confidently claim both. **Plan
for per-app** — run both closed tests at the same time, with the same 12 people, and
the ambiguity costs you nothing.

Rough calendar from the day you first upload:

| Day | What happens |
| --- | --- |
| 0 | Both AABs uploaded to closed testing; testers opt in |
| 0–2 | First review of each closed-testing build |
| 14 | Tester requirement satisfied — apply for production access |
| 14–21 | Production access review (Google says up to 7 days) |
| ~21 | Production rollout can begin |

Three weeks is the floor. Everything else in this document can be done inside day 0.

---

## 3. Already done in this repo

- **Upload keystore** generated at `~/.flick-keys/flick-upload.jks`, RSA 2048, valid
  to December 2053. It is outside the repository on purpose.
- **Release signing wired** in both modules, reading `flick.upload.*` from gitignored
  `local.properties` or `FLICK_UPLOAD_*` environment variables. Verified three ways:
  fully configured → `upload`; nothing configured → falls back to `debug` so a clone
  still builds; partly configured → build fails naming the missing field.
- **Version set to 1.0.0**, `versionCode` 4, both modules.
- **Both AABs built and signed** with the upload key (fingerprint
  `AF:34:FA:…:D0:26` on both).
- **Store graphics** rendered from the apps' own vector art in `docs/store/`.
- **Privacy policy** in `docs/privacy/index.html`, ready for GitHub Pages.

### Back up the keystore before anything else

If you lose `~/.flick-keys/flick-upload.jks` you can ask Google to reset the upload
key, but every local `bundleRelease` breaks until they do. Copy the file and the four
`flick.upload.*` lines from `local.properties` into a password manager now. Neither
belongs in this repository — it is public.

Note that the release builds are now signed with a **different key than before**. The
copies currently sideloaded on your phone and TV were signed with the debug key, so
`adb install -r` will fail with a signature mismatch. Uninstall first when you next
install a release build; TV pairings will need redoing.

---

## 4. Account setup (once)

1. **Play Console account** — $25, one-time. Personal or organisation; organisation
   needs a D-U-N-S number but is exempt from the 12-tester rule.
2. **Identity verification** — government ID plus an address document.
3. **2-Step Verification** on the Google account. Required to publish.
4. **Trader status** (EU / Digital Services Act). If you declare as a trader, your
   name, address and phone number are published on the listing. This is a privacy
   decision, not a technical one — decide it deliberately.
5. **Payments profile** — not needed. Flick is free with no in-app purchases.

Separately, Google's **Android Developer Verification** programme begins enforcing in
September 2026 in Brazil, Indonesia, Singapore and Thailand, expanding from 2027. It
governs apps installed on certified devices including sideloaded ones. Publishing
through Play satisfies it; it is worth knowing about if you also hand out APKs.

---

## 5. Store listings

### Flick (phone) — `com.flick.sender`

**Title** (30 max)

```
Flick: Cast Videos to TV
```

**Short description** (80 max)

```
Play your phone's own 4K videos on your TV. No transcoding, no screen mirroring.
```

**Full description**

```
Flick plays the videos already on your phone on your TV — at the quality they were
recorded in.

Most casting apps re-encode your video on the fly or mirror your screen. Both cost
you picture quality, and both stutter when the file is large. Flick does neither. It
hands your TV the original file, byte for byte, over your own Wi-Fi, and lets the
TV's own hardware decoder do the work it was built for.

That is why a 4K Dolby Vision file plays without a stall.

WHAT YOU GET
• Direct play — the original file, never transcoded, never downscaled
• 4K, HDR10 and Dolby Vision, decoded in hardware on the TV
• Your whole video library, browsable by folder, with search and thumbnails
• Picks up where you left off in every film
• Subtitles — the ones sitting next to your file, or searched online
• Playback controls, scrubbing with preview frames, and picture rotation
• Works from the lock screen and while your phone's screen is off

PRIVATE BY DESIGN
Your videos never leave your Wi-Fi. There is no Flick server, no account to make, no
analytics, and no advertising. Nothing is uploaded, ever.

YOU ALSO NEED FLICK TV
Flick casts to the Flick TV app, a free companion for Android TV and Google TV.
Install it on your TV, scan the pairing code once, and you are done.

FREE, WITH NO CATCH
Every feature is free. There is nothing to unlock and nothing to subscribe to. If you
want to leave a tip, you can — it buys nothing, and that is the point.
```

**Category:** Video Players & Editors  **Tags:** video player, casting, media
**Contact:** support email (published by Play), plus
`https://github.com/AniketShahane/Flick`

### Flick TV — `com.flick.receiver`

**Title** (30 max)

```
Flick TV
```

**Short description** (80 max)

```
The TV half of Flick. Plays 4K HDR video straight from your phone, no transcoding.
```

**Full description**

Google requires an Android TV app's description to mention Android TV.

```
Flick TV is the Android TV companion to Flick, the phone app that plays your own
videos on your television.

Pair a phone once by scanning the code on screen. From then on, choosing a video on
your phone starts it here — playing the original file straight off the phone over
your Wi-Fi, decoded by your TV's own hardware.

No transcoding. No screen mirroring. No quality lost on the way.

• 4K, HDR10 and Dolby Vision, hardware decoded
• Reads the original file directly — nothing is re-encoded
• Subtitles, picture rotation and full transport control from the phone
• An optional live overlay showing throughput, buffer health and the decoder in use
• Built for the remote: every screen is designed for a D-pad

Flick TV makes no connection to the internet. It talks only to a phone you have
paired with it, on your own network.

Requires the free Flick app on an Android phone.
```

**Category:** Video Players & Editors

### Graphics — `docs/store/`

| File | Where it goes |
| --- | --- |
| `flick-phone-icon-512.png` | Phone listing app icon |
| `flick-tv-icon-512.png` | TV listing app icon |
| `flick-phone-feature-1024x500.png` | Phone listing feature graphic |
| `flick-tv-feature-1024x500.png` | TV listing feature graphic |
| `flick-tv-banner-1280x720.png` | **TV banner** — required for the TV listing |
| `flick-tv-screenshot-01-idle.png` | TV screenshot (at least 1 required) |
| `*-flat.png` | Plain-lockup alternates, if the atmospheric ones ever need replacing |
| `backdrops/*.jpg` | Source art, so a composite can be rebuilt without regenerating |

Icons and the TV banner render from the apps' own vector drawables, so listing and
device cannot drift. The two feature graphics put that same vector lockup over a
generated backdrop — the lockup is never generated, because models mangle letterforms
and the wordmark is the one thing that has to be exact.

The mark's speed bars sit at 0.85/1.0 opacity in the feature graphics rather than the
shipped 0.45/0.85. Over the light phone ground the shipped values read as pale amber;
over near-black they collapse to muddy brown. Only the store assets differ — no app
art was touched — but the same effect is visible in the on-device TV launcher banner,
which is worth fixing separately.

### Screenshots — `docs/store/frame-screenshots.py`

Put raw captures in `docs/store/raw/` under the names the script expects and run it;
finished tiles land in `docs/store/screenshots/`. Missing captures are skipped and
listed, so it can be run before the set is complete.

```sh
python3 docs/store/frame-screenshots.py
```

**Phone captures cannot be uploaded raw.** Play requires that "the maximum dimension
of your screenshot can't be more than twice as long as the minimum dimension", and a
modern tall phone fails that unaided — 1440×3120 is 2.167:1 and is rejected.
Compositing onto a 1080×1920 canvas is what makes the asset valid, not just prettier.
TV captures are already 1920×1080 and pass as they are.

The order is the pitch. Play shows roughly the first three in search results before
anyone taps through, so those three carry the whole proposition: what it is, what
makes it different, what you get.

| # | Screen | Caption |
| --- | --- | --- |
| 1 | Library grid, thumbnails loaded | Your videos on the big screen |
| 2 | Detail sheet, direct-play verdict, real 4K specs | Never transcoded. Never downscaled. |
| 3 | Now Playing, mid-film, transport visible | Your phone becomes the remote |
| 4 | Subtitles sheet with a matched subtitle attached | Subtitles that just work |
| 5 | Devices, paired TV and the pairing card | Pair once, in one scan |
| 6 | Settings | Nothing leaves your Wi-Fi |

All six are captured, plus a spare — `raw/metrics.png`, the Signal & quality sheet with
live buffer health and RSSI. Play allows eight; that one is the strongest candidate if
a seventh is ever wanted.

Shot 3 was going to be captioned "4K HDR and Dolby Vision". It is not, because a
caption sits above a specific frame and no file in the staged library is HDR. The app
supports Dolby Vision and the store description says so; a screenshot cannot claim it
while showing an SDR file.

### The library in the screenshots is staged, deliberately

The captures use `/sdcard/Movies/Films`, holding five Blender open movies — *Big Buck
Bunny*, *Sintel*, *Tears of Steel*, *Caminandes: Gran Dillama*, *Elephants Dream*. They
are CC-BY: free to redistribute and display with attribution, which a personal film
library is not. Credit the Blender Foundation somewhere in the listing or the policy
page if these ship.

That folder exists only for screenshots and can be deleted:

```sh
adb shell rm -rf /sdcard/Movies/Films
```

`adb push` leaves MediaStore rows with `duration` NULL, and the app filters those out,
so the folder will not appear until a full metadata scan runs:

```sh
adb shell "content call --uri content://media --method scan_file --arg '/sdcard/Movies/Films/<file>'"
```

### One value is redacted, on purpose

The Devices screen prints the paired TV's real LAN address. Both this repository and a
store listing are public, so `frame-screenshots.py` redraws it as `192.168.42.17`, the
fixture address this project already reserves for documentation. The box and the
replacement string are declared in `REDACTIONS` at the top of the script so the edit is
auditable. Nothing else in any capture is altered except the status bar, which is
repainted because One UI ignores SystemUI demo mode.

The TV idle screenshot shows the paired phone's model string. That is a model, not a
serial or an identifier, but it is worth a look before upload.

TV tiles ship full-bleed with no caption. Captions were tried and dropped: the
receiver puts controls along the bottom edge and titles along the top, so a caption
band collides with real UI on one screen or the other. The strings in the script's
`TV_SHOTS` remain as notes for what each screen must be showing, and as video copy.

| # | Screen | Status |
| --- | --- | --- |
| 1 | Paused, full transport overlay | captured |
| 2 | Idle screen, paired | captured |
| 3 | Playback, mid-film | captured |
| 4 | Playback, second frame | captured |
| 5 | Settings | captured |

The paused tile leads deliberately. It is the only TV shot that shows the receiver's own
interface — transport, scrub position, the `1080p SDR / MP3 · STEREO / H.264` chips,
live throughput, `NOW PLAYING · DIRECT FILE` — instead of a video frame that could have
come from any player. Summoned with `KEYCODE_MEDIA_PAUSE` followed by `KEYCODE_DPAD_UP`.

### A real finding from capturing these: AC-3 audio fails on a Bluetooth route

Casting *Big Buck Bunny* failed with "The TV couldn't start a decoder". It looked like a
4K H.264 limit and is not — see
[implementation.md](implementation.md#decoder_init-is-usually-not-the-decoder--it-is-ac-3-passthrough-on-a-bluetooth-route)
for the full investigation, six controlled casts and the verbatim platform logs.

Short version: the Streamer's media audio is routed to a Bluetooth speaker, which takes
PCM stereo only, but the platform still advertises AC-3 direct playback from the HDMI
EDID. media3 selects passthrough, AudioFlinger refuses the track, and the audio failure
is reported as a decoder failure. The 4K video had already reached first frame.

**Both halves are now fixed.** The blast radius was most film rips and broadcast
recordings — anything carrying AC-3 or E-AC-3 — for any user whose TV sends audio to a
Bluetooth speaker, which is a shipping-grade defect rather than an edge case. The
receiver now answers the first output refusal by rebuilding its audio sink so the
bitstream is decoded rather than passed through, keeping the film, position and subtitle;
passthrough stays on for HDMI routes where it is correct.

The first attempt at that fix did not work, and the reason is worth knowing before
anyone reaches for it again: a PCM-only `AudioCapabilities` handed to the sink builder
is overwritten by media3's own `AudioCapabilitiesReceiver` as soon as it registers, so
the rebuilt sink asked the platform a second time and got the same wrong answer. The
refusal now lives in `PcmOnlyAudioSink`, wrapping the finished sink, where nothing
downstream can overwrite it. Verified on hardware: AC-3 and E-AC-3 clips now reach
`c2.dolby.ac3.decoder` and `c2.dolby.eac3.decoder`.

**The eight-clip matrix is green on hardware.** Seven of eight play with both a video and
an audio decoder named in the receiver's log; the eighth, H.264+DTS, plays silent because
the verified TV declares no DTS decoder at all. The full decoder-by-decoder table is in
[implementation.md](implementation.md#decoder_init-is-usually-not-the-decoder--it-is-ac-3-passthrough-on-a-bluetooth-route).

One caution for anyone re-running it: a mid-run router-side peer block between phone and
TV (the failure in `research/03`) reads as codec failures it is not. If clips start
returning `SKIP` or no first frame, check `ping` between the two devices before believing
the verdict — both can be healthy on the same /24 and still unable to reach each other.

```sh
export FLICK_PHONE=<adb serial>  FLICK_TV=<adb serial or host:port>
./docs/store/push-codec-clips.sh      # stage the clips, force a metadata scan
./docs/store/codec-matrix-test.sh     # cast each, read the verdict from the TV's log
```

The verdict names the audio decoder that actually ran, and a clip that shows video with
`audio=NONE` is reported as `SILENT` rather than `PASS` — silence is the one failure the
picture cannot reveal. DTS is expected to land there: the verified TV declares no DTS
decoder at all.

And the detail sheet said **"Will direct-play at full quality"** for a file that then did
not play. The verdict is computed without knowing what the paired TV will accept, so the
app's most prominent promise could be wrong and never learn it was. That half is fixed —
the sheet now stops promising after one failure and refuses after two.

### Promo video (optional, one per listing)

Play takes a single YouTube URL per listing. It must be public or unlisted, have ads
disabled, carry no age restriction, and be embeddable. The same six captions above
work as the beat sheet; the footage has to be the real app.

---

## 6. App content declarations

Everything under **Policy → App content**. All of it must be complete before a
release can be reviewed.

### Privacy policy

Publish `docs/privacy/index.html` and give Play the URL:

1. GitHub → the Flick repo → **Settings → Pages**
2. Source: **Deploy from a branch**, branch `main`, folder `/docs`
3. The policy lands at
   `https://aniketshahane.github.io/Flick/privacy/`

Use the same URL for both listings — the page names both packages.

### Data safety

The honest answers, verified against the code rather than assumed. The receiver makes
**no outbound internet connection at all**; the sender reaches exactly one host.

**Does your app collect or share any of the required user data types?** — **Yes**
(because of the optional OpenSubtitles sign-in; everything else is local-only).

Declare exactly one item:

| Field | Answer |
| --- | --- |
| Data type | Personal info → **User IDs** |
| Collected | Yes |
| Shared | Yes — with OpenSubtitles, at the user's request |
| Processed ephemerally | No (a session token is stored on device) |
| Required or optional | **Optional** |
| Purpose | App functionality |

Answer **No** to every other data type — no location, no photos or videos collected,
no files or docs, no contacts, no app activity, no crash logs, no diagnostics, no
advertising ID.

> Videos are read on the device and sent only to a TV the user paired, on their own
> network. Play's definition of "collection" is data transmitted off the device to a
> server the developer or a third party controls. That never happens, so **Photos and
> videos → No**. Say this in the App access notes too, because it is the answer a
> reviewer is most likely to want to check.

Also declare:
- Data is **encrypted in transit**: **No.** The LAN media transfer is plain HTTP by
  design, and claiming otherwise would be false. Explain it in App access notes.
- Users **can request deletion**: uninstalling removes everything; there is no server
  copy to delete.

### Photo and Video Permissions declaration — the likeliest rejection

`READ_MEDIA_VIDEO` is a restricted permission. Since October 2023 an app may only use
it when the Android Photo Picker cannot serve its core function, and Google states
plainly that having a custom picker does not by itself qualify. You will have to
submit a declaration. Paste this:

```
Flick's core function is a browsable library of the user's own video files, which it
serves to a paired television over the local network. The Android Photo Picker cannot
provide this, for four independent reasons.

1. Persistent access. Flick serves the chosen file over HTTP to the TV for the whole
   length of playback, from a foreground service, while the phone's screen is off and
   the app is in the background. Photo Picker grants are scoped to the granting
   activity and do not survive that lifecycle.

2. Sidecar subtitle files. Flick reads .srt/.ass files stored alongside a video so a
   film the user already has subtitles for plays with them. The picker returns a
   single opaque item and cannot expose neighbouring files.

3. Library browsing is the product. Users choose from folders, thumbnails, durations,
   search results and per-file resume positions across their whole collection. The
   picker is a one-shot selection UI and returns no library to browse.

4. File-content addressing. Flick computes a hash over the video file's bytes to
   identify the exact release when matching subtitles. This requires reading the file
   itself, repeatedly and across sessions.

Flick already implements the minimum-scope alternative: on Android 14+ it requests
READ_MEDIA_VISUAL_USER_SELECTED and degrades to a partial-access library when the
user grants only selected items. Broad access is requested, and used, only to show
the user their own library.
```

If it is refused, the fallback is to ship on `READ_MEDIA_VISUAL_USER_SELECTED` alone
and accept a user-selected library. The app already handles that path.

### Foreground service types

`FOREGROUND_SERVICE_MEDIA_PLAYBACK` must be declared, **with a demonstration video
link**. Use case: **Media playback**.

```
Flick serves the user's chosen video file, over their local Wi-Fi network, to a
television they have paired with. The service exists for the duration of playback and
runs no longer.

It must be a foreground service because playback continues while the phone's screen
is off and the app is in the background: the TV is reading the file from the phone in
real time. If the system defers or stops this service, the byte stream to the TV stops
and playback halts mid-film.

The service posts an ongoing notification with transport controls, backed by a
MediaSession, for the whole time it runs.
```

Record a screen capture (YouTube unlisted is fine) showing, in one take:

1. Opening Flick and choosing a video
2. The video starting on the TV
3. The ongoing notification with transport controls appearing
4. Pressing the phone's power button to blank the screen
5. The TV still playing
6. Waking the phone and stopping playback from the notification

### The rest of App content

| Section | Answer |
| --- | --- |
| Ads | **No ads** |
| App access | Restricted — see below |
| Content rating | Complete the IARC questionnaire. Utility, no violence, no user-to-user sharing, no purchases → expect Everyone / PEGI 3 |
| Target audience | **18+** (or 13+). Do not include under-13, which pulls both apps into the Families policy |
| News app | No |
| Data safety | Above |
| Government app | No |
| Financial features | No |
| Health | No |

### App access instructions — write these carefully

This is where a two-app system gets rejected. The reviewer of Flick TV has a TV and
no paired phone; the reviewer of Flick has a phone and no TV.

For **Flick TV**:

```
Flick TV is the television half of a two-app system. It plays video served by the
companion phone app, Flick (com.flick.sender), over the local network. No login, no
account and no test credentials are needed.

Without a phone paired, Flick TV opens on its idle screen and its Settings screen is
fully reachable — no part of the app is behind a login.

To exercise playback end to end:
1. Install the companion phone APK: <GitHub Release URL>
2. Put the phone and the TV on the same Wi-Fi network.
3. Open Flick TV. It shows a pairing QR code, or a code that can be typed.
4. Open Flick on the phone, tap Pair, and scan or type that code.
5. Choose any video in the phone's library and tap Cast. It plays on the TV.

Note on networking: the phone serves the video file to the TV over plain HTTP on the
local network only (port 8080). There is no server operated by the developer and no
internet connection is involved in playback. This is why Data safety declares
transit encryption as "No" — the transfer never leaves the user's own LAN.
```

For **Flick**:

```
Flick needs no login, account or test credentials. Every screen is reachable on first
launch after granting video access.

Flick casts to the companion Android TV app, Flick TV (com.flick.receiver). To
exercise casting you need an Android TV device on the same Wi-Fi network with that
app installed: <GitHub Release URL>

Without a TV present the app is still fully reviewable: the video library, playback
on the phone itself, subtitle search, settings and the support screen all work.

The video file is served from the phone directly to the paired TV over the local
network. Nothing is uploaded and there is no developer-operated server.
```

Publish both release APKs as a GitHub Release and paste the two asset URLs in.

### Tips — no Play billing needed

Flick's tip jar sends 100% to the developer and unlocks nothing. Google's Payments
policy covers this exactly:

> "In cases where 100% of the tip or contribution from a user goes to the creator and
> the payment does not grant access to any digital content or services … then we
> regard this as a peer-to-peer payment and use of Google Play's billing system is not
> required."

So the Stripe checkout links are compliant as built, and the in-app copy — "A tip is
just a one-time thank-you" — is doing policy work as well as tonal work. Do not let
anyone later add a perk for tipping: that single change would make Play billing
mandatory.

---

## 7. Technical requirements — all already met

| Requirement | Deadline | Status |
| --- | --- | --- |
| Target API 36 | 31 Aug 2026 | `targetSdk = 36` ✓ |
| 64-bit support | in force | `arm64-v8a` and `x86_64` present ✓ |
| 16 KB page sizes | 1 Aug 2026 | every 64-bit `.so` at `p_align=16384` ✓ |
| Android App Bundle | in force | `bundleRelease` ✓ |
| Upload key valid past Oct 2033 | in force | valid to Dec 2053 ✓ |

The API 36 deadline is 24 days away and Flick already meets it.

Both bundles ship four ABIs — `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` — which is
compliant: the rule forbids being 32-bit *only*, and every 32-bit library here has a
64-bit counterpart. Checked by parsing the ELF program headers straight out of the
AABs: 5 native libraries per ABI in `:sender` (CameraX, ML Kit barcode, DataStore,
graphics-path) and 1 in `:receiver`. The 16 KB rule applies to 64-bit libraries, and
all of those are aligned at 16384.

Play splits an AAB by ABI, so no user downloads a library for an architecture they do
not have.

---

## 8. Uploading

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :sender:bundleRelease :receiver:bundleRelease
```

```
sender/build/outputs/bundle/release/sender-release.aab      (20 MB)
receiver/build/outputs/bundle/release/receiver-release.aab  (9.5 MB)
```

Order of operations in Console, per app:

1. Create the app → free, not a game
2. Complete **App content** in full (section 6)
3. **Testing → Closed testing** → create a track → upload the AAB
4. Add 12+ testers by email, or a Google Group
5. For **Flick TV only**: Setup → Advanced settings → **Form factors → Add release
   type → Android TV**. This needs the TV screenshots uploaded first.
6. Share the opt-in link; confirm all 12 have opted in
7. Wait 14 days without anyone opting out
8. Dashboard → **Apply for production**

The production-access questionnaire asks what feedback testers gave and what you
changed because of it. Keep notes during the 14 days — a thin answer is a common
reason to be sent back.

Every later upload needs a higher `versionCode`. Bump it in both modules' `build.gradle.kts`.

---

## 9. Risk register

| Risk | Likelihood | What to do |
| --- | --- | --- |
| Photo & Video declaration refused | Medium | Fall back to `READ_MEDIA_VISUAL_USER_SELECTED` only; the code path exists |
| TV app fails Android TV quality review | Medium | Every screen must be D-pad reachable with visible focus; check before uploading |
| Reviewer can't test the two-app pairing | Medium | The App access text above, plus real APK links |
| Foreground service demo video rejected | Low | Must show the screen going off and playback continuing |
| Tips read as in-app purchase | Low | Policy quoted above; keep the copy free of any perk |
| Losing the upload keystore | Low | Back it up today |
| A TV update reaching users ahead of the phone update | Medium, from v1.1 on | Roll the phone out first and let it reach users before the TV. An older phone drops its control socket on any frame it does not know — see [control-channel.md](design/control-channel.md). Not a risk at 1.0.0, where both ship together. |

---

## 10. Left to do before day 0

- [ ] Back up `~/.flick-keys/flick-upload.jks` and its password
- [ ] Enable GitHub Pages and confirm the privacy URL loads
- [ ] Capture 2+ phone screenshots and 2–3 more TV screenshots
- [ ] Record the foreground-service demo video
- [ ] Publish both release APKs as a GitHub Release for reviewers
- [ ] Create the Play Console account and verify identity
- [ ] Decide EU trader status
- [ ] Line up 12 testers
