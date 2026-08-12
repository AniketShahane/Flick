#!/usr/bin/env python3
"""Turn raw device captures into Play store screenshots.

Run it with raw captures in `docs/store/raw/`; it writes finished tiles to
`docs/store/screenshots/`. Missing captures are skipped and reported, so it can be
run before every screen has been taken.

    python3 docs/store/frame-screenshots.py

## Why the phone captures cannot be uploaded as-is

Play requires that "the maximum dimension of your screenshot can't be more than
twice as long as the minimum dimension". A modern tall phone breaks that on its own:
1440x3120 is 2.167:1 and is rejected. Compositing onto a 1080x1920 canvas (exactly
16:9) is therefore not decoration — it is what makes the asset valid. The device is
drawn large and bleeds off the bottom edge, which also reads better than a shrunken
whole phone floating in space.

TV captures are already 1920x1080 and need no such rescue, so they stay full-bleed
and take a lower-third caption instead of losing screen area to a frame.

## Type

Captions are set in Bricolage Grotesque ExtraBold, the family that carries display
and titles inside both apps, so the listing and the product share a voice.
"""
import pathlib
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent.parent
RAW = HERE / "raw"
OUT = HERE / "screenshots"
DISPLAY_FONT = ROOT / "sender/src/main/res/font/bricolage_extrabold.ttf"

NIGHT = (4, 7, 15)
BLOOM = (18, 64, 232)
INK = (242, 246, 255)

PHONE_W, PHONE_H = 1080, 1920
TV_W, TV_H = 1920, 1080

# The order IS the pitch. Play shows roughly the first three in search results without
# anyone tapping through, so those three have to carry the whole proposition: what it
# is, what makes it different, and what you get.
# Every caption has to be provable by the screenshot under it. "Dolby Vision" was
# planned for shot 3 and dropped: the app supports it, but a caption sits above a
# specific frame, and a frame showing an SDR file cannot carry that claim. It stays
# in the store description, where it belongs.
PHONE_SHOTS = [
    ("library.png", "Your videos\non the big screen"),
    ("detail.png", "Never transcoded.\nNever downscaled."),
    ("nowplaying.png", "Your phone\nbecomes the remote"),
    ("subtitles.png", "Subtitles that\njust work"),
    ("pairing.png", "Pair once,\nin one scan"),
    ("privacy.png", "Nothing leaves\nyour Wi-Fi"),
]

# The paused tile leads because it is the only one that shows the receiver's own
# interface — transport, codec chips, live throughput — rather than a video frame that
# could have come from any player.
TV_SHOTS = [
    ("tv-paused.png", "Everything the TV knows, on the TV"),
    ("tv-idle.png", "Ready when your phone is"),
    ("tv-playback.png", "Hardware-decoded, full quality"),
    ("tv-diagnostics.png", "Your film, nothing else on screen"),
    ("tv-settings.png", "Pair once. Then forget it."),
]


