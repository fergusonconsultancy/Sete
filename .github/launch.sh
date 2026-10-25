#!/usr/bin/env bash
# Opens Sete, walks through the welcome, the garden and a watering round, then reports any crash.
set +e
PKG=com.fergusonconsultancy.sete
SIZE=$(adb shell wm size | grep -o "[0-9]*x[0-9]*" | tail -1); W=${SIZE%x*}; H=${SIZE#*x}
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
for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',x):
    if sys.argv[1] in (m.group(1), m.group(2)):
        a,b,c,d=map(int,m.groups()[2:]);hit=((a+c)//2,(b+d)//2)
if hit: print(*hit)
PY
)
  if [ -n "$b" ]; then adb shell input tap $b; echo "tapped '$1' at $b"; else echo "::warning::no '$1' on screen"; fi
}

# Taps the first on-screen plant card name from the list given
tap_any() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1
  b=$(python3 - "$@" <<'PY'
import re,sys
x=open('ui.xml',encoding='utf-8').read()
H=int(re.search(r'bounds="\[0,0\]\[\d+,(\d+)\]"',x).group(1))
for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',x):
    a,b,c,d=map(int,m.groups()[1:])
    if m.group(1) in sys.argv[1:] and b>150 and d<H-200:
        print((a+c)//2,(b+d)//2);break
PY
)
  if [ -n "$b" ]; then adb shell input tap $b; echo "tapped card at $b"; else echo "::warning::no card on screen"; fi
}
mkdir -p shots
shot() { adb exec-out screencap -p > "shots/$1.png"; }

adb shell cmd uimode night no
adb shell am start -W -n $PKG/.MainActivity
sleep 8
shot 01-welcome-day
tap_text "Not now"
sleep 2
adb shell input swipe $((W/2)) $((H*7/10)) $((W/2)) $((H*3/10)) 300
sleep 2
shot 02-garden-day
adb shell am start -W -n $PKG/.MainActivity --ez round true
sleep 4
shot 03-round-day
tap_text "Watered"; sleep 3
tap_text "Still damp"; sleep 2
tap_text "Bone dry"; sleep 3
tap_text "Skip"; sleep 2
tap_text "Close"; sleep 2
# The tally chips take you to their group; a plant's page shows its spot; the light meter saves
tap_text "Not logged"; sleep 2
shot 04-chip-scrolled
tap_any "Bird of Paradise" "Citrus Tree" "Christmas Cactus" "Little Spider Plant" "Bromeliad" "Orchid in Bloom" "Pilea" "Kalanchoe, Terracotta" "Peace Lily" "Cane Orchid" "Orchid with the Sloth" "Kalanchoe Cuttings" "Dieffenbachia" "Monstera" "Orchid, Dotted Pot" "Orchid, Wild Roots" "Kalanchoe, Fluted Pot" "Dragon Tree" "Aloe" "Big Spider Plant"
sleep 2
shot 05-plant-day
# Scroll the sheet until the light meter button shows
for i in 1 2 3 4; do
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1
  grep -q 'Measure the light here' ui.xml && python3 -c "
import re;x=open('ui.xml').read();m=re.search(r'text=\"Measure the light here\"[^>]*?bounds=\"\\[\\d+,(\\d+)\\]',x);import sys;sys.exit(0 if m and int(m.group(1))<$((H*8/10)) else 1)" && break
  adb shell input swipe $((W/2)) $((H*7/10)) $((W/2)) $((H*3/10)) 700; sleep 1
done
shot 06-plant-spot
tap_text "Measure the light here"; sleep 4
shot 07-meter
tap_text "Save this reading"; sleep 2
adb shell input keyevent KEYCODE_BACK; sleep 1
# Adding starts with the camera; closing the camera still opens the form
tap_text "Add a plant with a photo"; sleep 5
shot 08-camera
adb shell input keyevent KEYCODE_BACK; sleep 3
shot 09-add-after-camera
adb shell input keyevent KEYCODE_BACK; sleep 1

# Landscape: the round must still show every button
count() { adb shell run-as $PKG cat files/garden.json | python3 -c "import sys,json;print(sum(len(x['log']) for x in json.load(sys.stdin)['plants']))"; }
before=$(count)
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 2
adb shell am start -W -n $PKG/.MainActivity --ez round true
sleep 4
tap_text "Watered"; sleep 3
tap_text "Still damp"; sleep 2
after=$(count)
if [ "$after" -gt "$before" ]; then echo "::notice::landscape round logged $((after-before)) answers"; else echo "::error::landscape round: buttons not reachable"; fi
tap_text "Close"; sleep 2
adb shell settings put system user_rotation 0
sleep 2
# Night: the evening palette
adb shell cmd uimode night yes; sleep 4
adb shell am start -W -n $PKG/.MainActivity; sleep 4
shot 10-garden-night
tap_any "Bird of Paradise" "Citrus Tree" "Christmas Cactus" "Little Spider Plant" "Bromeliad" "Orchid in Bloom" "Pilea" "Kalanchoe, Terracotta" "Peace Lily" "Cane Orchid" "Orchid with the Sloth" "Kalanchoe Cuttings" "Dieffenbachia" "Monstera" "Orchid, Dotted Pot" "Orchid, Wild Roots" "Kalanchoe, Fluted Pot" "Dragon Tree" "Aloe" "Big Spider Plant"
sleep 2
shot 11-plant-night
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell cmd uimode night no

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
