# Room and Favorites UX

## Objective

Prioritize fast watch access to important conversations.

## Rules

- Favorites are **always** the first list section.
- Support both group rooms and DMs — no implicit group-only assumptions.
- Keep per-room info compact: avatar, display name, unread count / mention dot, last activity timestamp, short preview.
- Do not overload the screen with full room-list complexity.
- Tappable areas must respect Wear OS touch-target sizing.

## Open Area Below Favorites

Three candidates — prepare architecture for all, ship (1) for MVP:

1. **Favorites only** (MVP preference).
2. Favorites + small "Recent" list (last N rooms with activity in last 24h).
3. Favorites + unread / recent hybrid (unreads first, then recent).

## MVP Preference

Stay conservative. Favorites only. Keep the rest behind a feature flag.
