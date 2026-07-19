# Design Brief for Claude: "Flick" — Design System + High-Fidelity Mockups for a Two-Screen Casting Experience (Phone Sender ⟷ TV Receiver)

You are a world-class product + brand designer. Produce a **single, self-contained visual design artifact** — an HTML page with embedded CSS and inline SVG, no external assets, no network calls — that delivers a **complete, cohesive brand + design system AND high-fidelity, annotated mockups of every screen and state** for a two-app product called **Flick**. Render mockups as *actual HTML/CSS UI* (real type, layout, gradients, glass/scrim, focus glows) inside device frames — phone screens in a portrait phone frame, TV screens in a 16:9 TV frame — never as placeholder boxes or written descriptions. This is a visual design deliverable, not shippable code, but **every choice must be buildable in the real frameworks named below** — specific enough that a Compose / Compose-for-TV engineer could build from it.

**Invent the brand FIRST, then let it flow with discipline through every screen. The synchronized phone⟷TV scrub is the hero — weight your effort there above all else.** If you must trade off depth anywhere, protect the design system, the Now Playing screens, and the scrub storyboard; an exhaustive icon set or a fifth re-tint matters less.

---

## THE PRODUCT (ground every decision in this truth)

**Flick** casts your OWN local 4K / HDR / Dolby-Vision videos from your Android phone to your Android TV over plain home Wi-Fi with **ZERO buffering**, by **direct-play**: the phone serves the original file bytes off local storage and the TV hardware-decodes them. **No transcode. No screen-mirror. No accounts. No cloud. Nothing ever leaves the home network.** It just works, and it's fast.

Two apps, one session:

1. **SENDER** — Android **phone**, Jetpack Compose + **Material 3 / Material You** (dynamic color welcome). It browses/picks local videos, manages the cast session (including a discreet "serving from this phone" foreground-service status), and — the entire point — **becomes a rich tactile REMOTE** while casting: real scrub bar, play/pause, skip ±10s, volume, glanceable signal.
2. **RECEIVER** — Android **TV / Google TV**, **Compose for TV** (androidx.tv material). A 10-foot, focus-driven, D-pad UI: a cinematic playback canvas with an on-screen scrub bar + transport, elegant now-playing chrome, a delightful first-run connect/pair flow, and the old developer debug overlay demoted to an optional toggle.

Celebrate the product truths everywhere, as design moments rather than footnotes: **local-first & private**, **4K / HDR / Dolby Vision reference quality**, **zero-buffer speed**, **no accounts**.

---

## ★ DESIGN PHILOSOPHY — THE CENTERPIECE (build the whole system to serve this)

**The phone-as-companion-remote and the SYNCHRONIZED scrub are the entire hero.** The phone and the TV are **two views of ONE session** and they **move against each other** in real time. Everything else — libraries, pairing, brand, diagnostics — exists to make this one moment sing.

The connective principles (design the system so these are literally expressible):

- **Shared playhead, two surfaces.** While a video plays, BOTH phone and TV show a scrub bar bound to the same session clock — same current-time / duration / progress-fill, same accent, same easing. Dragging the phone scrubber moves the TV playhead and the TV's on-screen bar live; play/pause/skip/volume on *either* surface reflects on the other.
- **The phone as tactile instrument.** A large, thumb-reachable scrubber anchored in the lower third; one-handed reach arcs respected. **Haptics annotated at every meaningful event** (tick detents while dragging, a firmer "snap" on release, a confirm pulse on play/pause). A **frame-preview thumbnail** rides above the thumb while dragging (a floating still with a tabular timecode chip) — feasible because the file is local.
- **The TV as cinematic canvas.** The drag manifests as the playhead gliding along the bottom bar over a blurred freeze-frame, a large timecode, and a subtle scrubbing affordance — no clutter, big legible type, cinematic dark, poster-derived ambient glow behind the controls.
- **Latency-forgiving sync mechanics (design these explicitly — this is the intellectual core):** a **ghost / echo playhead** on the TV shows the last network-confirmed position trailing the phone's optimistic local position (a "target vs confirmed" relationship); on release the two reconcile with a short **snap spring**; a faint **"syncing…" shimmer** appears when the round-trip lags; a dropped tick is smoothed, never a jump-scare jump. A little LAN latency must read as *alive and honest*, never broken.
- **The handoff feeling.** It should feel like picking the movie up in your hand — a connective visual thread (shared accent, mirrored motion, a subtle traveling light between the two device frames) so they read as one continuous object across the room.

Deliver this as a dedicated **multi-frame SCRUB STORYBOARD** (Part 4) plus a **side-by-side phone‖TV "synchronized moment" hero** on the cover.

---

## PART 1 — THE FLICK BRAND & DESIGN SYSTEM (specify and visibly demonstrate this FIRST)

Present a proper design-system page at the top of the artifact.

