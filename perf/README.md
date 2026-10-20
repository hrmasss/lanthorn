# Perf harness

Measures the NF rules in `docs/specs/02_requirements.md` on a real phone, and MX Player 3.3.1 on
the same phone and files for the "no worse than MX" bar.

**Emulator numbers are for regressions only, never for claims.** The emulator has no GPU and no
hardware decoder.

## Run it

```
perf/gen-media.sh                 # once; full set, tens of minutes. --quick for 10 s clips
./gradlew :app:assembleBench      # release code + bench mode, debug-signed, id app.player.bench
perf/bench.sh --apk app/build/outputs/apk/bench/app-arm64-v8a-bench.apk
```

One phone on adb (or `ANDROID_SERIAL`). About 25 minutes. Results go to
`perf/results/<date>-<model>.md` (commit it) and raw JSON to `perf/results/raw/` (not committed).
Record the phone in `perf/devices.md` the first time.

`--only startup,first-frame,playback,seek,scrub` runs a subset. `--quick` runs short versions.

## What is measured

| Rule | Scenario | How |
|---|---|---|
| NF-1 cold start | `startup` | `am start -W` on the launcher activity after force-stop, 10 runs, `TotalTime` (to first frame drawn). App compiled `speed-profile` first, like a Play install. |
| NF-2 first frame | `first-frame` | VIEW intent on the player, cold process, 10 runs per file. Time from the system logging the intent (`ActivityTaskManager START`) to the engine's first-frame event (mpv `playback-restart` after load), both from logcat on the device clock. |
| NF-3, NF-4, NF-7 drops | `playback` | Bench activity plays through the real player screen, opens and closes the control overlay every 10 s, reads mpv's counters: dropped by the video output plus dropped by the decoder, over frames played. 5 min for 1080p60, 2 min for 4K. |
| NF-5 fallback | `first-frame` on `h264-444-1080p30.mp4` and the info panel | Phone hardware refuses 4:4:4; the decoder must read SW within 1 s and playback must not leave the player. |
| NF-6 seek | `seek` | 20 exact seeks to random points of the 1-hour file, time from the command to `playback-restart`. |
| NF-6 scrub | `scrub` | Keyframe seeks at 30 per second for 5 s, count frames shown per second. |
| NF-9 size | - | CI prints APK sizes; the arm64 release APK is the number. |

The bench build is not debuggable, because debuggable apps run slower. It reads media from
`/sdcard/Movies/PlayerBench`, the same files MX can open.

## Media

`perf/gen-media.sh` generates everything with ffmpeg; nothing is committed. Every frame has a
burnt-in timecode and frame number, so screen recordings can be read frame by frame.

| File | For |
|---|---|
| `h264-1080p60.mp4`, `hevc8-1080p60.mp4` (5 min) | NF-2, NF-3 |
| `hevc10-2160p60.mp4`, `av1-2160p60.mp4`, `av1-1080p60.mp4` (2 min) | NF-4 |
| `vp9-1080p60.webm` (2 min) | codec coverage |
| `ass-karaoke-1080p60.mkv` (5 min, font attached) | NF-7 |
| `h264-444-1080p30.mp4` (30 s) | NF-5 fallback |
| `h264-long-1080p30.mp4` (1 h, 8 s GOP) | NF-6 |

## Measuring MX on the same phone

MX exposes no counters, so it is measured from outside, the same way for both apps. Run each
step on our app too, so the comparison uses one method.

**Setup:** same phone, same files in `/sdcard/Movies/PlayerBench`, airplane mode, screen
brightness fixed, battery above 50%, phone cool. MX at default settings, decoder left on its
default. Turn on Developer options > Show taps.

| Rule | Method |
|---|---|
| NF-1 cold start | `adb shell am force-stop <mx package>` then `adb shell am start -W -n <mx launcher activity>`, 10 runs, `TotalTime`. Find the activity with `adb shell cmd package resolve-activity --brief <mx package>`. |
| NF-2 first frame | Screen record at 60 fps (`adb shell screenrecord --bit-rate 20M`), tap the file in each app's list, 10 times each. Read the recording frame by frame: first frame showing the tap dot to first frame showing the video's burnt-in timecode. 16.7 ms resolution. `perf/frames.sh <video>` lists frame times with large picture changes to speed this up. |
| NF-3, NF-4 drops | `perf/sf-drops.sh '<video layer>' <seconds> <fps>` during playback: samples `dumpsys SurfaceFlinger --latency` every second and counts gaps longer than 1.5 frame intervals. Layer names from `adb shell dumpsys SurfaceFlinger --list`. Run it on our app too, so both numbers come from the compositor. |
| NF-6 seek | Screen recording: release of the finger (tap dot gone) to the first frame showing the target timecode. |
| NF-7 subtitles | Screenshots at fixed timecodes, compared side by side for styling, karaoke fill and positioning. |
| NF-9 size | Installed APK size of MX from `adb shell pm path` and `stat`. |

Results go in the same results file under an "MX 3.3.1" heading, one row per measurement, next to
ours.
