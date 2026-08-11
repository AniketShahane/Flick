# Reviewer video — generation prompts

## What this video is, and what it must not be

Google Play's **App access instructions** is read by a reviewer deciding whether the app
works. Flick's problem there is specific: a reviewer holding only a phone cannot pair with a
TV, will conclude the app is broken, and will fail it. The video exists to make the two-app
model obvious in ten seconds.

That means this video is a **concept explainer, not a demonstration**. It shows the shape of
the system — a phone, a TV, a file moving directly between them over the home network. It
must never render Flick's actual interface, because a generated interface is invented
footage of a product's behaviour, and handing that to a reviewer as evidence the app works
misrepresents it. Generated text is also unreliable: models produce garbled words, which
looks like a broken app.

**Proof that the app works should be a real screen recording** (`adb screenrecord` on both
devices), cut after this explainer or submitted alongside it. The generated piece carries the
concept; the capture carries the evidence.

Every prompt below therefore treats device screens as abstract light and motion — a glow, a
colour field, a moving band — and never as UI.

---

## Shot 1 — The problem, stated without words (6–8 s)

> Cinematic wide shot of a warm, modern living room at dusk. A person sits on a low sofa,
> holding a modern smartphone in both hands, the screen casting soft cool light up onto their
> face and chest. Across the room, a large flat television sits dark and switched off on a
> low wooden console. The physical distance between the phone and the television is the
> subject of the frame — compose so both are clearly visible with empty space between them.
> Shallow depth of field, 35 mm lens, camera slowly pushing in a few centimetres. Warm
> practical lamps in the background, cool screen light on the person. Soft natural film
> grain, gentle highlight rolloff, muted contemporary colour grade with teal shadows and warm
> amber highlights. Photorealistic, calm, unhurried. No text, no logos, no user interface
> visible on either screen — the phone screen reads only as a soft cool glow and the
> television is completely black.

## Shot 2 — The two devices meet (6–8 s)

> Cinematic medium shot from behind and slightly over the shoulder of a person holding a
> smartphone up at chest height, framed so the dark television across the room is visible
> and in focus beyond the phone. The television wakes: a soft, even field of deep blue light
> blooms across its panel and spills onto the wall and console behind it. A moment later the
> phone's screen shifts from cool white to the same deep blue, so the two screens visibly
> match in colour. The colour match between the small screen and the large screen is the
> entire point of the shot. Slow handheld micro-movement, 50 mm lens, shallow depth of field.
> Photorealistic, warm room, cool screens. No text, no numerals, no icons, no interface
> elements of any kind on either display — both screens are pure colour and light only.

## Shot 3 — The file travels directly (8–10 s)

> Cinematic wide shot of the same living room, camera locked off. A smartphone rests on the
> sofa arm in the foreground, screen glowing. Across the room the large television now
> displays a warm, softly out-of-focus abstract image — indistinct film-like colour and
> movement, no recognisable scene, no faces, no text. Between the phone and the television,
> a single slender ribbon of soft light arcs through the air in one clean unbroken curve,
> travelling from the phone to the television, suggesting a direct path with nothing in
> between. The ribbon is thin, elegant and continuous — not particles, not a network
> diagram, not multiple beams, and it does not detour toward anything else in the room.
> Cool cyan light against the warm room. Slow, confident, premium. Photorealistic
> environment with one stylised light element. No text, no logos, no icons, no router or
> equipment visible in frame.

---

## Negative prompt (apply to every shot)

> text, letters, numbers, words, captions, subtitles, watermarks, logos, brand marks, user
> interface, app screens, buttons, menus, icons, progress bars, QR codes, cluttered room,
> visible cables between devices, router or set-top box in frame, multiple beams, particle
> swarm, network diagram, holograms, floating panels, sci-fi HUD, lens flare spam,
> oversaturated colour, distorted hands, extra fingers, deformed face

## Technical settings

- **Aspect ratio** 16:9. It is watched in the Play Console, and the product is a television.
- **Resolution** 1080p or higher.
- **Duration** three clips of 6–10 s each; cut to roughly 20–25 s total.
- **Audio** none. Generate silent and leave it silent — a reviewer reads rather than listens,
  and generated music adds a licensing question for no gain.
- **Motion** slow and minimal throughout. Fast motion reads as an advertisement; this needs
  to read as an explanation.

## The one caption to add afterwards, in an editor

Burn in a single line of real text over shot 1 or under the cut, typed by hand rather than
generated:

> **Flick is two apps. The phone app sends; the Android TV app receives. Both are required.**

That sentence is the whole reason the video exists. It must be legible, correctly spelled,
and unmistakably authored — which is exactly why it is added in an editor and never asked of
a generative model.