**Brand & mark (invent it — there is no existing system).**
"**Flick**" carries a double meaning: *a flick of the wrist/remote* (fast, effortless, kinetic motion) AND *a flick* (a movie). Lean **fast, fluid, cinematic, playful-but-premium**. Deliver an inline-SVG **logo mark** that fuses motion → play (e.g. a flicked play-triangle, a swept aperture, a motion-streaked dot that resolves into a play glyph), buildable at both **16px and 512px**. Show it in mono, on light, on dark, as the **Android adaptive/launcher icon (phone)**, and as the **wide TV banner / leanback icon** (call out the differing aspect requirements). Add a **wordmark** with a committed, justified letterform treatment, a favicon-scale lockup, and a one-line **brand voice / tagline** (e.g. "Your movies. Your network. No buffering.").

**Color story — LIGHT and DARK, both mandatory, committed (do not offer multiple-choice palettes).**
- A **deep cinematic dark** as the primary canvas: near-black with a subtle tint, *not* flat #000. Define elevated surface tiers (base / raised / glass overlay) with hex.
- A clean, airy **light theme** for the phone, Material You-friendly.
- Commit to a signature **"Flick" accent: an electric warm vermilion/coral spark** (the kinetic "flick"), paired with a **cool electric-cyan secondary** reserved for **TV D-pad focus glow and the live-link/sync moments**. This warm-content / cool-focus split is intentional — state it and use it consistently. (Refine exact hex within that story; keep the personality decisive and non-generic.)
- Full semantic set with hex + role (surface, on-surface, outline): **success / "live & serving," warning / "2.4 GHz · weak signal," error / "unreachable," info / HDR.** Plus a dedicated, tasteful **HDR / Dolby Vision "premium" treatment** (a restrained gradient or badge sheen used as a quality signal — never garish).
- **Material You is a signature, so prove it:** render the SAME Now Playing screen **re-tinted from two or three different source posters** to demonstrate artwork-seeded dynamic-color extraction on the phone — while showing the brand accent staying anchored. The TV holds a **fixed cinematic-dark** identity.

**Typography.**
A confident pairing (a characterful display/wordmark voice + a highly legible UI/body face; name system-safe families and note the intended Google-font-style face). Show specimens for **two distinct scales**: a phone (arm's-length) scale AND a separate, larger **10-ft TV scale** (bigger minimums, more weight, generous tracking). Specify **tabular figures** for all timecodes and telemetry.

**Shape, spacing, grid & layout.**
An 8pt (4pt sub-grid) system with expressive rounded corner tokens (small → extra-large + a "full" pill). **Phone:** single-column, a **thumb-reach map** with primary controls bottom-anchored. **TV:** a ~12-column rhythm with **explicit TV-safe margins (~5% overscan inset — call out the numbers and show the guide)**.

**Iconography.**
One cohesive inline-SVG set, consistent stroke: play, pause, skip ±10s, previous/next, volume, cast/connect, QR/pair, Wi-Fi/signal, HDR/DV, lock/private, settings, debug/metrics.

**Elevation, blur & material.**
Define the glass/blur layer (backdrop blur + subtle border/inner-glow), the elevation ramp, the scrim language over video (soft gradient, not a hard bar), and how poster imagery bleeds/glows behind controls.

**Focus system (TV — critical).**
Define the **unmistakable D-pad focus language**: scale-up + tonal elevation + cyan outline/glow, with focused vs unfocused vs selected vs disabled shown side by side, and a focus-order note. **No hover states exist on TV** — focus-driven only. Every interactive TV element in every mock must show which element is focused.

**MOTION language (the connective tissue — name it and document it with diagrams/motion-trail frames, since the artifact is static).**
- The signature **"flick-and-settle"**: a fast, decisive, slightly-overshooting spring with a directional streak that resolves crisply. Specify easing curves and durations, and map it to specific moments: app launch, the **toss-to-cast handoff**, seek confirm, screen transitions, and the mark animating.
- The **playhead glide**, the **sync spring** (snap-on-release reconciliation), the **poster↔playback cross-dissolve**, TV controls **fade-in/out**, TV **focus scale + glow**, and the phone's **haptic-backed scrub feel**.
- The **cross-device rule**: the same easing and the same accent pulse fire on both surfaces for the same event, so a flick on the phone visibly *is* the movement on the TV. Include the traveling light/thread that visualizes the live link.

**Component kit.** Show the reusable pieces once, well: the scrub bar (phone-tactile + TV-cinematic variants), transport cluster, play/pause morph, volume control (phone AND TV), video tile/card, the **signal/quality chip**, pairing/QR card, status pills (serving / connecting / error), and the band-warning + battery-optimization cards reimagined elegantly.

---

## PART 2 — SENDER (phone) SCREEN GALLERY

Render each in a realistic portrait phone frame, labeled + one-line caption on intent, thumb-reach-aware, key screens shown in **both light and dark**:

1. **First-run / connect–pair** — the delightful intended flow: **discovered-device list** (nearby TVs with names/thumbnails), plus **scan the TV's QR** and **short pairing code**. Manual-IP survives only as a tiny "enter manually" escape hatch. Sells local-first & private ("stays on your Wi-Fi · no account").
2. **Empty state** — no videos / not connected; warm, on-brand, guiding — not sterile.
3. **Local video library / picker** — browse your own videos *gorgeously*: filmic thumbnails, duration, **resolution + HDR/DV badges**, file size, folders/recents. A beautiful gallery, not a file browser — where Material You color comes alive.
4. **Video detail / "cast this" moment** — poster-forward sheet: big thumbnail, title, technical badges (4K • HDR10 / Dolby Vision • codec • size), a "will direct-play at full quality" reassurance, and a confident **Cast / Flick to TV** action that implies the toss gesture.
5. **Connecting** — the phone→TV handshake with the shared-motion connection visual; honest about the LAN handshake.
6. **NOW PLAYING — the phone as rich remote** *(a centerpiece)* — full transport: a **large tactile scrub bar** in the thumb zone, play/pause (morphing), skip ±10s, **volume**, now-playing title over a poster/ambient backdrop tinted by artwork, a discreet "serving from this phone" indicator, and a **glanceable signal chip**. Designed for haptics and one-handed ergonomics.
7. **Paused** — the remote at rest.
8. **Seeking / scrubbing — THE synchronized moment** — enlarged scrubber, **frame-preview thumbnail above the thumb**, tabular timecode chip, haptic detents annotated, ghost/snap affordances noted. (Feeds the storyboard.)
9. **Buffering / loading** — rare by design; a calm, quality-forward moment that reassures rather than alarms.
10. **Quality / signal indicator** — reimagine the real diagnostics (throughput, buffer health, resolution + HDR flag, decoder name, Wi-Fi band + RSSI) as ONE **elegant, glanceable "signal" chip that expands to a tasteful quality / latency / HDR sheet** — beautiful gauges, **NOT a wall of dev metrics.**
11. **2.4 GHz band warning + battery-optimization card** — today's functional warnings reframed as friendly, specific, on-brand advisories (the app knows 2.4 GHz can't sustain 4K VBR peaks; the battery nudge keeps it serving reliably).
12. **Error / diagnosis** — two distinct states: **reachable-but-not-serving** vs **unreachable**, each with a calm, specific, human explanation and a clear next step.

