#!/usr/bin/env bash
# Opens Sete, waits, taps a card and the reminders pill, then reports any crash.
set +e
APK=$(ls app/build/outputs/apk/debug/*.apk | head -1)
adb install -r "$APK"
adb shell pm grant com.fergusonconsultancy.sete android.permission.POST_NOTIFICATIONS
adb logcat -c
adb shell am start -W -n com.fergusonconsultancy.sete/.MainActivity
sleep 10
adb shell input keyevent KEYCODE_BACK
sleep 2
adb shell input swipe 500 1500 500 400 300
sleep 3
adb shell input tap 300 900
sleep 4
adb shell am broadcast -a com.fergusonconsultancy.sete.ALARM -n com.fergusonconsultancy.sete/.ReminderReceiver --es slot morning
sleep 6
adb logcat -d > logcat.txt
if grep -q "FATAL EXCEPTION" logcat.txt; then
  grep -A 40 "FATAL EXCEPTION" logcat.txt | grep -E "AndroidRuntime" | head -40 | sed 's/^.*AndroidRuntime: //' | while IFS= read -r line; do echo "::error::$line"; done
  exit 1
fi
adb shell run-as com.fergusonconsultancy.sete cat files/crash.txt 2>/dev/null | head -30 | while IFS= read -r line; do echo "::error::$line"; done
if adb shell pidof com.fergusonconsultancy.sete >/dev/null; then echo "::notice::Sete launched and is still running"; else echo "::error::Sete is not running after launch"; exit 1; fi
adb shell dumpsys notification --noredact | grep -E "pkg=com.fergusonconsultancy.sete" | head -5 | while IFS= read -r line; do echo "::notice::$line"; done
exit 0
