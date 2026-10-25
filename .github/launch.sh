#!/usr/bin/env bash
# Opens Sete, walks through the welcome, the garden and a watering round, then reports any crash.
set +e
PKG=com.fergusonconsultancy.sete
APK=$(ls app/build/outputs/apk/debug/*.apk | head -1)
adb install -r "$APK"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb logcat -c

# Taps the centre of the topmost element whose text is exactly $1
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1
  b=$(python3 - "$1" <<'PY'
import re,sys
x=open('ui.xml',encoding='utf-8').read()
hit=None
# The last match is the one drawn on top (the round covers the garden)
for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',x):
    if m.group(1)==sys.argv[1]:
        a,b,c,d=map(int,m.groups()[1:]);hit=((a+c)//2,(b+d)//2)
if hit: print(*hit)
PY
)
  if [ -n "$b" ]; then adb shell input tap $b; echo "tapped '$1' at $b"; else echo "::warning::no '$1' on screen"; fi
}

adb shell am start -W -n $PKG/.MainActivity
sleep 8
tap_text "Not now"
sleep 2
adb shell input swipe 500 1500 500 400 300
sleep 2
adb shell am start -W -n $PKG/.MainActivity --ez round true
sleep 4
tap_text "Watered"; sleep 3
tap_text "Still damp"; sleep 2
tap_text "Bone dry"; sleep 3
tap_text "Skip"; sleep 2
tap_text "Close"; sleep 2
adb shell am broadcast -a $PKG.ALARM -n $PKG/.ReminderReceiver --es slot morning
sleep 6
adb logcat -d > logcat.txt
if grep -q "FATAL EXCEPTION" logcat.txt; then
  grep -A 40 "FATAL EXCEPTION" logcat.txt | grep -E "AndroidRuntime" | head -40 | sed 's/^.*AndroidRuntime: //' | while IFS= read -r line; do echo "::error::$line"; done
  exit 1
fi
adb shell run-as $PKG cat files/crash.txt 2>/dev/null | head -30 | while IFS= read -r line; do echo "::error::$line"; done
if adb shell pidof $PKG >/dev/null; then echo "::notice::Sete launched, ran a watering round and is still running"; else echo "::error::Sete is not running"; exit 1; fi
adb shell run-as $PKG cat files/garden.json | python3 -c "import sys,json;p=json.load(sys.stdin)['plants'];print('::notice::logged:',sum(1 for x in p if x['log']),'plants')"
exit 0
