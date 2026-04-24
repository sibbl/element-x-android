# Background Behavior

## Objective

Ensure companion features work even when the phone app is **not** in the foreground.

## Rules

- Do not assume the phone app is on screen.
- Register the Wear Data Layer listener as a **manifest-declared `WearableListenerService`**, so the system wakes the process on incoming Data Layer events.
- Keep work short and non-blocking in the listener; hand off to an existing Element X scoped coroutine / work manager task.
- Respect Android background execution limits — don't hold wake locks longer than necessary.
- Surface simple retry / failure UX to the watch when the phone side is unreachable (Capability lookup returns empty).

## What we do NOT do

- Start a persistent foreground service just to keep the bridge warm (too invasive, bad for battery, user-hostile).
- Poll the homeserver from the watch.
- Pre-fetch large timelines speculatively.
