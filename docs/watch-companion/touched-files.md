# Touched Upstream Files

Every edit to **existing** Element X files lives here with a merge-conflict risk label and a
one-line rationale. Keep this file in sync with every PR.

| File | Risk | Rationale |
| :--- | :--- | :--- |
| `settings.gradle.kts` | low | Four `include(…)` lines for new additive modules. |
| `app/build.gradle.kts` | low | Add GPlay-only dependency on `:watchbridge` so the debug phone APK can advertise and receive Wear Data Layer bridge events. |
| `app/src/main/kotlin/io/element/android/x/di/AppGraph.kt` | low | Expose existing app/session services required by the GPlay-only WatchBridge runtime. |
| `app/src/gplay/AndroidManifest.xml` | medium | Add GPlay-only Wear bridge capability metadata and listener service declaration. |
| `app/src/gplay/kotlin/io/element/android/x/initializer/WatchBridgeCapabilityInitializer.kt` | low | Dynamically registers the phone bridge capability at process start and treats already-registered static capabilities as benign. |
| `app/src/gplay/kotlin/io/element/android/x/watchbridge/ElementXWatchBridgeListenerService.kt` | low | Route Wear Data Layer commands to the active Element X session bridge dispatcher. |
| `app/src/gplay/kotlin/io/element/android/x/watchbridge/ElementXWatchBridgeRuntime.kt` | low | Restore the active Matrix session and project favorites plus recent rooms to compact watch DTOs. |
| `app/src/gplay/res/values/wear.xml` | low | Add the GPlay-only static Wear Data Layer phone capability resource. |
| `wearapp/src/main/AndroidManifest.xml` | low | Declare the watch capability and use a local launcher icon that parses correctly on Wear OS. |
| `wearapp/src/main/res/values/wear.xml` | low | Add the static Wear Data Layer watch capability resource. |

## Planned (Phase 1 — not yet applied)

| File | Risk | Rationale |
| :--- | :--- | :--- |
| `app/src/main/AndroidManifest.xml` | medium | Add `<service android:name="io.element.android.watchbridge.WatchBridgeListenerService"/>` behind a manifest merge boundary. |
| `app/src/main/kotlin/io/element/android/x/di/RootModule.kt` (or equivalent) | low | Add one `@ContributesBinding(AppScope::class)` for `ElementXWatchPort`. |
| `features/preferences/impl/...PreferencesView.kt` | low | One `SwitchPreference` for the companion. |
| `app/src/main/kotlin/io/element/android/x/ElementXApplication.kt` | low | Three-line `dispatcher.start()` call in `onCreate` behind the feature flag. |
