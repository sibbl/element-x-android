# Thread Guidelines

## Objective

Support a narrow but useful thread experience on watch.

## MVP Scope

- Show a thread indicator on timeline items with `hasThread = true`.
- Open a thread view from the indicator.
- Read a limited number of recent replies (e.g. last 20).
- Reply to the thread (thread-root-scoped send).

## Avoid

- Full parity with phone thread UX.
- Complex jump navigation into arbitrary events.
- Broad thread refactors in existing code.
- Advanced thread management (edit, redact flows) for MVP.

## Data Shape

- `WatchThreadSummary` for the indicator.
- `WatchThreadItem` for the individual replies (shape mirrors `WatchTimelineItem` but thread-scoped).
- Thread sends use `WatchSendRequest` with `threadRootEventId` set.
