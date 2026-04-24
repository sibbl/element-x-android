# Audio Guidelines

## Objective

Support TTS, voice recording, and voice playback with **minimum** complexity.

## Rules

- TTS stays watch-side (use Android `TextToSpeech` on Wear OS).
- Voice recording stays watch-side (`MediaRecorder` → OGG/Opus file).
- Phone handles Matrix-compatible voice upload / send via existing Element X voice message pipeline.
- Playback must handle route / device limitations gracefully (surface "needs BT headset" where necessary).
- Use the simplest reliable format first: **Ogg/Opus, 16 kHz mono**, matching matrix-rust-sdk voice message expectations.

## Avoid

- Creating a second voice-message pipeline on the watch.
- Moving media upload, retry, or E2EE concerns to the watch.
- Overengineering codecs in MVP.

## Pipeline

1. Watch records → local temp file → emits `WatchVoiceDraft { draftId, tempAudioUri, durationMs, codec, … }`.
2. Watch opens `ChannelClient` to phone → streams file bytes.
3. Phone persists the draft → invokes existing Element X voice message send use-case.
4. Phone returns `Ack.Pending` → `Ack.Sent` once matrix event is acknowledged by the homeserver.

## Playback

- Phone projects the voice message's `playbackUri` (a content-provider-backed URI accessible via Data Layer transfer, not a matrix mxc URI directly) as `WatchPlaybackDescriptor`.
- Watch streams / downloads and plays via `MediaPlayer`.
