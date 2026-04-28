# Wear OS Companion Docs

- [architecture.md](architecture.md) — high-level design, module layout, transport, and
  answers to the open questions from the spec.
- [patch-plan.md](patch-plan.md) — phased, PR-sized implementation plan.
- [touched-files.md](touched-files.md) — registry of every edit to existing Element X files
  with merge-conflict risk labels.

Recent fork behavior highlights:

- Two watch tiles are exposed: **Recent conversations** and **Favorite conversations**.
- Tapping a tile avatar now deep-links straight into the corresponding watch room timeline.
- Room/message/thread flows preserve Matrix-native reply context and smarter return scroll state.
- Watch voice recording uses an explicit record → cancel/send flow and uploads audio to the phone,
  where the existing Matrix voice-send pipeline remains the system of record.
