#!/usr/bin/env bash
# subs-audio emulator job through Android Test Orchestrator (each test in its own process).
# Args: <apk root> <out dir>. <apk root> holds app-debug-x86_64/, test-feature-subtitles/, test-feature-audio/.
set -u
APK=$1; OUT=$2; mkdir -p "$OUT"
ORCH=${ORCH:?directory holding the two orchestrator APKs (see emu-orch-all.sh)}
R=androidx.test.runner.AndroidJUnitRunner
S=app.player.feature.subtitles
A=app.player.feature.audio
adb logcat -c
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard
adb install -r -g "$ORCH/orchestrator-1.6.1.apk" >/dev/null
adb install -r -g "$ORCH/test-services-1.6.0.apk" >/dev/null
adb uninstall app.player >/dev/null 2>&1
adb install "$APK/app-debug-x86_64/app-x86_64-debug.apk" > "$OUT/app-install.txt" 2>&1
adb shell pm grant app.player android.permission.READ_MEDIA_VIDEO 2>/dev/null
for p in $S $A; do adb uninstall $p.test >/dev/null 2>&1; adb shell rm -rf /sdcard/Android/data/$p.test; done
adb install -r -t "$APK/test-feature-subtitles/subtitles-debug-androidTest.apk" >/dev/null
adb install -r -t "$APK/test-feature-audio/audio-debug-androidTest.apk" >/dev/null
orch() { # <pkg> <out name> <classes>
  adb shell "CLASSPATH=\$(pm path androidx.test.services) app_process / androidx.test.services.shellexecutor.ShellMain am instrument -r -w -e targetInstrumentation $1.test/$R -e class $3 androidx.test.orchestrator/androidx.test.orchestrator.AndroidTestOrchestrator" > "$OUT/$2-instrument.txt" 2>&1
  adb pull /sdcard/Android/data/$1.test/files/evidence "$OUT/$2-shots" >/dev/null 2>&1
}
orch $S subtitles $S.AutoLoadOnDeviceTest
orch $A audio $A.AudioSettingsOnDeviceTest
adb logcat -d -s evidence:I > "$OUT/evidence-lines.txt"
adb logcat -d | grep -E " mpv |PlayerEngine|AndroidRuntime|TestRunner|evidence" | tail -6000 > "$OUT/logcat-tail.txt"
