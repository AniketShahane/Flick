#!/usr/bin/env python3
"""Writes the handshake tunnel's drawables: tunnel_loop.xml (rings, streaks and the halo
breathing around the window), tunnel_wash.xml (the static wash and glow under them) and
tunnel_window.xml (the porthole's pane before the film shows through it).

The tunnel is an AnimatedVectorDrawable because that is the one public animation the
platform runs on the RenderThread: it keeps flowing while the main thread is blocked at a
stage seam. Every ring and streak loops on its own period with its phase baked into its
keyframes (a wrap point where its alpha is 0), so the tunnel is full from its first frame.

Run from the repository root: python3 receiver/tools/gen_tunnel_avd.py
The geometry must match TUNNEL_* in ui/components/HouseLights.kt; HouseLightsPlanTest
reads the generated file back and checks it.
"""
import math
import os

W, H = 960.0, 540.0
VP_X, VP_Y = W / 2, 0.42 * H
WIN_W = 0.3 * W
WIN_H = 0.3 * H
WIN_R = 0.04 * WIN_W

RING_COUNT = 7
RING_LIFE_MS = 3100
RING_GROWTH = 4.2          # scale = 1 + growth * q^2; at 5.2 the ring is past every edge
RING_STROKE = (1.5, 3.0)   # on-screen stroke, q = 0 -> 1
RING_ALPHA = 0.55
RING_AMBER_ALPHA = 0.8
AMBER_RING = 3

STREAKS = [
    # (angle in degrees on the unsquashed circle, life ms, phase, colour)
    (12, 1500, 0.00, "white"),
    (48, 1700, 0.55, "blue"),
    (83, 1400, 0.25, "white"),
    (118, 1600, 0.80, "amber"),
    (152, 1500, 0.40, "blue"),
    (196, 1800, 0.10, "white"),
    (231, 1400, 0.65, "blue"),
    (262, 1600, 0.35, "amber"),
    (297, 1500, 0.90, "white"),
    (333, 1700, 0.50, "blue"),
]
STREAK_SQUASH = 0.7
STREAK_LEN = (20.0, 72.0)
STREAK_TRAVEL = 520.0
STREAK_GAP = 8.0
STREAK_ALPHA = 0.9

ENVELOPE_RISE = 0.12
FADE_IN_MS = 360
BREATH_MS = 900
BREATH_SCALE = 1.05
GLOW_REACH = 2.2          # the glow is this many times the window's size
# The window's approach: it grows from APPROACH_FROM of its size toward its full size as
# 1 - exp(-t / tau), on the RenderThread so a stalled main thread never stops it. Must
# match TUNNEL_APPROACH_* in HouseLights.kt.
APPROACH_FROM = 0.55
APPROACH_TAU_MS = 1600.0
APPROACH_MS = 12000
APPROACH_SAMPLES = 32
PANE_BLEED = 1.0          # viewport units the porthole's pane overlaps the cutout by
HALO = [(5.0, 6.0, 0.22), (12.0, 10.0, 0.08)]  # (offset from the window, stroke, alpha)
SAMPLES = 16


def envelope(q):
    if q <= 0 or q >= 1:
        return 0.0
    if q < ENVELOPE_RISE:
        return q / ENVELOPE_RISE
    return (1 - q) / (1 - ENVELOPE_RISE)


def f(v):
    s = ("%.4f" % v).rstrip("0").rstrip(".")
    return s if s not in ("-0", "") else "0"


def phased(phase):
    """(fraction, q) keyframe stops for one loop that starts at q = phase."""
    grid = sorted({k / SAMPLES for k in range(SAMPLES + 1)} | {ENVELOPE_RISE, phase})
    stops = []
    for q in grid:
        if q >= 1:
            continue
        frac = q - phase if q >= phase else q - phase + 1
        stops.append((frac, q))
    if phase > 0:
        wrap = 1 - phase
        stops.append((wrap - 0.0005, 1.0))
        stops.append((wrap, 0.0))
    stops.append((1.0, phase if phase > 0 else 1.0))
    stops = sorted(set(stops))
    out = []
    for frac, q in stops:
        if out and abs(out[-1][0] - frac) < 1e-9:
            out[-1] = (frac, q)
        else:
            out.append((frac, q))
    return out


def pvh(name, stops, value):
    rows = "".join(
        '\n                        <keyframe android:fraction="%s" android:value="%s" />' % (f(fr), f(value(q)))
        for fr, q in stops
    )
    return (
        '\n                    <propertyValuesHolder android:propertyName="%s" android:valueType="floatType">%s'
        "\n                    </propertyValuesHolder>" % (name, rows)
    )


