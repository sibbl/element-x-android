# Patch Plan — Wear OS Companion Fork

Sequenced, PR-sized changes. Each step is independently mergeable and flagged.

## Phase 0 — Scaffolding (this patch)

- [x] Add `:watchbridge-contract`, `:watchbridge`, `:watchbridge-testing`, `:wearapp` to
  `settings.gradle.kts`.
- [x] Provide the full DTO + protocol in `:watchbridge-contract` with roundtrip tests.
- [x] Provide the phone-side `WatchBridgeDispatcher`, `WatchBridgeListenerService`, and
  `WatchTransport` with Play Services implementation.
- [x] Provide a Wear OS app skeleton with Favorites / Room / Thread / Voice Recorder screens,
  TTS, dictation, and `ChannelClient`-based voice upload.
- [x] Add `.agents/skills/` and extend `AGENTS.md`.

## Phase 1 — Host-app integration (tiny, flag-gated)

Each item here is a 1-file edit in existing code, behind `WatchBridgeFeatureFlag`.

1. `:app/AndroidManifest.xml` — add a `<service>` block for
   `io.element.android.watchbridge.WatchBridgeListenerService` inside a `tools:node="merge"`
   boundary. **Risk: medium** (manifest merges rebase cleanly but are worth watching).
2. `:app` DI module (e.g. `io.element.android.app.di`) — add one
   `@ContributesBinding(AppScope::class) class HostElementXWatchPort @Inject constructor(...)`
   that implements `ElementXWatchPort` in terms of the existing room-list service, timeline,
   and send use-cases. **Risk: low** (new class, no renames).
3. Preferences entry — add a single `SwitchPreference` labelled "Wear OS companion"
   in the existing settings screen, bound to a new preference key
   `"watchbridge.enabled"`. **Risk: low**.
4. Application `onCreate` — `if (featureFlag && pref) dispatcher.start()`. **Risk: low**.

Every touched file will be appended to `touched-files.md` with its current upstream SHA and
risk label.

## Phase 2 — Favorites & Read (MVP cut #1)

- Host port's `favorites()` emits a list of `WatchFavoriteRoom`.
- `openRoom → timelineDelta` pipeline is wired.
- Dictate + send + text send.

## Phase 3 — Threads & Reactions (MVP cut #2)

- Host port's `threadTimeline(...)` and `sendReaction(...)` backends.
- `RoomScreen` reaction quick-bar.

## Phase 4 — Voice (MVP cut #3)

- `ChannelClient` byte reader on the phone side (new class `VoiceChannelBridge` in the host
  app — not in `:watchbridge` to avoid coupling that module to `com.google.android.gms.wearable`
  channel lifecycle APIs beyond the transport abstraction).
- Phone-side invocation of Element X's existing voice message send pipeline from the received
  bytes + `WatchVoiceDraft` metadata.
- Playback descriptor flow (`RequestPlayback` → `PlaybackReady`).

## Phase 5 — Background Hardening & Release

- Verify `WearableListenerService` wake path with the app backgrounded (manual smoke).
- Tune ack retry on the watch.
- CI: `./gradlew :watchbridge-contract:test :watchbridge:test :wearapp:lint :wearapp:assembleDebug`
  added to the weekly build workflow.

## Rollback

- Flip the preference to `false` — bridge does not start.
- Uninstall `:wearapp` APK — phone behavior is unchanged.
- Revert the four host-app edits — trivially small diffs.
