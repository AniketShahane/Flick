#!/bin/zsh
# Push the codec test clips to the phone and force a real MediaStore scan.
#
#   ./docs/store/push-codec-clips.sh
#
# They land in their own folder so the "Films" folder the store screenshots were shot
# from stays exactly as it was. `adb push` leaves MediaStore rows with duration NULL
# and Flick filters those out, so the scan_file pass below is not optional — without
# it the folder never appears in the app's folder picker.
# The phone's serial comes from the environment, never from this file: the repository
# is public and a device serial does not belong in it.
#   export FLICK_PHONE=<adb serial>     # `adb devices` lists it
A=${ADB:-adb}
PHONE=${FLICK_PHONE:?set FLICK_PHONE to the adb serial of the phone — run: adb devices}
SRC=${1:-$HOME/flick-test-media/codectests}
DEST="/sdcard/Movies/Codec Tests"

if [ ! -d "$SRC" ]; then
  echo "No clips at $SRC — regenerate them or pass the directory as \$1"; exit 1
fi

$A -s $PHONE shell mkdir -p "$DEST"
for f in "$SRC"/*.(mp4|mkv|webm); do
  echo "pushing ${f:t}"
  $A -s $PHONE push "$f" "$DEST/${f:t}" >/dev/null || echo "  FAILED ${f:t}"
done

echo "scanning..."
for f in "$SRC"/*.(mp4|mkv|webm); do
  $A -s $PHONE shell "content call --uri content://media --method scan_file --arg '$DEST/${f:t}'" >/dev/null 2>&1
done
$A -s $PHONE shell sleep 3

echo "=== indexed with real metadata ==="
$A -s $PHONE shell "content query --uri content://media/external/video/media --projection _display_name --where \"bucket_display_name='Codec Tests'\"" 2>&1
echo
echo "Now: open Flick, switch the library folder to 'Codec Tests', then run"
echo "  ./docs/store/codec-matrix-test.sh"
