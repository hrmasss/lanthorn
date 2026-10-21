# Phone-only criteria

EARS ids that only Hojayfa's phone can prove: the emulator has no GPU, no hardware decoder, no real
audio output and makes no timing claims. One line each: id, owner, why.

- US-47.3, subs-audio: external audio within 40 ms of the video needs real audio output timing; emulator jitter alone is over 40 ms.
- US-48.2, subs-audio: the Bluetooth delay switches on a real Bluetooth output; the emulator has none.
- US-49.2, subs-audio: the output peak under 0 dBFS is read after the platform effect; the emulator's Visualizer reads before it.
- NF-7, subs-audio: styled and karaoke ASS within the frame-drop budget needs the phone's GPU; the emulator draws in software.
