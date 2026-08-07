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
PHONE_SHOTS = [
    ("library.png", "Your videos\non the big screen"),
    ("detail.png", "Never transcoded.\nNever downscaled."),
    ("nowplaying.png", "4K HDR and\nDolby Vision"),
    ("subtitles.png", "Subtitles that\njust work"),
    ("pairing.png", "Pair once,\nin one scan"),
    ("privacy.png", "Nothing leaves\nyour Wi-Fi"),
]

TV_SHOTS = [
    ("tv-idle.png", "Ready when your phone is"),
    ("tv-playback.png", "Hardware-decoded 4K HDR"),
    ("tv-diagnostics.png", "See exactly what your TV is doing"),
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
    top = y + 90
    bezel = 14
    screen_w = 828
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
    screen = capture.convert("RGB").resize((screen_w, screen_h), Image.LANCZOS)
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
        tile = build(Image.open(src), caption)
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
