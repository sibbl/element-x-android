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
| `app/src/gplay/kotlin/io/element/android/x/watchbridge/ElementXWatchBridgeRuntime.kt` | medium | Restore the active Matrix session, project watch DTOs, and hand uploaded watch voice drafts into the existing Matrix voice-send pipeline. |
| `app/src/gplay/res/values/wear.xml` | low | Add the GPlay-only static Wear Data Layer phone capability resource. |
| `wearapp/src/main/AndroidManifest.xml` | medium | Register both conversation tiles and listener support for channel-based voice draft uploads on the watch. |
| `wearapp/src/main/res/values/wear.xml` | low | Add the static Wear Data Layer watch capability resource. |
| `wearapp/src/main/kotlin/io/element/android/wearapp/WearApp.kt` | low | Refresh both recent and favorite conversation tiles and expose the upgraded bridge client behavior at watch app startup. |
| `wearapp/src/main/kotlin/io/element/android/wearapp/ui/WearMainActivity.kt` | low | Coordinate watch-only navigation, tile deep-links into room timelines, transient errors, and smarter voice-launch context. |
| `wearapp/src/main/kotlin/io/element/android/wearapp/ui/room/RoomScreen.kt` | medium | Drive room loading/error handling plus reply-aware scroll requests, Matrix reply actions, and room-context voice launches from the live room timeline. |
| `wearapp/src/main/kotlin/io/element/android/wearapp/ui/room/MessageDetailScreen.kt` | low | Send actual Matrix replies from message detail, restore the right scroll position, and launch voice replies with room context. |
| `wearapp/src/main/kotlin/io/element/android/wearapp/ui/thread/ThreadScreen.kt` | medium | Treat empty threads as a real state and launch thread voice replies with full room/thread context. |
| `wearapp/src/main/kotlin/io/element/android/wearapp/ui/voice/VoiceRecorderActivity.kt` | medium | Replace the placeholder recorder with a centered record/cancel/send UX, permission handling, live animation, and bridge-backed voice upload. |
| `wearapp/src/main/kotlin/io/element/android/wearapp/audio/VoiceRecorder.kt` | low | Harden watch recording stop/cancel behavior and expose live amplitude for the recording animation. |
| `watchbridge-contract/src/main/kotlin/io/element/android/watchbridge/contract/WatchCommand.kt` | medium | Add explicit reply metadata to watch voice drafts so voice replies can target the correct Matrix event. |
| `watchbridge-contract/src/main/kotlin/io/element/android/watchbridge/contract/WatchDataPaths.kt` | medium | Add draft-specific channel paths so uploaded watch audio bytes can be paired with the matching voice draft metadata. |
| `watchbridge/src/main/kotlin/io/element/android/watchbridge/ElementXWatchPort.kt` | low | Extend the phone-side bridge port to distinguish plain messages, thread messages, and Matrix replies. |
| `watchbridge/src/main/kotlin/io/element/android/watchbridge/WatchBridgeDispatcher.kt` | medium | Forward reply targets, pair voice draft metadata with uploaded audio bytes, and publish empty thread deltas when thread content has not arrived yet. |
| `wearapp/src/main/kotlin/io/element/android/wearapp/bridge/WearBridgeClient.kt` | medium | Cache thread deltas, refresh both tile providers, and upload watch voice drafts over the Wear channel transport. |
| `wearapp/src/main/kotlin/io/element/android/wearapp/ui/room/RoomView.kt` | medium | Replace the initial-only scroll jump with sticky-bottom behavior plus targeted or bottom-forcing navigation scroll restoration. |
| `wearapp/src/main/kotlin/io/element/android/wearapp/tile/RecentContactsTileService.kt` | medium | Split the tile implementation into recent/favorite conversation modes, re-center the layout, and deep-link tile avatars directly into room timelines. |
| `wearapp/src/main/res/values/temporary.xml` | low | Add copy for split conversation tiles, the upgraded watch voice recorder flow, and the watch-side error states used by the new UX. |

## Planned (Phase 1 — not yet applied)

| File | Risk | Rationale |
| :--- | :--- | :--- |
| `app/src/main/AndroidManifest.xml` | medium | Add `<service android:name="io.element.android.watchbridge.WatchBridgeListenerService"/>` behind a manifest merge boundary. |
| `app/src/main/kotlin/io/element/android/x/di/RootModule.kt` (or equivalent) | low | Add one `@ContributesBinding(AppScope::class)` for `ElementXWatchPort`. |
| `features/preferences/impl/...PreferencesView.kt` | low | One `SwitchPreference` for the companion. |
| `app/src/main/kotlin/io/element/android/x/ElementXApplication.kt` | low | Three-line `dispatcher.start()` call in `onCreate` behind the feature flag. |
