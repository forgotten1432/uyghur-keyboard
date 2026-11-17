#!/usr/bin/env bash
# Runs on the Android emulator in CI: installs the APK, types with the real keyboard, takes screenshots.
set -u
OUT=/tmp/emu; mkdir -p "$OUT"
PKG=com.uyghur.keyboard
IME=$PKG/.UyghurKeyboardService
K=.github/emulator/keys.py
log() { echo "$*" | tee -a "$OUT/results.txt"; }

shot() { sleep 0.8; adb exec-out screencap -p > "$OUT/$1.png"; log "screenshot $1"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1; }
keys() { sleep 0.6; adb logcat -d -s UgKeys:D > "$OUT/keys.txt"; }
tap() { adb shell input tap $1 $2; }
key() { tap $(python3 $K one "$OUT/keys.txt" "$1"); }
type_text() {
  python3 $K pos "$OUT/keys.txt" "$1" | while read -r x y; do adb shell input tap "$x" "$y"; done
}
field_text() { dump; python3 $K text "$OUT/ui.xml" "$1"; }

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant $PKG android.permission.RECORD_AUDIO
# the system needs a moment to register a newly installed keyboard
for i in $(seq 1 30); do
  adb shell ime list -a -s | grep -q "$PKG" && break
  sleep 2
done
adb shell ime enable $IME
adb shell ime set $IME
log "current keyboard: $(adb shell settings get secure default_input_method)"
adb shell settings put system show_touches 1
adb logcat -c
adb shell am start -n $PKG/.SetupActivity
sleep 5
shot 01_setup
adb shell input swipe 540 1900 540 500 400; sleep 1
dump
tap $(python3 $K field "$OUT/ui.xml" test-text)
sleep 6                                 # first start: dictionaries + model are loaded
keys
shot 02_uyghur_empty

# ── phrase learning: type the greeting 3 times ──
for i in 1 2 3; do
  type_text "ئەسسالامۇ ئەلەيكۇم قانداق ئەھۋالىڭىز. "
done
shot 03_typed_three_times
log "typed: $(field_text test-text)"

# clear the field and look at the predictions
adb shell input keyevent KEYCODE_MOVE_END
adb shell input keyevent $(for i in $(seq 1 200); do printf '67 '; done)
sleep 1; keys
shot 04_empty_field_prediction
key SLOT1; sleep 0.5; shot 05_after_first_word
key SLOT1; sleep 0.5; shot 06_after_second_word
key SLOT1; sleep 0.5; shot 07_after_third_word
log "after tapping middle suggestion 3x: $(field_text test-text)"

# ── word completion + typo correction ──
key SPACE; type_text "ياخ"; shot 08_completion_yax
type_text "شم"; shot 09_typo_yaxshm
key SLOT1; sleep 0.5
log "after picking correction: $(field_text test-text)"

# ── long press on period ──
P=$(python3 $K one "$OUT/keys.txt" ".")
adb shell input swipe $P $P 1500 &
sleep 0.9; adb exec-out screencap -p > "$OUT/10_long_press_period.png"; wait
log "screenshot 10_long_press_period"

# ── shift layer ──
key SHIFT; keys; shot 11_uyghur_shift
key SHIFT; keys

# ── Turkish ──
key LANGUAGE; keys; shot 12_turkish
key SPACE; type_text "nası"; shot 13_turkish_nasi
key SLOT1; sleep 0.5
log "turkish: $(field_text test-text)"

# ── English ──
key LANGUAGE; keys; shot 14_english
key SPACE; type_text "keybaord"; shot 15_english_typo
key LANGUAGE; keys          # back to Uyghur

# ── number pad ──
adb shell input keyevent KEYCODE_BACK; sleep 1
dump
tap $(python3 $K field "$OUT/ui.xml" test-number); sleep 2; keys
shot 16_number_pad
type_text "1250.75"
log "number field: $(field_text test-number)"
shot 17_number_typed

# ── dark theme ──
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell cmd uimode night yes; sleep 3
adb shell input swipe 540 1900 540 500 400; sleep 1
dump
tap $(python3 $K field "$OUT/ui.xml" test-text); sleep 2; keys
shot 18_dark

adb logcat -d | grep -E "FATAL|AndroidRuntime|UyghurIME|AsrEngine" | tail -50 > "$OUT/logcat.txt"
log "done"
