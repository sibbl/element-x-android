# Touched Upstream Files

Every edit to **existing** Element X files lives here with a merge-conflict risk label and a
one-line rationale. Keep this file in sync with every PR.

| File | Risk | Rationale |
| :--- | :--- | :--- |
| `settings.gradle.kts` | low | Four `include(…)` lines for new additive modules. |

## Planned (Phase 1 — not yet applied)

| File | Risk | Rationale |
| :--- | :--- | :--- |
| `app/src/main/AndroidManifest.xml` | medium | Add `<service android:name="io.element.android.watchbridge.WatchBridgeListenerService"/>` behind a manifest merge boundary. |
| `app/src/main/kotlin/io/element/android/x/di/RootModule.kt` (or equivalent) | low | Add one `@ContributesBinding(AppScope::class)` for `ElementXWatchPort`. |
| `features/preferences/impl/...PreferencesView.kt` | low | One `SwitchPreference` for the companion. |
| `app/src/main/kotlin/io/element/android/x/ElementXApplication.kt` | low | Three-line `dispatcher.start()` call in `onCreate` behind the feature flag. |
