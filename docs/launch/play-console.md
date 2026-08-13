# Play Console pack

Drafted 2026-08-11 against `versionCode 5` / `1.0.0`. Every factual claim below was read out
of the code, not assumed; where a claim is a judgement call rather than a fact, it says so.

Two separate Console entries are required, because they are two separate packages:

| | Package | Form factor |
| --- | --- | --- |
| Phone | `com.flick.sender` | Phone / tablet |
| TV | `com.flick.receiver` | Android TV |

---

## 1. App access instructions — the highest-risk field

This is the field most likely to decide the review. A reviewer holding only a phone cannot
pair with a TV, will conclude the app is broken, and will fail it. Paste this verbatim.

> **Flick is a two-app system. Both apps are required and neither works alone.**
>
> - **Flick** (`com.flick.sender`) — installs on an Android phone. It serves a video file
>   the user already has on their phone.
> - **Flick TV** (`com.flick.receiver`) — installs on an Android TV device. It plays that
>   file.
>
> Both devices must be on the same Wi-Fi network. Nothing is uploaded to any server: the
> phone streams the file directly to the TV over the local network.
>
> **To review the full flow you need both an Android phone and an Android TV device
> (or an Android TV emulator) on one network:**
> 1. Install Flick TV on the Android TV device and open it. It shows a 4-digit pairing code
>    and a QR code.
> 2. Install Flick on the phone and open it. Grant video-library access when asked.
> 3. On the phone, tap Connect. It discovers the TV on the network. Enter the 4-digit code
>    shown on the TV, or scan the QR code with the phone camera.
> 4. Pick any video in the phone's library and press the cast button. It plays on the TV.
>
> **If only a phone is available**, the phone app is still fully reviewable on its own: the
> video library, search, sorting, per-file details and the subtitle search all work with no
> TV present. Only the casting step needs the second device. The phone app will report that
> no TV was found, which is correct behaviour and not a failure.
>
> **No account or login exists anywhere in either app.** There is nothing to sign in to and
> no credentials to supply. Subtitle search is authenticated with an API key built into the
> app, so it works immediately with no setup.

Attach the demo video here as well — see `reviewer-video-prompt.md`. The generated piece
explains the two-app shape; a real screen recording of the apps is what evidences behaviour.

---

## 2. Store listings

### Phone — `com.flick.sender`

**App name (≤30):** `Flick — Cast Video to TV`

**Short description (≤80):**
`Play your phone's own 4K videos on your Android TV. No upload, no transcode.`

**Full description (≤4000):**

> Flick plays the videos already on your phone on your Android TV, at full quality, over your
> own Wi-Fi.
>
> It does not upload anything. It does not re-encode anything. It does not mirror your
> screen. Your phone serves the original file, byte for byte, and the TV decodes it in
> hardware — which is why a 4K HDR or Dolby Vision file plays as itself rather than as a
> compressed copy of itself.
>
> **What you need**
> Flick is two apps. This one runs on your phone. Install "Flick TV" on your Android TV as
> well — both are free, and neither does anything without the other. Put both devices on the
> same Wi-Fi and pair them once with a 4-digit code or a QR scan.
>
> **What it does**
> • Plays your local 4K, HDR10 and Dolby Vision videos on the TV without transcoding
> • Full playback control from the phone — scrub, pause, volume, and an audio-delay dial for
>   when your soundbar runs ahead of the picture
> • Search your library by name, and sort by newest, name, or length
> • Find and attach subtitles, or use a subtitle file you already have
> • Keeps serving with the screen off, so the phone can sit in your pocket
>
> **What it does not do**
> No account. No cloud. No analytics. No ads. Your video never leaves your home network, and
> nothing about it is sent to us — there is no "us" to send it to.
>
> Flick is free. If it is useful to you, there is an entirely optional tip jar inside; it
> unlocks nothing, because everything is already unlocked.

### TV — `com.flick.receiver`

**App name (≤30):** `Flick TV — Receiver`

**Short description (≤80):**
`Receives video from the Flick phone app and plays it at full quality.`

**Full description (≤4000):**