---

## PART 3 — RECEIVER (TV) SCREEN GALLERY

Render each in a 16:9 TV frame, **cinematic/dark**, with the **TV-safe margin guide visible**, big 10-ft type, and an **explicit D-pad focus state shown** on interactive elements:

1. **First-run / connect–pair** — a premium 10-ft welcome that pairs the phone with a **large QR + short code** (no keyboard hunting), device name, and the private/local-first promise. Focus-navigable.
2. **Empty / idle "ready to cast"** — an ambient, screensaver-grade standby with the mark, quietly showing the pairing hint; waiting for a flick from the phone.
3. **Now-playing chrome revealed** — the cinematic canvas with transport summoned by the remote: on-screen **scrub bar with buffered range + transport** (play/pause, skip, **volume**), now-playing title, HDR/quality badge, tasteful scrim, poster-derived ambient glow. Show the **focused** control distinctly.
4. **Playing, chrome hidden** — pure full-bleed, HDR-respecting cinema; controls dissolved away.
5. **Paused** — elegant, chrome up, focus visible.
6. **Seeking / scrubbing** — the TV side of the hero: playhead gliding along the bar with a **ghost/echo playhead**, large timecode, preview frame, snap-on-release — driven by the remote (or mirrored from the phone).
7. **Buffering / loading** — calm, cinematic, minimal.
8. **Quality / signal moment** — the diagnostics reimagined for 10 ft: a tasteful, glanceable resolution • Dolby Vision • decoder • Wi-Fi-health read that appears briefly, not a permanent HUD.
9. **Error / diagnosis** — the TV-side **reachable-but-not-serving** vs **unreachable**, 10-ft legible and reassuring, with a clear D-pad-actionable recovery step.
10. **Optional DEBUG overlay — show BOTH states:** the demoted resting state (a single unobtrusive toggle in settings) AND the **overlay ON** — the dense developer metrics (throughput, buffer, resolution/HDR flag, decoder name, Wi-Fi band/RSSI) restyled as an **intentional, well-typed developer HUD**, clearly a secondary power-user layer. Proof the dev asset survives tastefully.

---

## PART 4 — THE HERO: SYNCHRONIZED PHONE ⟷ TV SCRUB (the marquee — make it sing)