def target(name, duration, holders):
    return (
        '\n    <target android:name="%s">'
        '\n        <aapt:attr name="android:animation">'
        '\n            <objectAnimator android:duration="%d" android:repeatCount="infinite"'
        '\n                android:interpolator="@android:anim/linear_interpolator">%s'
        "\n            </objectAnimator>"
        "\n        </aapt:attr>"
        "\n    </target>" % (name, duration, "".join(holders))
    )


def rounded_rect(cx, cy, w, h, r):
    x0, y0 = cx - w / 2, cy - h / 2
    return (
        "M%s,%s h%s a%s,%s 0 0 1 %s,%s v%s a%s,%s 0 0 1 -%s,%s h-%s a%s,%s 0 0 1 -%s,-%s v-%s a%s,%s 0 0 1 %s,-%s z"
        % (
            f(x0 + r), f(y0), f(w - 2 * r), f(r), f(r), f(r), f(r), f(h - 2 * r), f(r), f(r), f(r), f(r),
            f(w - 2 * r), f(r), f(r), f(r), f(r), f(h - 2 * r), f(r), f(r), f(r), f(r),
        )
    )


def ring_scale(q):
    return 1 + RING_GROWTH * q * q


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    win = rounded_rect(VP_X, VP_Y, WIN_W, WIN_H, WIN_R)
    glow_r = GLOW_REACH * WIN_W / 2

    paths = []
    targets = []
    for i in range(RING_COUNT):
        amber = i == AMBER_RING
        phase = i / RING_COUNT
        stops = phased(phase)
        paths.append(
            '\n        <group android:name="ring%d" android:pivotX="%s" android:pivotY="%s" android:scaleX="%s" android:scaleY="%s">'
            '\n            <path android:name="ring%dpath" android:pathData="%s"'
            '\n                android:strokeColor="@color/%s" android:strokeAlpha="%s" android:strokeWidth="%s" />'
            "\n        </group>"
            % (
                i, f(VP_X), f(VP_Y), f(ring_scale(phase)), f(ring_scale(phase)), i, win,
                "tunnel_amber" if amber else "tunnel_ring",
                f((RING_AMBER_ALPHA if amber else RING_ALPHA) * envelope(phase)),
                f((RING_STROKE[0] + (RING_STROKE[1] - RING_STROKE[0]) * phase) / ring_scale(phase)),
            )
        )
        peak = RING_AMBER_ALPHA if amber else RING_ALPHA
        targets.append(target("ring%d" % i, RING_LIFE_MS, [
            pvh("scaleX", stops, ring_scale),
            pvh("scaleY", stops, ring_scale),
        ]))
        targets.append(target("ring%dpath" % i, RING_LIFE_MS, [
            pvh("strokeAlpha", stops, lambda q, p=peak: p * envelope(q)),
            pvh("strokeWidth", stops, lambda q: (RING_STROKE[0] + (RING_STROKE[1] - RING_STROKE[0]) * q) / ring_scale(q)),
        ]))

    for i, (deg, life, phase, colour) in enumerate(STREAKS):
        a = math.radians(deg)
        dx, dy = math.cos(a), STREAK_SQUASH * math.sin(a)
        k = math.hypot(dx, dy)
        screen = math.degrees(math.atan2(dy, dx))
        ux, uy = dx / k, dy / k
        edge = min(
            (WIN_W / 2) / abs(ux) if abs(ux) > 1e-6 else 1e9,
            (WIN_H / 2) / abs(uy) if abs(uy) > 1e-6 else 1e9,
        )
        r0 = edge + STREAK_GAP
        travel = STREAK_TRAVEL * k

        def reach(q, r0=r0, travel=travel):
            return r0 + travel * q * q

        def trim(q):
            return (STREAK_LEN[0] + (STREAK_LEN[1] - STREAK_LEN[0]) * q) / STREAK_LEN[1]

        stops = phased(phase)
        paths.append(
            '\n        <group android:pivotX="%s" android:pivotY="%s" android:rotation="%s">'
            '\n            <group android:name="streak%d" android:translateX="%s">'
            '\n                <path android:name="streak%dpath" android:pathData="M%s,%s h%s"'
            '\n                    android:strokeColor="@color/tunnel_streak_%s" android:strokeAlpha="%s" android:strokeWidth="2"'
            '\n                    android:strokeLineCap="round" android:trimPathEnd="%s" />'
            "\n            </group>"
            "\n        </group>"
            % (
                f(VP_X), f(VP_Y), f(screen), i, f(reach(phase)), i, f(VP_X), f(VP_Y), f(STREAK_LEN[1]),
                colour, f(STREAK_ALPHA * envelope(phase)), f(trim(phase)),
            )
        )
        targets.append(target("streak%d" % i, life, [pvh("translateX", stops, reach)]))
        targets.append(target("streak%dpath" % i, life, [
            pvh("strokeAlpha", stops, lambda q: STREAK_ALPHA * envelope(q)),
            pvh("trimPathEnd", stops, trim),
        ]))

    breath = (
        '\n    <target android:name="breath">'
        '\n        <aapt:attr name="android:animation">'
        '\n            <set>'
        '\n                <objectAnimator android:propertyName="scaleX" android:duration="%d" android:valueFrom="1" android:valueTo="%s"'
        '\n                    android:repeatCount="infinite" android:repeatMode="reverse"'
        '\n                    android:interpolator="@android:interpolator/accelerate_decelerate" />'
        '\n                <objectAnimator android:propertyName="scaleY" android:duration="%d" android:valueFrom="1" android:valueTo="%s"'
        '\n                    android:repeatCount="infinite" android:repeatMode="reverse"'
        '\n                    android:interpolator="@android:interpolator/accelerate_decelerate" />'
        "\n            </set>"
        "\n        </aapt:attr>"
        "\n    </target>" % (BREATH_MS, f(BREATH_SCALE), BREATH_MS, f(BREATH_SCALE))
    )

    def fade(name):
        return (
            '\n    <target android:name="%s">'
            '\n        <aapt:attr name="android:animation">'
            '\n            <objectAnimator android:propertyName="alpha" android:duration="%d" android:valueFrom="0" android:valueTo="1"'
            '\n                android:interpolator="@android:interpolator/decelerate_quad" />'
            "\n        </aapt:attr>"
            "\n    </target>" % (name, FADE_IN_MS)
        )

    def approach_size(t_ms):
        return APPROACH_FROM + (1 - APPROACH_FROM) * (1 - math.exp(-t_ms / APPROACH_TAU_MS))

    # Denser early, where it moves fastest.
    approach_stops = sorted({(i / APPROACH_SAMPLES) ** 2 for i in range(APPROACH_SAMPLES + 1)})
    approach_target = (
        '\n    <target android:name="approach">'
        '\n        <aapt:attr name="android:animation">'
        '\n            <objectAnimator android:duration="%d" android:interpolator="@android:anim/linear_interpolator">%s%s'
        "\n            </objectAnimator>"
        "\n        </aapt:attr>"
        "\n    </target>"
        % (
            APPROACH_MS,
            pvh("scaleX", [(fr, fr) for fr in approach_stops], lambda fr: approach_size(fr * APPROACH_MS)),
            pvh("scaleY", [(fr, fr) for fr in approach_stops], lambda fr: approach_size(fr * APPROACH_MS)),
        )
    )

    def approaching(body):
        return (
            '\n        <group android:name="approach" android:pivotX="%s" android:pivotY="%s" android:scaleX="%s" android:scaleY="%s">%s'
            "\n        </group>" % (f(VP_X), f(VP_Y), f(APPROACH_FROM), f(APPROACH_FROM), body)
        )

    def document(name, body, targets):
        return (
            '<?xml version="1.0" encoding="utf-8"?>'
            "\n<!-- Generated by receiver/tools/gen_tunnel_avd.py. Edit the generator, not this file. -->"
            '\n<animated-vector xmlns:android="http://schemas.android.com/apk/res/android"'
            '\n    xmlns:aapt="http://schemas.android.com/aapt">'
            '\n    <aapt:attr name="android:drawable">'
            '\n        <vector android:name="%s" android:width="%sdp" android:height="%sdp"'
            '\n            android:viewportWidth="%s" android:viewportHeight="%s" android:alpha="0">'
            "%s"
            "\n        </vector>"
            "\n    </aapt:attr>"
            "%s"
            "\n</animated-vector>\n" % (name, f(W), f(H), f(W), f(H), body, targets)
        )

    # The halo breathes around the window: two soft outlines, so the frame it redraws stays thin.
    halo = "".join(
        '\n            <path android:pathData="%s" android:strokeColor="@color/tunnel_ring" android:strokeAlpha="%s" android:strokeWidth="%s" />'
        % (rounded_rect(VP_X, VP_Y, WIN_W + 2 * d, WIN_H + 2 * d, WIN_R + d), f(a), f(w))
        for d, w, a in HALO
    )
    loop_body = (
        '\n        <group android:name="breath" android:pivotX="%s" android:pivotY="%s">%s'
        "\n        </group>"
        "%s"
        % (f(VP_X), f(VP_Y), halo, "".join(paths))
    )
    # The porthole before the film is up: an opaque blue with a soft light in it. One unit
    # larger than the cutout all round, so the two antialiased edges never leave a seam.
    pane = rounded_rect(VP_X, VP_Y, WIN_W + 2 * PANE_BLEED, WIN_H + 2 * PANE_BLEED, WIN_R + PANE_BLEED)
    light_r = WIN_W / 2
    window_body = (
        '\n        <path android:name="window" android:pathData="%s" android:fillColor="@color/tunnel_porthole_base" />'
        '\n        <group android:pivotX="%s" android:pivotY="%s" android:scaleY="%s">'
        '\n            <path android:name="light" android:pathData="M%s,%s a%s,%s 0 1 0 %s,0 a%s,%s 0 1 0 -%s,0 z">'
        '\n                <aapt:attr name="android:fillColor">'
        '\n                    <gradient android:type="radial" android:centerX="%s" android:centerY="%s" android:gradientRadius="%s">'
        '\n                        <item android:offset="0" android:color="@color/tunnel_porthole_light" />'
        '\n                        <item android:offset="1" android:color="@color/tunnel_porthole_light_edge" />'
        "\n                    </gradient>"
        "\n                </aapt:attr>"
        "\n            </path>"
        "\n        </group>"
        % (
            pane, f(VP_X), f(VP_Y), f(WIN_H / WIN_W),
            f(VP_X - light_r), f(VP_Y), f(light_r), f(light_r), f(2 * light_r), f(light_r), f(light_r), f(2 * light_r),
            f(VP_X), f(VP_Y), f(light_r),
        )
    )
    # Nothing in the wash animates but its fade, which is the drawable's root alpha: that is
    # applied when its cached bitmap is composited, so the gradients are rasterised once.
    wash_body = (
        '\n        <path android:name="wash" android:pathData="M0,0 h%s v%s h-%s z">'
        '\n            <aapt:attr name="android:fillColor">'
        '\n                <gradient android:type="radial" android:centerX="%s" android:centerY="%s" android:gradientRadius="%s">'
        '\n                    <item android:offset="0" android:color="@color/tunnel_wash_core" />'
        '\n                    <item android:offset="0.45" android:color="@color/tunnel_wash_mid" />'
        '\n                    <item android:offset="1" android:color="@color/tunnel_wash_edge" />'
        "\n                </gradient>"
        "\n            </aapt:attr>"
        "\n        </path>"
        '\n        <group android:pivotX="%s" android:pivotY="%s" android:scaleY="%s">'
        '\n            <path android:name="glow" android:pathData="M%s,%s a%s,%s 0 1 0 %s,0 a%s,%s 0 1 0 -%s,0 z">'
        '\n                <aapt:attr name="android:fillColor">'
        '\n                    <gradient android:type="radial" android:centerX="%s" android:centerY="%s" android:gradientRadius="%s">'
        '\n                        <item android:offset="0" android:color="@color/tunnel_glow_core" />'
        '\n                        <item android:offset="0.5" android:color="@color/tunnel_glow_mid" />'
        '\n                        <item android:offset="1" android:color="@color/tunnel_glow_edge" />'
        "\n                    </gradient>"
        "\n                </aapt:attr>"
        "\n            </path>"
        "\n        </group>"
        % (
            f(W), f(H), f(W),
            f(VP_X), f(VP_Y), f(0.75 * W),
            f(VP_X), f(VP_Y), f(WIN_H / WIN_W),
            f(VP_X - glow_r), f(VP_Y), f(glow_r), f(glow_r), f(2 * glow_r), f(glow_r), f(glow_r), f(2 * glow_r),
            f(VP_X), f(VP_Y), f(glow_r),
        )
    )
    drawables = os.path.join(root, "src", "main", "res", "drawable")
    for name, xml in (
        ("tunnel_loop.xml", document("tunnel", approaching(loop_body), fade("tunnel") + approach_target + breath + "".join(targets))),
        ("tunnel_wash.xml", document("wash_root", wash_body, fade("wash_root"))),
        ("tunnel_window.xml", document("window_root", approaching(window_body), fade("window_root") + approach_target)),
    ):
        path = os.path.join(drawables, name)
        with open(path, "w") as fh:
            fh.write(xml)
        print("wrote", path)


if __name__ == "__main__":
    main()