> Flick TV is the receiving half of Flick. Install it on your Android TV, then install
> "Flick" on your Android phone — this app does nothing on its own.
>
> Open it and it shows a 4-digit pairing code and a QR code. Pair the phone once, and from
> then on the phone finds this TV by itself.
>
> Video is decoded in hardware, direct from the original file on your phone. Nothing is
> transcoded and nothing is streamed from the internet, so a 4K HDR or Dolby Vision file
> arrives as itself. A built-in diagnostics view reports the decoder, resolution, HDR type
> and link quality if you ever want to see what is actually happening.
>
> Requires an Android phone with the Flick app, on the same Wi-Fi network. No account, no
> cloud, no ads, no analytics.

### Graphics still needed (both listings)

- Icon 512×512 PNG.
- Feature graphic 1024×500.
- Phone: at least 2 screenshots (use 4–8: library, search, sort menu, now-playing, subtitles).
- TV: at least 1 TV banner 1280×720, plus TV screenshots at 1920×1080.

---

## 3. Data safety

> **Re-verified 2026-08-12 against `884a9f9`** (the error-transparency pass: 84 files,
> +5697/−384, 66 new user-facing strings). That branch fast-forwards `main`, so the tree
> checked below is byte-identical to the post-merge tree — this is a verification of the
> upload state, not of a proposal. A verification is not carried across a diff that size;
> the section was re-derived rather than re-read. What the re-check found:
>
> - **Only external host is still `https://api.opensubtitles.com`** — sole match in shipping
>   source (`OpenSubtitlesClient.kt:485`). The other URLs in the tree are font-licence text
>   in `assets/licenses/`, which is displayed, never fetched.
> - **Still no account or sign-in.** `OpenSubtitlesSession` / `restoredSession` have zero
>   callers outside `OpenSubtitlesWire.kt` and its unit test; no `/login` request exists in
>   shipping source. The only `password` matches are `KeyboardType.NumberPassword` on the
>   **pairing-code** fields — a masked numeric keyboard for the 4-digit code, not a credential.
> - **No new permission.** The eleven declared permissions are unchanged.
> - **The sender manifest did change**, and it is worth stating rather than leaving to be
>   rediscovered under review: a `<queries>` element declaring one `ACTION_VIEW` + `video/*`
>   intent, so the error screen can tell whether a local player exists before offering the
>   button. This is the narrow form and **deliberately not `QUERY_ALL_PACKAGES`** — which
>   matters, because `QUERY_ALL_PACKAGES` is a sensitive permission needing its own Console
>   declaration and this needs none. It collects nothing, so no Data safety row changes.
> - **The 66 new strings introduce no new claim.** They name OpenSubtitles in a few subtitle
>   errors — already disclosed in the privacy policy below — and `connecting_detail` says
>   "no transcode, no cloud", which matches the listing copy rather than overreaching.

These answers were derived by reading the code. Two facts anchor everything:

- The **only** external host either app contacts is `https://api.opensubtitles.com/api/v1`.
- There is **no** analytics or crash-reporting SDK in either module — no Firebase,
  Crashlytics, Sentry or equivalent. Nothing is collected by the developer at all.

**There is no account sign-in in the shipping app.** This was checked rather than assumed:
`OpenSubtitlesSession` exists in `OpenSubtitlesWire.kt` as a parsed shape, but it is
constructed only inside that pure policy object and its unit test. No `/login` request is
ever issued — the string appears only in KDoc — no sign-in field exists in `SubtitlesSheet.kt`,
and no username or password is held anywhere in the app. Searches are authenticated with an
API key: the one the build ships with, or one the user pastes in as their own.

So **no email address, username or password ever leaves the device.**

**Does your app collect or share any of the required user data types?**
→ **Yes**, and only one thing: what the user types when searching for a subtitle.

| Data type | Collected | Shared | Purpose | Notes |
| --- | --- | --- | --- | --- |
| App activity → Other actions | Yes | Yes | App functionality | A subtitle search sends the film's title, or a numeric fingerprint of the file, to opensubtitles.com. Never to the developer. |
| Personal info (name, email, user IDs) | **No** | **No** | — | No sign-in exists. Nothing identifying is sent. |
| Photos and videos → Videos | **No** | **No** | — | Read from local storage and served only on the local network. Never transmitted off the network and never to the developer. |
| Location, contacts, financial info, health, messages, calendar | No | No | — | Not accessed at all. |

