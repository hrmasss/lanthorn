# Phone-only criteria

EARS ids that no emulator test can prove: they need real hardware decoding, a real GPU or the
reference phone's timing. `tools/ears-report.py` (workspace) lists them as phone-only instead of
missing. Their numbers come from `perf/bench.sh` on Hojayfa's phone (see `README.md` here).
One line each: id, owner, why. Add your own; keep the reason concrete.

| Id | Owner | Why |
|---|---|---|
| NF-1 | engine | Cold start to the library's first frame in 500 ms: emulator start-up times say nothing about a phone. |
| NF-2 | engine | First video frame in 300 ms p50: needs the phone's hardware decoder. |
| NF-3 | engine | 0.1% drops at 1080p60 on hardware decoding: the emulator has no hardware decoder. |
| NF-4 | engine | 4K HEVC 10-bit and AV1 hardware decoding: not on the emulator. |
| NF-6 | engine | 200 ms release and 10 preview frames a second: emulators decode in software at 0.5 to 1 frame a second. |
| NF-5 | engine | Mid-playback: a hardware decoder that stalls inside a seek. The emulator proves the watchdog with an injected block (EngineStallUiTest, DecoderFallbackTest); a real MediaCodec stall, falling to SW at the same place with the player still responsive, needs the phone. Check: scrub hard on 4K HEVC 10-bit on HW. |
| NF-7 | engine | ASS within the NF-3 drop budget: same as NF-3. |
| US-17.2 | engine | Lands on the target on the emulator (SeekPreviewUiTest); the NF-6 time budget is the phone's. |
| US-17.3 | engine | 10 updates a second while swiping: NF-6, phone. |
| US-30.1 | engine | Preview shown, 192 dp, exact frames on the emulator (SeekPreviewUiTest); 10 a second within 2 s of the finger is NF-6, phone. |
| US-24.2 | playback | Restarts the same video with no next file and no end on the emulator (QueueUiTest); the 500 ms gap at the restart needs the phone. |
| US-47.3 | subs-audio | External audio within 40 ms of the video: needs a real audio output's timing; emulator jitter alone is over 40 ms. |
| US-48.2 | subs-audio | The Bluetooth delay switches on a real Bluetooth output; the emulator has none. |
| US-49.2 | subs-audio | Output peak under 0 dBFS, read after the platform effect: the emulator's Visualizer reads before it. The limiter settings are tested. |
| US-20.2 | player-ui | Back to normal speed on lift is checked on the emulator; the 100 ms bound is the phone's. |
| US-54.1 | player-ui | Every ratio is drawn and measured on the emulator; "within one frame" needs the phone's frame timing. |
| US-57.1 | player-ui | Mirror and flip are pixel-checked on the emulator; "within one frame" needs the phone's frame timing. |
| US-57.3 | player-ui | Range, steps and the identical frame at zero on the emulator; the 100 ms bound is the phone's. |
| US-31.2 | player-ui | Pause within 200 ms of a headset or Bluetooth disconnect: the emulator has nothing to unplug, and only the system may send the "becoming noisy" broadcast. |
| NF-9 | library | 1000 videos listed within 1.5 s of a cold scan: the phone's MediaStore query and storage speed. The emulator times a fake 1000-row scan to the list (LibraryHomeTest) for regressions only. The 35 MB arm64 APK limit is read from CI's "APK sizes" step. |
| US-80.2 | library | Mounting or removing an SD card or USB drive needs real hardware; the emulator cannot. The rescan on the system's volume broadcast is unit-tested (LibraryViewModelTest). |
