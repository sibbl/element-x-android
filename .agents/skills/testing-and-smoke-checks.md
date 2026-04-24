# Testing and Smoke Checks

## Automated

- DTO mapping (Element X domain ↔ `:watchbridge-contract` DTOs).
- Protocol (de)serialization roundtrip.
- Envelope versioning (old → new, new → old rejection path).
- Request / ack flow with a fake transport.
- Room projection (favorites, summary, timeline slice).
- Thread projection.
- Reaction send flow (idempotency on repeated `requestId`).
- Voice draft transfer (bytes integrity).
- Feature flags default to off.

## Integration

- Watch ↔ phone sync over a fake `WearableListenerService`.
- Room open.
- Background phone app handling (receiver wake-up).
- Thread fetch.
- Playback descriptor flow.
- Voice upload handoff.

## Manual Smoke Checklist

- [ ] Favorites visible on watch, groups + DMs.
- [ ] Unread indicators correct.
- [ ] Room open + read last N messages.
- [ ] Send text.
- [ ] Send via dictation.
- [ ] Send emoji.
- [ ] Send reaction.
- [ ] Open thread.
- [ ] Send thread reply.
- [ ] TTS reads a message aloud.
- [ ] Record a voice message.
- [ ] Phone sends it to Matrix.
- [ ] Play a voice message on watch.
- [ ] Behavior on missing audio route.
- [ ] Behavior with phone app backgrounded.
- [ ] Behavior with phone app force-stopped (should fail gracefully).
