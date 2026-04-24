# Upstream Safe Editing

## Objective

Preserve long-term rebaseability with `element-hq/element-x-android` upstream.

## Rules

- Prefer **additive** modules over modifications.
- Prefer **adapters** over refactors.
- Do not rename existing types.
- Do not perform formatting-only changes.
- Isolate watch-specific logic in `:watchbridge*` / `:wearapp`.
- Gate every integration point with a feature flag.
- Document every touched existing file in `docs/watch-companion/touched-files.md`.

## For every edit in an existing module

- Explain why a new module is not enough.
- Explain the smallest viable patch.
- Label merge-conflict risk as **low**, **medium**, or **high**.
- Keep the diff under ~20 lines per file if at all possible.
- Never mix refactors with feature additions.

## Forbidden in the same commit

- Feature change + formatting change.
- Feature change + rename.
- Feature change + dependency upgrade.
