# Touched Upstream Files

Every edit to **existing** Element X files lives here with a merge-conflict risk label and a
one-line rationale. Keep this file in sync with every PR.

| File | Risk | Rationale |
| :--- | :--- | :--- |
| `settings.gradle.kts` | low | Four `include(…)` lines for new additive modules. |
| `app/build.gradle.kts` | low | Add GPlay-only dependency on `:watchbridge` so the debug phone APK can advertise and receive Wear Data Layer bridge events. |
| `app/src/main/kotlin/io/element/android/x/di/AppGraph.kt` | low | Expose existing app/session services required by the GPlay-only WatchBridge runtime. |
| `app/src/gplay/AndroidManifest.xml` | medium | Add the GPlay-only Wear bridge capability metadata plus listener service support for both message and channel-based voice draft delivery. |
| `app/src/gplay/kotlin/io/element/android/x/initializer/WatchBridgeCapabilityInitializer.kt` | low | Dynamically registers the phone bridge capability at process start and treats already-registered static capabilities as benign. |
| `app/src/gplay/kotlin/io/element/android/x/watchbridge/ElementXWatchBridgeListenerService.kt` | medium | Route both Wear message commands and channel-uploaded voice drafts to the active Element X session bridge dispatcher. |
| `app/src/gplay/kotlin/io/element/android/x/watchbridge/ElementXWatchBridgeRuntime.kt` | medium | Restore the active Matrix session, project watch DTOs, derive read-marker anchors, and materialize bounded image previews for the watch media cache. |
| `app/src/gplay/res/values/wear.xml` | low | Add the GPlay-only static Wear Data Layer phone capability resource. |
| `wearapp/src/main/AndroidManifest.xml` | medium | Register both conversation tiles and listener support for channel-based voice draft uploads on the watch. |
| `wearapp/src/main/res/values/wear.xml` | low | Add the static Wear Data Layer watch capability resource. |
| `wearapp/src/main/kotlin/io/element/android/wearapp/WearApp.kt` | low | Refresh both recent and favorite conversation tiles and expose the upgraded bridge client behavior at watch app startup. |
| `wearapp/src/main/kotlin/io/element/android/wearapp/ui/WearMainActivity.kt` | low | Coordinate watch-only navigation, fix warm-start tile deep-links, register the image viewer route, and keep transient error/voice flows intact. |
| `wearapp/src/main/kotlin/io/element/android/wearapp/ui/room/RoomScreen.kt` | medium | Drive room loading/error handling, surface cached media previews, and pass reply-aware scroll requests plus room-context voice launches from the live room timeline. |
| `wearapp/src/main/kotlin/io/element/android/wearapp/ui/room/MessageDetailScreen.kt` | low | Send actual Matrix replies from message detail, surface cached image previews, and navigate into the full-screen viewer while restoring the right scroll position. |
| `wearapp/src/main/kotlin/io/element/android/wearapp/ui/thread/ThreadScreen.kt` | medium | Treat empty threads as a real state, keep the latest thread item fields, and share the media-aware room/thread timeline surface. |
| `wearapp/src/main/kotlin/io/element/android/wearapp/ui/voice/VoiceRecorderActivity.kt` | medium | Replace the placeholder recorder with a centered record/cancel/send UX, permission handling, live animation, and bridge-backed voice upload. |
| `wearapp/src/main/kotlin/io/element/android/wearapp/audio/VoiceRecorder.kt` | low | Harden watch recording stop/cancel behavior and expose live amplitude for the recording animation. |
| `watchbridge-contract/src/main/kotlin/io/element/android/watchbridge/contract/WatchCommand.kt` | medium | Add explicit reply metadata to watch voice drafts so voice replies can target the correct Matrix event. |
| `watchbridge-contract/src/main/kotlin/io/element/android/watchbridge/contract/WatchDataPaths.kt` | medium | Add dedicated media-preview Data Layer paths alongside the draft channel paths so event-scoped watch images can sync and expire independently. |
| `watchbridge/src/main/kotlin/io/element/android/watchbridge/ElementXWatchPort.kt` | low | Extend the phone-side bridge port to distinguish plain messages, thread messages, Matrix replies, and per-event image preview materialization. |
| `watchbridge/src/main/kotlin/io/element/android/watchbridge/WatchBridgeDispatcher.kt` | medium | Forward reply targets, pair voice draft metadata with uploaded audio bytes, publish removal-aware room/thread deltas, and sync bounded image previews with TTLs. |
| `wearapp/src/main/kotlin/io/element/android/wearapp/bridge/WearBridgeClient.kt` | medium | Cache thread deltas, keep the latest timeline item fields, maintain in-memory media preview state, prune deleted previews, and refresh both tile providers. |
| `wearapp/src/main/kotlin/io/element/android/wearapp/ui/room/RoomView.kt` | medium | Replace the initial-only scroll jump with read-marker-aware opening, immediate bottom jumps, and media-aware timeline rows. |
| `wearapp/src/main/kotlin/io/element/android/wearapp/tile/RecentContactsTileService.kt` | medium | Split the tile implementation into recent/favorite conversation modes, re-center the layout, and deep-link tile avatars directly into room timelines. |
| `wearapp/src/main/res/values/temporary.xml` | low | Add copy for split conversation tiles, watch voice recorder flow, and the new watch-side image placeholder/full-screen error states. |
| `watchbridge-contract/src/main/kotlin/io/element/android/watchbridge/contract/WatchProjections.kt` | medium | Add additive watch DTO metadata for cached media previews and read-marker anchoring without leaking Matrix internals across the transport boundary. |
| `watchbridge-contract/src/main/kotlin/io/element/android/watchbridge/contract/WatchSync.kt` | medium | Add event-scoped media preview sync payloads so watch image bytes travel separately from timeline DTOs and can expire independently. |
| `watchbridge-contract/src/main/kotlin/io/element/android/watchbridge/contract/WatchBridgeSerialization.kt` | low | Register the new additive media preview sync payload in the shared watch/phone serializer. |
| `wearapp/src/main/kotlin/io/element/android/wearapp/bridge/WearBridgeCacheStore.kt` | medium | Persist event-scoped media previews on the watch with one-week TTL, room/timeline invalidation, and bounded quota eviction for offline viewing. |
| `wearapp/src/main/kotlin/io/element/android/wearapp/ui/room/RoomTimelineState.kt` | low | Keep room timeline caches biased toward the latest item fields so read-marker anchors and media metadata survive incremental deltas. |
| `wearapp/src/main/kotlin/io/element/android/wearapp/ui/room/TimelineItemView.kt` | medium | Render cached image previews with placeholder states in timeline/detail surfaces while preserving existing long-press, reaction, and voice affordances. |
| `wearapp/src/main/kotlin/io/element/android/wearapp/ui/room/MessageDetailView.kt` | low | Allow message detail to render cached image previews and open them into the watch-only full-screen image viewer. |

## Planned (Phase 1 — not yet applied)

| File | Risk | Rationale |
| :--- | :--- | :--- |
| `app/src/main/AndroidManifest.xml` | medium | Add `<service android:name="io.element.android.watchbridge.WatchBridgeListenerService"/>` behind a manifest merge boundary. |
| `app/src/main/kotlin/io/element/android/x/di/RootModule.kt` (or equivalent) | low | Add one `@ContributesBinding(AppScope::class)` for `ElementXWatchPort`. |
| `features/preferences/impl/...PreferencesView.kt` | low | One `SwitchPreference` for the companion. |
| `app/src/main/kotlin/io/element/android/x/ElementXApplication.kt` | low | Three-line `dispatcher.start()` call in `onCreate` behind the feature flag. |