A dedicated **storyboard section**: a horizontal sequence of **paired frames (phone + TV side-by-side)** showing the two-screen choreography across time. Storyboard at least these beats, each annotated with what animates, what is optimistic vs confirmed, the motion token used, the haptic beat, and the "two views of one session" connective thread:

1. **Playing / in sync** — both bars advancing together at the same playhead; shared poster-seeded color, shared motion.
2. **Thumb-down on phone** — the scrubber grabs (haptic ripple); TV chrome wakes; frame-preview thumbnail appears above the thumb.
3. **Dragging** — the phone thumb moves; the **TV playhead + on-screen bar follow live**; draw motion arrows showing phone→TV drive; detent haptics; timecodes track.
4. **Network-lag beat (latency grace)** — the optimistic phone position **leads**; the TV **ghost/echo playhead trails** at the last-confirmed position; a faint **"syncing…" shimmer** — responsive and honest, never janky.
5. **Release / seek complete** — a **snap-on-release spring** reconciles both surfaces with a shared confirmation pulse; playback resumes.
6. **Cross-surface control** — play/pause (or skip, or volume) hit on the **TV remote reflecting instantly on the phone**, and vice-versa.

Spell out the choreography principles alongside the frames: phone = **tactile one-handed remote** (haptics, thumb-reach, large scrubber, frame preview); TV = **cinematic canvas**; **shared design language + shared motion + the traveling live-link light** so they unmistakably read as two views of ONE Flick session.

---

## REAL CONSTRAINTS (keep it buildable, not fantasy)

- Phone = **Jetpack Compose + Material 3** (Material You dynamic color welcome, brand accent anchored). TV = **Compose for TV (androidx.tv material)** — focus-driven, **no hover**, D-pad navigation, unmistakable focus states (scale + glow + border), big legible 10-ft type, generous **TV-safe margins**. Every TV mock shows which element is focused. Respect one-handed phone reach (primary actions in the thumb zone).
- Transport is **LAN direct-play** between two devices on the same home Wi-Fi — reflect that privacy/local-first truth in copy and iconography (a "Direct-play over your Wi-Fi · nothing leaves home" cue). **No cloud imagery, no account/sign-in chrome anywhere.**
- The real diagnostics must be represented (reimagined, never dumped): throughput, buffer health, resolution + HDR/DV flag, decoder name, Wi-Fi band + RSSI, and the 2.4 GHz peak-sustain warning. Verified hardware detail you may reference for honest copy: Google TV Streamer, **Dolby Vision Profile 8.1**, decoder names like `c2.mtk.dvhe.sth.decoder` — but the dev-metrics density only appears in the opt-in TV debug overlay.
- Support **light and dark** on the phone; the **TV leans cinematic/dark**.
- Use realistic, filmic placeholder poster/thumbnail art (CSS gradients / inline SVG stand-ins) that evoke real movies without copying any real brand.

---

## OUTPUT FORMAT (what to actually produce)

Produce **ONE self-contained, responsive HTML artifact** — all CSS and SVG inline, no external fonts/images/scripts, works offline in light and dark, wide rows scroll inside their own containers — structured top-to-bottom as:

1. **Cover** — the Flick mark, wordmark, tagline, a one-line philosophy statement, and the **side-by-side synchronized-scrub hero (phone ‖ TV).**
2. **Design-system page** — brand/logo (incl. phone-icon vs TV-banner lockups at both scales), color (light + dark swatches with hex + roles, plus the multi-poster Material You re-tint proof), typography (phone + TV scales, specimens, tabular figures), spacing/grid + a TV-safe-margin diagram, iconography, elevation/glass samples, the **focus-state spec**, the documented **motion language**, and the component kit.
3. **Sender (phone) gallery** — every Part 2 screen/state in phone frames, key screens in both light and dark, each labeled + captioned.
4. **Receiver (TV) gallery** — every Part 3 screen/state in 16:9 frames with visible focus states and TV-safe guides, each labeled + captioned.
5. **Scrub storyboard** — the Part 4 phone⟷TV synchronized sequence, paired frames, annotated with the choreography and the latency-handling mechanics.

Render every mockup as real HTML/CSS UI in device frames — not descriptions, not empty boxes. Keep it visually rich but skimmable (clear headings, generous whitespace, annotations only where motion/latency/focus behavior needs explaining), and internally consistent so one brand system visibly flows through all five sections. Make it beautiful enough to win a design award and specific enough to build from.

---

### How to use this in Claude Design

Paste this whole brief into Claude's design tool as a single message; it produces one self-contained HTML artifact you can preview and iterate on in place. If the first pass runs long or thins out toward the end, re-prompt section by section ("regenerate Part 4 — the scrub storyboard — at full fidelity"), since the hero storyboard and Now Playing screens matter most. To explore identity, ask it to vary only the Part 1 color story or mark while holding the rest fixed.
