# Release and Rebase

## Objective

Keep the fork **releasable weekly** and **syncable** with upstream Element X.

## Git Topology

- `origin` → this fork (`<your-gh-user>/element-x-android`)
- `upstream` → `element-hq/element-x-android`

## Weekly Workflow

1. `git fetch upstream`
2. Rebase the `develop` branch onto `upstream/develop` (or merge early if rebase conflicts get too large).
3. Resolve conflicts — known hotspots are listed in `docs/watch-companion/touched-files.md`.
4. Run `./gradlew ktlintFormat detekt test lint`.
5. Run `./gradlew :wearapp:assembleDebug :app:assembleDebug`.
6. Sign APKs (CI secret `SIGNING_KEYSTORE`).
7. Smoke-test on phone + wear device pair.
8. Publish nightly channel.

## Required Notes per Release

- Rebase notes (commits brought in).
- Conflict log.
- Known issues.
- Wear-specific release notes (if any behavior changed).

## Feature Flag Policy

- Every watch integration point touches existing code behind a build-config or preference flag.
- The flag default stays `false` until the corresponding MVP item is fully verified.
