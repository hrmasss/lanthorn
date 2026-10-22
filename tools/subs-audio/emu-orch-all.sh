#!/usr/bin/env bash
# subs-audio emulator job through Android Test Orchestrator (each test in its own process).
# Args: <apk root> <out dir>. <apk root> holds app-debug-x86_64/, test-app/, test-feature-subtitles/, test-feature-audio/.
set -u
APK=$1; OUT=$2; mkdir -p "$OUT"
# Orchestrator APKs: curl -fLO https://dl.google.com/dl/android/maven2/androidx/test/orchestrator/1.6.1/orchestrator-1.6.1.apk
#                  curl -fLO https://dl.google.com/dl/android/maven2/androidx/test/services/test-services/1.6.0/test-services-1.6.0.apk
ORCH=${ORCH:?directory holding the two orchestrator APKs}
R=androidx.test.runner.AndroidJUnitRunner
S=app.player.feature.subtitles
A=app.player.feature.audio
adb logcat -c
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard
adb install -r -g "$ORCH/orchestrator-1.6.1.apk" >/dev/null
adb install -r -g "$ORCH/test-services-1.6.0.apk" >/dev/null
adb uninstall io.github.hrmasss.lanthorn >/dev/null 2>&1
adb install "$APK/app-debug-x86_64/app-x86_64-debug.apk" > "$OUT/app-install.txt" 2>&1
adb shell pm grant io.github.hrmasss.lanthorn android.permission.READ_MEDIA_VIDEO 2>/dev/null
for p in $S $A; do adb uninstall $p.test >/dev/null 2>&1; adb shell rm -rf /sdcard/Android/data/$p.test; done
adb install -r -t "$APK/test-feature-subtitles/subtitles-debug-androidTest.apk" >/dev/null
adb install -r -t "$APK/test-feature-audio/audio-debug-androidTest.apk" >/dev/null
orch() { # <pkg> <out name>
  adb shell "CLASSPATH=\$(pm path androidx.test.services) app_process / androidx.test.services.shellexecutor.ShellMain am instrument -r -w -e targetInstrumentation $1.test/$R -e package $1 androidx.test.orchestrator/androidx.test.orchestrator.AndroidTestOrchestrator" > "$OUT/$2-instrument.txt" 2>&1
  adb pull /sdcard/Android/data/$1.test/files/evidence "$OUT/$2-shots" >/dev/null 2>&1
}
orch $S subtitles
orch $A audio
# The Subtitle and Audio groups in the real Settings screen (app module).
adb install -r -t "$(ls "$APK"/test-app/*.apk | head -1)" >/dev/null
adb shell am instrument -w -e class app.player.SettingsGroupsUiTest io.github.hrmasss.lanthorn.test/$R > "$OUT/app-instrument.txt" 2>&1
adb pull /sdcard/Android/data/io.github.hrmasss.lanthorn/files/gate "$OUT/app-shots" >/dev/null 2>&1
adb logcat -d -s evidence:I > "$OUT/evidence-lines.txt"
adb logcat -d | grep -E " mpv |PlayerEngine|AndroidRuntime|TestRunner|evidence" | tail -6000 > "$OUT/logcat-tail.txt"
