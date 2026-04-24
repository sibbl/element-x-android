# Reactions Guidelines

## Objective

Support visible and sendable reactions in a watch-appropriate way.

## MVP Scope

- Display reactions compactly on timeline items (`WatchReactionSummary` list).
- Allow sending reactions from a **limited predefined set** (e.g. 👍 ❤️ 😂 🎉 🙏 👀).
- Optimize for one-tap interaction.

## Avoid

- Full emoji picker in early MVP.
- Broad timeline-rendering refactors just for reaction richness.

## Sending

- Use `WatchReactionSendRequest { requestId, roomId, eventId, reactionKey }`.
- Phone side must be idempotent: reposting the same `requestId` must not double-send.
