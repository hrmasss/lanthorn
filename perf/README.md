# Perf harness

Measures the NF rules in `docs/specs/02_requirements.md` on a real phone, and MX Player 3.3.1 on
the same phone and files for the "no worse than MX" bar.

**Emulator numbers are for regressions only, never for claims.** The emulator has no GPU and no
hardware decoder.

## Phone run on apollo (P2.4)

What Hojayfa does, start to end. About 45 minutes, most of it unattended.

| Step | Command or action | Done when |
|---|---|---|
| 1. Tools | `adb`, `ffmpeg`, `python3`, `gh` (logged in) on apollo | `adb version`, `ffmpeg -version` answer |
| 2. Code | `git clone https://github.com/hrmasss/player-app && cd player-app` (or `git pull` on main) | on main |
| 3. Media | `perf/gen-media.sh` (once, tens of minutes; files land in `perf/media/`, not committed) | 9 files in `perf/media/` |
| 4. Bench APK | `gh run download $(gh run list -b main -w CI -s success -L 1 --json databaseId -q '.[0].databaseId') -n app-bench-arm64 -D perf` | `perf/app-arm64-v8a-bench.apk` |
| 5. Phone | USB debugging on, plugged in, battery 50% or more, airplane mode on, brightness fixed, screen timeout 30 min, Developer options > Show taps on, cool to the touch | `adb devices` lists one device |
| 6. Our app | `perf/bench.sh --apk perf/app-arm64-v8a-bench.apk` | `wrote perf/results/<date>-<model>.md` |
| 7. The other player | install MX 3.3.1 from the Play Store, default settings, open it once and grant storage access, then `perf/compare.sh --package <its package> --label "MX 3.3.1"` | `added to perf/results/...md` |
| 8. By hand | NF-2 first frame and NF-6 seek from screen recordings, NF-7 screenshots (table below); write the numbers under the same heading | rows filled |
| 9. Hand back | `git add perf/results/*.md perf/devices.md && git commit -m "perf: phone run <model>" && git push`, or send the results file to the orchestrator | pushed |

The bench refuses to start if the phone is not ready (battery, airplane mode, heat) and says why;
`--force` records anyway and marks the results. It copies the media to `/sdcard/Movies/PlayerBench`
once, so step 7 plays the very same files. Record the phone in `perf/devices.md` the first time.

## Run it

```
perf/gen-media.sh                 # once; full set, tens of minutes. --quick for 10 s clips
./gradlew :app:assembleBench      # release code + bench mode, debug-signed, id io.github.hrmasss.lanthorn.bench
perf/bench.sh --apk app/build/outputs/apk/bench/app-arm64-v8a-bench.apk
```

CI builds the same APK on every main push: artifact `app-bench-arm64` (step 4 above).

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
| NF-5 fallback | `first-frame` on `h264-444-1080p30.mp4` | Phone hardware refuses 4:4:4; the decoder column must read SW and the first frame comes within 1 s. A decoder that stalls mid-seek falls to SW through the engine watchdog (P2.6): scrub hard on `hevc10-2160p60.mp4` in the player and see it keep playing. |
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
| NF-1 cold start | `perf/compare.sh` does it: `am force-stop`, then `am start -W` on each app's launcher activity, 10 runs, `TotalTime`. |
| NF-2 first frame | Screen record at 60 fps (`adb shell screenrecord --bit-rate 20M`), tap the file in each app's list, 10 times each. Read the recording frame by frame: first frame showing the tap dot to first frame showing the video's burnt-in timecode. 16.7 ms resolution. `perf/frames.sh <video>` lists frame times with large picture changes to speed this up. |
| NF-3, NF-4 drops | `perf/compare.sh` does it: each app opens the same file through a VIEW intent, and `perf/sf-drops.sh` samples `dumpsys SurfaceFlinger --latency` on that app's video layer every second, counting gaps longer than 1.5 frame intervals. Both numbers come from the compositor. |
| NF-6 seek | Screen recording: release of the finger (tap dot gone) to the first frame showing the target timecode. |
| NF-7 subtitles | Screenshots at fixed timecodes, compared side by side for styling, karaoke fill and positioning. |
| NF-9 size | `perf/compare.sh` does it: installed APK size, all splits, from `adb shell pm path` and `stat`. |

Results go in the same results file under an "MX 3.3.1" heading, one row per measurement, next to
ours.