def backdrop(w, h):
    """A calm brand ground: near-black with one soft blue bloom high in the frame.

    Deliberately quieter than the feature graphic's art. Here the app UI is the
    subject, and a busy background would compete with the thing being sold.
    """
    base = Image.new("RGB", (w, h), NIGHT)
    glow = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(glow)
    cx, cy = w // 2, int(h * 0.06)
    r = int(max(w, h) * 0.62)
    # Concentric discs approximate a smooth radial falloff cheaply and without numpy.
    steps = 60
    for i in range(steps, 0, -1):
        rr = int(r * i / steps)
        d.ellipse((cx - rr, cy - rr, cx + rr, cy + rr), fill=int(96 * (1 - i / steps) ** 2))
    glow = glow.filter(ImageFilter.GaussianBlur(w // 12))
    return Image.composite(Image.new("RGB", (w, h), BLOOM), base, glow)


def fit_font(lines, max_width, start):
    """Largest size at which every line clears the margins."""
    size = start
    while size > 20:
        font = ImageFont.truetype(str(DISPLAY_FONT), size)
        if all(font.getbbox(line)[2] - font.getbbox(line)[0] <= max_width for line in lines):
            return font
        size -= 2
    return ImageFont.truetype(str(DISPLAY_FONT), size)


# The one thing in a capture that must never be published. The Devices screen prints
# the paired TV's real LAN address; both this repository and a store listing are
# public, so it is redrawn as the fixture address this project already reserves for
# documentation. Nothing else in any capture is altered — the box and the replacement
# text are written out here so the edit is auditable rather than invisible.
REDACTIONS = {
    "pairing.png": [((405, 1200, 1294, 1325),
                     ["Google TV Streamer ·", "192.168.42.17:47654"])],
    # The TV names the phone it is paired to, so any capture of these two screens
    # carries the author's actual handset model — into a public repo, and onto a
    # store listing. "Pixel 9 Pro" is the device label already used as the fixture in
    # the receiver's instrumentation tests, so the listing and the tests name the same
    # imaginary phone rather than inventing a second one.
    #
    # Each box starts AFTER the leading glyph — the green status dot on idle, the source
    # icon on paused — so only the words are repainted and the mark survives untouched.
    # Boxes were measured off the captures, not estimated: text cap-top y974 (idle) and
    # y75 (paused), cap-height 23, and the height of 43 is what the font-size formula
    # below turns into 31 px, whose glyph height is 24. The replacement renders NARROWER
    # than the string it covers in both cases (338 vs 358, 358 vs 384), so neither can
    # overflow its chip.
    "tv-idle.png": [((137, 965, 495, 1008), ["Paired with Pixel 9 Pro"])],
    "tv-paused.png": [((180, 66, 564, 109), ["Flicked from Pixel 9 Pro"])],
}


def redact(name, capture):
    edits = REDACTIONS.get(name)
    if not edits:
        return capture
    im = capture.convert("RGB").copy()
    d = ImageDraw.Draw(im)
    for (x0, y0, x1, y1), lines in edits:
        ground = im.getpixel((x1 - 6, y0 + 4))          # card fill, clear of glyphs
        d.rectangle((x0 - 4, y0 - 4, x1 + 4, y1 + 4), fill=ground)
        size = round((y1 - y0) / len(lines) * 0.72)
        font = ImageFont.truetype(str(ROOT / "sender/src/main/res/font/geist_semibold.ttf"), size)
        y = y0
        for line in lines:
            d.text((x0, y), line, font=font, fill=(255, 255, 255))
            y += round((y1 - y0) / len(lines))
    return im


def clean_status_bar(capture):
    """Repaint the status bar with a neutral one.

    One UI ignores SystemUI demo mode, so a raw capture carries the real clock, the
    real battery percentage and whatever happened to be notifying — which dates the
    screenshot and leaks the device's state. This covers the strip in the app's own
    background colour and redraws time, signal, Wi-Fi and battery.

    Only OS chrome is touched. Nothing belonging to the app is altered, and the
    strip height stays well above the app's first content row.
    """
    im = capture.convert("RGB").copy()
    w, h = im.size
    bar = round(h * 0.048)
    ground = im.getpixel((12, bar + 12))
    light_ground = (0.299 * ground[0] + 0.587 * ground[1] + 0.114 * ground[2]) > 128
    ink = (11, 16, 32) if light_ground else (242, 246, 255)

    d = ImageDraw.Draw(im)
    d.rectangle((0, 0, w, bar), fill=ground)

    size = round(bar * 0.42)
    font = ImageFont.truetype(str(ROOT / "sender/src/main/res/font/geist_semibold.ttf"), size)
    mid = bar * 0.54
    d.text((round(w * 0.055), mid), "9:41", font=font, fill=ink, anchor="lm")

    x = w - round(w * 0.055)                     # laid out right to left
    cap_h = round(bar * 0.30)
    d.rounded_rectangle((x - cap_h * 2, mid - cap_h / 2, x, mid + cap_h / 2),
                        cap_h * 0.28, fill=ink)  # battery
    d.rectangle((x + 3, mid - cap_h * 0.18, x + 8, mid + cap_h * 0.18), fill=ink)

    x -= cap_h * 2 + round(bar * 0.20)
    for i in range(4):                            # signal, four rising bars
        bw = round(bar * 0.07)
        bh = cap_h * (0.36 + 0.21 * i)
        d.rounded_rectangle((x - bw, mid + cap_h / 2 - bh, x, mid + cap_h / 2), bw * 0.4, fill=ink)
        x -= bw + round(bar * 0.045)

    x -= round(bar * 0.14)                        # Wi-Fi, three arcs and a dot
    for i, r in enumerate((0.50, 0.34, 0.18)):
        rr = cap_h * r
        d.arc((x - rr, mid + cap_h * 0.34 - rr, x + rr, mid + cap_h * 0.34 + rr),
              215, 325, fill=ink, width=max(2, round(bar * 0.055)))
    d.ellipse((x - 4, mid + cap_h * 0.20, x + 4, mid + cap_h * 0.20 + 8), fill=ink)
    return im


def rounded_mask(size, radius):
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius, fill=255)
    return mask


def phone_tile(capture, caption):
    canvas = backdrop(PHONE_W, PHONE_H)
    draw = ImageDraw.Draw(canvas)

    lines = caption.split("\n")
    font = fit_font(lines, PHONE_W - 150, 78)
    line_h = int(font.size * 1.16)
    y = 118
    for line in lines:
        w = font.getbbox(line)[2] - font.getbbox(line)[0]
        draw.text(((PHONE_W - w) // 2 - font.getbbox(line)[0], y), line, font=font, fill=INK)
        y += line_h

    # Device: wide enough to read, bleeding past the bottom so it feels generous
    # rather than like a small phone dropped onto a poster.
    #
    # The width and the gap above are tuned together against the tallest thing any
    # of these screens has to show: the detail sheet's cast button sits ~85% down
    # the capture, and at a wider setting the bleed cut straight through its label,
    # which reads as a mistake rather than as a crop. These values clear it.
    top = y + 60
    bezel = 14
    screen_w = 792
    screen_h = round(screen_w * capture.height / capture.width)
    frame_w, frame_h = screen_w + bezel * 2, screen_h + bezel * 2
    fx = (PHONE_W - frame_w) // 2

    shadow = Image.new("L", (PHONE_W, PHONE_H), 0)
    ImageDraw.Draw(shadow).rounded_rectangle(
        (fx, top + 18, fx + frame_w, top + frame_h + 18), 62, fill=170)
    shadow = shadow.filter(ImageFilter.GaussianBlur(34))
    canvas = Image.composite(Image.new("RGB", (PHONE_W, PHONE_H), (0, 0, 0)), canvas, shadow)
    draw = ImageDraw.Draw(canvas)

    draw.rounded_rectangle((fx, top, fx + frame_w, top + frame_h), 62,
                           fill=(10, 10, 12), outline=(42, 51, 80), width=2)
    screen = clean_status_bar(capture).resize((screen_w, screen_h), Image.LANCZOS)
    canvas.paste(screen, (fx + bezel, top + bezel), rounded_mask(screen.size, 50))
    return canvas


def tv_tile(capture, caption):
    """Full-bleed capture, no caption.

    A TV capture is already 16:9 and already fills the tile, so a frame would trade
    away the only thing worth showing. Captions were tried and dropped: the receiver
    puts its controls along the bottom edge and its titles along the top, so a
    caption band collides with real UI on one screen or the other. The captions in
    TV_SHOTS survive as the notes for what each screen has to be showing when it is
    captured, and as ready-made copy if these ever move into a video.
    """
    return capture.convert("RGB").resize((TV_W, TV_H), Image.LANCZOS)


def main():
    if not DISPLAY_FONT.is_file():
        sys.exit(f"display font missing: {DISPLAY_FONT}")
    RAW.mkdir(exist_ok=True)
    OUT.mkdir(exist_ok=True)

    made, missing = 0, []
    jobs = [(n, c, phone_tile, "phone") for n, c in PHONE_SHOTS] + \
           [(n, c, tv_tile, "tv") for n, c in TV_SHOTS]

    for index, (name, caption, build, kind) in enumerate(jobs, 1):
        src = RAW / name
        if not src.is_file():
            missing.append(name)
            continue
        tile = build(redact(name, Image.open(src)), caption)
        order = index if kind == "phone" else index - len(PHONE_SHOTS)
        dest = OUT / f"{kind}-{order:02d}-{src.stem}.png"
        tile.save(dest)
        print(f"{dest.name:34s} {tile.size[0]}x{tile.size[1]}")
        made += 1

    print(f"\n{made} written to {OUT.relative_to(ROOT)}")
    if missing:
        print("still to capture: " + ", ".join(missing))


if __name__ == "__main__":
    main()
