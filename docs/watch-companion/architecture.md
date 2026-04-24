# Wear OS Companion — Architecture Note

## Scope

A **companion-only** Wear OS experience for Matrix messaging, layered on a fork of
`element-hq/element-x-android`. The watch is strictly a UI + input surface; the phone app
remains the system of record.

## Module Layout

```text
settings.gradle.kts
├── :app                       (existing — untouched until Phase 2)
├── :features/…                (existing — untouched)
├── :libraries/…               (existing — untouched)
├── :watchbridge-contract      (NEW, pure Kotlin/JVM)
├── :watchbridge               (NEW, Android library — phone side)
├── :watchbridge-testing       (NEW, pure Kotlin/JVM — fakes + fixtures, not shipped)
└── :wearapp                   (NEW, Wear OS application)
```

### `:watchbridge-contract`

- Pure Kotlin/JVM.
- Owns the versioned envelope (`WatchSyncEnvelope`), DTO projections (`WatchFavoriteRoom`,
  `WatchRoomSummary`, `WatchTimelineItem`, `WatchThreadItem`, `WatchReactionSummary`,
  `WatchVoiceDraft`, `WatchPlaybackDescriptor`), and the three distinct payload hierarchies
  (`WatchSync`, `WatchCommand`, `WatchAck`).
- Shared verbatim by phone and watch — no Android / Play Services dependencies.

### `:watchbridge`

- Android library, added to the `:app` dependency graph behind a feature flag.
- `ElementXWatchPort` is the *only* seam the host app has to implement. It returns DTOs, never
  Element X domain models.
- `WatchBridgeDispatcher` dispatches `WatchCommand`s, publishes `WatchSync*` snapshots, and
  emits `WatchAck`s — all via the pluggable `WatchTransport`.
- `WatchBridgeListenerService` is a `WearableListenerService` that wakes the phone app process
  when the watch sends a command.

### `:wearapp`

- Standalone Wear OS Compose app with three screens (`Favorites`, `Room`, `Thread`) plus a
  dedicated `VoiceRecorderActivity`.
- `WearBridgeClient` is the watch-side transport wrapper that drives `favorites` / per-room /
  per-thread flows and exposes a `send { requestId -> WatchCommand… }` helper that returns the
  `requestId` so UIs can observe their own acks.
- TTS via Android `TextToSpeech`, STT via `RecognizerIntent`, recording via `MediaRecorder`
  (OGG/Opus 16 kHz mono — matches matrix-rust-sdk voice message expectations), playback via
  `MediaPlayer`.

## Core Rule

`ElementXWatchPort` is the contract that the host app MUST implement and that the bridge
alone consumes. No other bridge code reaches into `:features/*` or `:libraries/*`. That keeps
existing Element X modules **untouched** and the bridge DI-framework-agnostic.

## Transport

| Payload | Channel |
| :--- | :--- |
| Favorites snapshot | `DataClient` @ `/watchbridge/favorites` |
| Room summary | `DataClient` @ `/watchbridge/room/summary` |
| Room timeline delta | `DataClient` @ `/watchbridge/room/timeline/{roomId}` |
| Thread delta | `DataClient` @ `/watchbridge/thread/{roomId}/{rootId}` |
| Commands (watch → phone) | `MessageClient` @ `/watchbridge/command` |
| Acks (phone → watch) | `MessageClient` @ `/watchbridge/ack` |
| Voice draft bytes | `ChannelClient` @ `/watchbridge/voice/draft` |
| Voice playback bytes | `ChannelClient` @ `/watchbridge/voice/playback` |

## Background Behavior

- `WatchBridgeListenerService` is manifest-declared with an intent filter on
  `pathPrefix="/watchbridge"`. Android wakes the phone process when the watch sends a command
  — no foreground service required.
- The service hands off to the app-scoped `WatchBridgeDispatcher` via a host-app-provided
  resolver (Metro `@Inject` is the expected implementation).
- The dispatcher publishes on coroutines scoped to the application lifecycle; it does not
  assume any Activity.

## Upstream Safety

- All four new modules are **additive**.
- The only planned edits in existing code are:
  1. `settings.gradle.kts` — four `include(…)` lines (already done).
  2. `:app/AndroidManifest.xml` — a `<service>` merge entry for
     `WatchBridgeListenerService`, behind `BuildConfig.WEAR_COMPANION_ENABLED`.
  3. `:app` DI module — a single `@ContributesBinding(AppScope::class)` binding that maps
     the existing Element X room-list / timeline / send use-cases to `ElementXWatchPort`.
  4. A settings toggle in the existing preferences screen to enable the companion.
- These touch points are documented, one by one, in [touched-files.md](touched-files.md) with a
  merge-conflict risk label.

## Feature-Flag Policy

- Compile-time gate: `WatchBridgeFeatureFlag.ENABLED` (defaults to `true` in the fork, absent
  upstream).
- Runtime gate: a user-facing preference in Element X Android settings. Defaults to `false`
  until MVP verification on the paired device.

## Answers to the spec's open questions

1. **How are Element X favorites read?** Via the existing `RoomListService` → a projection
   adapter in the host app's `ElementXWatchPort` implementation. The adapter filters rooms
   marked as favorites and maps each to `WatchFavoriteRoom`. No changes required in
   `:features/roomlist`.
2. **UX below favorites?** MVP: favorites-only. A second list (recent rooms or unread
   aggregation) is deliberately deferred but the DTO & transport paths are already generic
   (`Invalidation.scope = ROOM`, extra `WatchSync` variant can be added additively).
3. **DM vs group unification?** `WatchRoomKind = GROUP | DM`. UI branches only on this enum;
   the rest of the model is identical.
4. **Watch-friendly reaction sending?** Curated set of 6 one-tap reactions in
   `RoomScreen.QUICK_REACTIONS` (`👍 ❤️ 😂 🎉 🙏 👀`). Full emoji picker deferred.
5. **Voice message format?** Ogg/Opus, 16 kHz mono (matches matrix-rust-sdk voice pipeline).
6. **Background Android lifecycle?** Manifest-declared `WearableListenerService` is the only
   reliable wake path; the dispatcher keeps work short and delegates to app-scoped coroutines.
7. **Reusable Element X abstractions?** Room list, timeline, thread, send, reaction, and voice
   message use-cases — all consumed from the host app's `ElementXWatchPort` implementation.
8. **Biggest rebase-conflict hotspots?** `:app/AndroidManifest.xml` (merge block) and the
   single DI module that binds `ElementXWatchPort`. Both are labeled **medium** in
   `touched-files.md`.
9. **Early feature flags?** `WatchBridgeFeatureFlag.ENABLED` (compile-time) +
   preference-backed runtime flag in the host app.
10. **Clear MVP scope?** See the "Definition of Done" in [AGENTS.md](../../AGENTS.md). Deferred
    to post-MVP: below-favorites list, full emoji picker, advanced thread management, image /
    sticker / file send.
