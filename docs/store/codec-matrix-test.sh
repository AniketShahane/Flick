#!/bin/zsh
# End-to-end codec matrix test for the AC-3 passthrough fix.
#
# Casts every clip in /sdcard/Movies/Codec Tests one at a time and records whether
# the TV reached first frame. Reads the TV's own log rather than the phone's, because
# the receiver is where the verdict is made and a release build logs almost nothing
# on the sender.
#
#   ./docs/store/codec-matrix-test.sh
#
# Requires: both devices connected, the receiver already paired, and the Flick app
# already installed from THIS branch on both. Phone taps are guarded — the script
# refuses to tap when Flick is not the focused window, because a stray tap in an
# earlier session escaped into other apps.

# Device addresses come from the environment and are never written down here: this
# repository is public, and a serial or a LAN address is exactly what must not be in it.
#   export FLICK_PHONE=<adb serial>   FLICK_TV=<adb serial or host:port>
# `adb devices` lists both.
A=${ADB:-adb}
PHONE=${FLICK_PHONE:?set FLICK_PHONE to the adb serial of the phone — run: adb devices}
TV=${FLICK_TV:?set FLICK_TV to the adb serial or host:port of the TV — run: adb devices}
OUT=${1:-${TMPDIR:-/tmp}/flick-codec-matrix}
mkdir -p "$OUT"

focused() { $A -s $PHONE shell "dumpsys window | grep -m1 mCurrentFocus" 2>/dev/null }
guard() {
  if [[ "$(focused)" != *com.flick.sender* ]]; then
    echo "  REFUSED: Flick is not focused — $(focused)"; return 1
  fi
}
serving() {  # is the phone's media server bound?
  $A -s $PHONE shell "cat /proc/net/tcp6 /proc/net/tcp 2>/dev/null" \
    | awk 'NR>1 && $4=="0A" {print $2}' | sed 's/.*://' \
    | while read h; do printf "%d\n" "0x$h"; done | grep -qx 8080
}

echo "phone: $PHONE   tv: $TV"
$A -s $PHONE shell am force-stop com.flick.sender
$A -s $TV shell am force-stop com.flick.receiver
$A -s $TV shell monkey -p com.flick.receiver -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1
$A -s $TV shell sleep 6
$A -s $PHONE shell monkey -p com.flick.sender -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
$A -s $PHONE shell sleep 6

printf '\n%-34s %-8s %s\n' "CLIP" "RESULT" "EVIDENCE"
printf '%s\n' "----------------------------------------------------------------------"

# Tiles are read from the accessibility tree rather than assumed, so a reordered
# library cannot make this tap the wrong film.
$A -s $PHONE shell uiautomator dump /sdcard/_m.xml >/dev/null 2>&1
$A -s $PHONE shell cat /sdcard/_m.xml > "$OUT/tree.xml" 2>/dev/null

python3 - "$OUT/tree.xml" > "$OUT/tiles.txt" <<'PY'
import re, sys
x = open(sys.argv[1]).read()
for m in re.finditer(r'content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x):
    label = m.group(1).strip()
    if not label or 'Showing' in label or 'Search' in label:
        continue
    print(f"{label}\t{(int(m.group(2))+int(m.group(4)))//2}\t{(int(m.group(3))+int(m.group(5)))//2}")
PY

while IFS=$'\t' read -r label cx cy; do
  [ -z "$label" ] && continue
  $A -s $TV logcat -c 2>/dev/null
  guard || { printf '%-34s %-8s %s\n' "${label:0:33}" "SKIP" "app not focused"; continue }
  $A -s $PHONE shell input tap "$cx" "$cy"; $A -s $PHONE shell sleep 3
  # The cast button is located per sheet, never hardcoded.
  $A -s $PHONE shell uiautomator dump /sdcard/_s.xml >/dev/null 2>&1
  $A -s $PHONE shell cat /sdcard/_s.xml > "$OUT/sheet.xml" 2>/dev/null
  coords=$(python3 -c "
import re
x=open('$OUT/sheet.xml').read()
for m in re.finditer(r'(?:text|content-desc)=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', x):
    t=m.group(1).strip()
    if t.startswith('Flick to') or t.startswith('Resume from'):
        print((int(m.group(2))+int(m.group(4)))//2, (int(m.group(3))+int(m.group(5)))//2); break
")
  if [ -z "$coords" ]; then
    printf '%-34s %-8s %s\n' "${label:0:33}" "SKIP" "no cast button"
    $A -s $PHONE shell input keyevent KEYCODE_BACK; continue
  fi
  guard || continue
  $A -s $PHONE shell input tap ${=coords}
  $A -s $PHONE shell sleep 18

  $A -s $TV logcat -d > "$OUT/${label}.log" 2>/dev/null
  first=$(grep -c "firstFrame" "$OUT/${label}.log")
  rebuilt=$(grep -c "audioSinkRebuilt" "$OUT/${label}.log")
  if [ "$first" -gt 0 ] && serving; then
    note="first frame"
    [ "$rebuilt" -gt 0 ] && note="first frame (audio sink rebuilt)"
    printf '%-34s %-8s %s\n' "${label:0:33}" "PASS" "$note"
  else
    why=$(grep -oE "code=[A-Z_]+|AudioTrack init failed|Bad parameter[^\"]*" "$OUT/${label}.log" | head -1)
    printf '%-34s %-8s %s\n' "${label:0:33}" "FAIL" "${why:-see ${label}.log}"
  fi

  # Back to the library for the next clip.
  $A -s $PHONE shell uiautomator dump /sdcard/_n.xml >/dev/null 2>&1
  $A -s $PHONE shell cat /sdcard/_n.xml > "$OUT/np.xml" 2>/dev/null
  stop=$(python3 -c "
import re
x=open('$OUT/np.xml').read()
for m in re.finditer(r'(?:text|content-desc)=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', x):
    if m.group(1).strip()=='Stop casting':
        print((int(m.group(2))+int(m.group(4)))//2, (int(m.group(3))+int(m.group(5)))//2); break
")
  [ -n "$stop" ] && { guard && $A -s $PHONE shell input tap ${=stop}; }
  $A -s $PHONE shell sleep 4
done < "$OUT/tiles.txt"

$A -s $PHONE shell 'rm -f /sdcard/_m.xml /sdcard/_s.xml /sdcard/_n.xml'
printf '\nTV logs per clip: %s\n' "$OUT"
