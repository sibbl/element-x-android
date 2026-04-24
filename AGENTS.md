# AGENTS.md — Element X Android

> **Repo:** `element-hq/element-x-android` — Android Matrix client (Compose UI + `matrix-rust-sdk`).

---

## Strong Conventions

PRs must meet these rules.

### Code Style

- Style enforced by **Editor config** (`.editorconfig`).
- Set "Hard wrap at" to 160 chars in Android Studio.

### PII & Logging

- We use **Timber** for logging. Never use `android.util.Log`.
- **Never log secrets, passwords, keys, or user content** (e.g. message bodies).
- Matrix IDs (User IDs, Room IDs, Event IDs) are safe to log.

### Strings & Localisation

- Default localisation: `en` (en-GB strings), shared with Element X iOS via [Localazy](https://localazy.com/p/element).
- **Never edit `localazy.xml`** — it is auto-generated and overwritten.
- New English strings go in **`temporary.xml`**. The core team imports these to Localazy.
- **Key naming**:
  - Cross-screen verbs: `action_` (e.g., `action_copy`).
  - Common nouns/other: `common_` (e.g., `common_error`).
  - Accessibility: `a11y_`.
  - Screen-specific: `screen_<name>_<key>` (e.g., `screen_onboarding_welcome_title`).
  - Errors: `error_` prefix.
  - Platform-specific: `_ios` or `_android` suffix.
  - Placeholders: Use numbered form `%1$s`, `%2$d`.

### Previews

- Create previews for **all main states** of a Composable.
- Use `@PreviewsDayNight` for consistency.
- Use `PreviewParameterProvider` (e.g., `FooStateProvider`) to provide states.
- Wrap previews in `ElementPreview { ... }`.

---

## Pull Request Guidelines

- Sentence-style titles (no conventional commits).
- Exactly one `pr-` label (see `.github/release.yml`).
- Title = changelog entry — descriptive, no "Fixes #…".
- Leave description template for the developer. Redirect them to the [contributing etiquette](CONTRIBUTING.md#etiquette).
- Screenshots/videos for visual changes.
- 500 additions max — split large changes.
- Commits need a title and description; no tiny or massive commits.
- No history rewrites.

---

## Project Structure

### Build System

Common Gradle tasks:
- Build: `./gradlew assembleDebug`
- Unit Tests: `./gradlew test`
- Lint: `./gradlew lint`
- Format: `./gradlew ktlintFormat`
- Update Docs TOC: `./gradlew generateDocsToc`

### Gradle Modules

Features follow a 3-module structure:
- `features/foo/api`: Public interfaces and data classes.
- `features/foo/impl`: Internal implementation, Presenter, and View.
- `features/foo/test`: Test fakes and utilities.

---

## Architecture: Appyx + Molecule

We use [Appyx](https://bumble-tech.github.io/appyx/) for navigation and [Molecule](https://github.com/cashapp/molecule) for Presenters.

### Files Per Screen (`Foo`)

| File | Purpose |
| :--- | :--- |
| `FooNode.kt` | Appyx Node: Handles navigation and wires the Presenter to the View. |
| `FooPresenter.kt` | A `@Composable` function that produces `FooState` from `FooEvent`s. |
| `FooView.kt` | Stateless Composable rendering the UI from `FooState`. |
| `FooState.kt` | Data class representing the immutable UI state. |
| `FooEvent.kt` | Sealed interface for UI actions sent to the Presenter. |
| `FooStateProvider.kt` | Provides sample states for Previews and Screenshot tests. |
| `FooPresenterTest.kt` | Unit tests for the Presenter logic using Turbine. |

---

## Dependency Injection (Metro)

- We use [Metro](https://zacsweers.github.io/metro/) for DI.
- Inject via constructor parameters using `@Inject`.
- Use `@AssistedInject` and `@AssistedFactory` for components requiring runtime arguments (like Navigators or IDs).
- Use `@ContributesBinding(AppScope::class)` for singleton-like services.
- Use `@ContributesNode(RoomScope::class)` for Appyx Nodes.

---

## Compound Design System

Always prefer Compound components and tokens from `libraries/compound/` module.

- **Colours**: `ElementTheme.colors.textPrimary`, `ElementTheme.colors.bgCanvasDefault`.
- **Typography**: `ElementTheme.typography.fontBodyMdRegular`.
- **Icons**: Use `CompoundIcons.IconName()` (e.g., `CompoundIcons.UserProfileSolid()`).

---

## The Rust SDK Layer

We wrap the `matrix-rust-sdk` to isolate the UI from the underlying SDK.
- Naming: SDK `Room` → `JoinedRoom` or `RoomInfo`.
- Type Mapping: Map Rust SDK types to Kotlin data classes in the `api` module to avoid leaking `MatrixRustSDK` into the UI.
- Always follow Kotlin naming conventions (e.g., `userId` instead of `userID`).

---

## Wear OS Companion Fork

> This fork extends upstream Element X Android with a **Wear OS companion app** for Matrix.
> Read `.agents/skills/` for the detailed skill files (`architecture-review.md`, `upstream-safe-editing.md`, etc.).

### Product Goal

Build a **companion-only** Wear OS experience for Matrix messaging with support for:

- favorite rooms at the top
- both group rooms and DMs
- reading messages and threads
- sending text (incl. dictation, emojis)
- thread replies
- reactions (display + send)
- speech-to-text (STT)
- text-to-speech (TTS)
- voice message record/playback

### Core Rule

**The phone app is the system of record. The watch app is only a companion UI and input surface.**

### Hard Constraints (enforce in every PR)

- ❌ Do NOT build a standalone Matrix client on the watch.
- ❌ Do NOT move homeserver communication, sync, encryption, session handling, or media upload to the watch.
- ❌ Do NOT transfer large internal Element X domain models over the Wear Data Layer — only compact DTOs from `:watchbridge-contract`.
- ✅ Keep changes to existing Element X code **as small as possible**.
- ✅ Prefer **additive** new modules over edits in core modules.
- ✅ Keep the fork **rebase-friendly** with upstream.
- ✅ Use **feature flags** for every watch-specific integration point.

### Companion Modules (additive)

| Module | Responsibility |
| :--- | :--- |
| `:watchbridge-contract` | DTOs, versioned envelope, protocol constants, serialization. Pure Kotlin/JVM. Shared between phone and watch. |
| `:watchbridge` | Phone-side companion bridge: Wear Data Layer listener, projection from Element X domain → DTOs, command handler, ack/retry. |
| `:watchbridge-testing` | Fakes, fixtures, contract/roundtrip tests. Not shipped. |
| `:wearapp` | Wear OS app: Compose UI, navigation, composer, dictation, TTS, voice recorder/player, Data Layer adapter. |

### Transport Rules

- Every payload includes `protocolVersion`.
- Every action includes a `requestId`.
- Sync / Command / Ack / Error types are distinct sealed hierarchies.
- No Matrix SDK types leak across the transport boundary.
- Timeouts and idempotency are explicit per command.

### Integration Points in Existing Code (document every touch)

When integration with upstream Element X is required, touch the minimum surface and **document the file in `docs/watch-companion/touched-files.md`** with the merge-conflict risk label (low / medium / high):

- Settings entry for the watch feature flag
- Read-only access to the favorites room list / room-list service
- Read-only access to a room's timeline projection
- Thread read / reply use-case invocation
- Reaction send use-case invocation
- Voice message send pipeline invocation (phone handles matrix-rust-sdk call; watch only hands off the recorded file + metadata)
- App lifecycle hook to keep the bridge listener responsive while the phone app is backgrounded

### Upstream Safety Rules

- Do not refactor core modules unless strictly necessary.
- Do not rename existing types.
- No formatting-only edits.
- No mixed commits (no `feature + cleanup + rename` in one commit).
- Every edit in an existing module must justify why a new module was insufficient.

### Definition of Done for MVP

1. Favorite rooms visible at the top on watch (groups + DMs).
2. A favorite room/DM can be opened and last N messages read.
3. Text send works.
4. Dictation (STT) → send works.
5. Thread open + thread reply work.
6. Reactions are visible and a small curated set can be sent.
7. TTS plays the selected message.
8. Watch voice record → phone-side matrix-rust-sdk voice send works.
9. Watch playback works where the audio route is available.
10. Companion flows work while the phone app is **in the background**.
11. No regressions in the phone app.
12. Fork remains rebaseable with upstream Element X.