Also declare:
- **Is all data encrypted in transit?** Yes for the row above — the OpenSubtitles traffic is
  HTTPS. *(The phone→TV media stream is plain HTTP on the local network only. It carries no
  data type in the table above, but do not claim more than is true if asked more broadly.)*
- **Can users request data deletion?** No data is held on any server by this developer,
  because there is no server.
- **Is data collection optional?** Yes. Subtitle search is a feature the user chooses to use;
  casting works without ever touching it.

The **camera** is used only to read the pairing QR code on screen. No image is stored or
transmitted, so it is not a Data safety disclosure — but expect the reviewer to ask why a
casting app wants a camera, so the answer above belongs in App access instructions too.

---

## 4. Content rating (IARC questionnaire)

Category: **Utility / Productivity / Communication** (not a game).

Expected answers — all "No" unless noted:
- Violence, sexuality, profanity, controlled substances, gambling, horror: **No**. The app
  contains none. It plays files the user already has; the questionnaire asks about content
  *you* supply.
- **Does the app allow users to interact or exchange content with other users?** **No.** It
  streams to the user's own TV on their own network. There is no server, no other user, and
  no discovery of anyone else's content.
- **Does the app share the user's location?** **No.**
- **Does the app allow purchases?** **Yes, external** — see the payments note below.
- **Does the app contain links to external websites?** **Yes** — OpenSubtitles, the tip links,
  and the privacy policy.

Expected outcome: **Everyone / PEGI 3 / ESRB Everyone.**

---

## 5. Foreground service justification

Play asks why `mediaPlayback` is used. Answer:

> The app runs an HTTP server that streams a local video file to the user's television for
> the entire duration of playback. Serving must continue while the screen is off and while
> the user is in another app, because stopping it stops the film mid-playback on the TV. The
> service posts a persistent notification with transport controls, and it is started only
> when the user begins casting and stopped when the cast ends.

`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is deliberately **not** declared, so no
battery-exemption declaration is needed. The app routes users to the OS battery settings
screen instead, which needs no permission.

---

## 6. Privacy policy

A hosted URL is mandatory. Draft text — host it anywhere public and stable:

> **Flick — Privacy Policy**
> Last updated: 11 August 2026
>
> **We do not collect anything.** Flick has no account system, no server, no analytics, and
> no advertising. The developer receives no data about you or your device.
>
> **Your videos stay on your network.** Flick reads videos from your phone's storage so it
> can list them and stream them to your television. That stream travels directly from your
> phone to your TV across your own Wi-Fi. Your video files are never uploaded anywhere and
> are never sent to the developer.
>
> **Subtitles are the one thing that leaves your network, and only if you ask.** If you
> search for subtitles, Flick sends the film's title — or a short numeric fingerprint of the
> file, which cannot be turned back into the file — to OpenSubtitles.com. Nothing identifies
> you: Flick has no account and asks you for no name, email or password. That request goes to
> OpenSubtitles, never to us. Their privacy policy governs it:
> https://www.opensubtitles.com/en/privacy_policy
>
> **Camera.** If you pair by scanning the QR code on your TV, the camera is used to read that
> code on your device. No image is stored, and no image is transmitted.
>
> **Tips.** If you choose to leave a tip, the payment is handled entirely by Stripe on
> Stripe's own web pages. Flick never sees or stores payment details.
>
> **Children.** Flick is not directed at children and collects no data from anyone.
>
> **Contact.** <add a contact address — Play requires a reachable one>

---

## 7. Two policy risks worth knowing before you submit

**The tip links.** Flick opens `buy.stripe.com` links in a browser for voluntary tips. Google
Play Billing is generally required for in-app digital purchases, with donations treated
differently. These tips unlock nothing — no feature, no content, no removal of ads — which is
the fact that matters most for that distinction. I am not able to give you a definitive read
on current Play Payments policy, so check the live policy text before submitting, and be
ready to describe the tips as unlocking nothing if asked. Declaring purchases as external
rather than hiding them is the safer posture either way.

**The `INTERNET` permission with a "nothing leaves your network" claim.** These are both true
— the internet access is for OpenSubtitles and the tip links — but a reviewer reading the
listing may see a contradiction. The listing copy above already says "your video never leaves
your home network" rather than "the app has no internet access", which is the honest and
defensible version. Keep it that way.
