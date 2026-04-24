# Wear Companion Protocol

## Objective

Keep watch-phone communication **explicit**, **small**, and **versioned**.

## Rules

- Every payload carries `protocolVersion` (integer, monotonically increasing).
- Every action carries a `requestId` (UUID string).
- Distinct sealed hierarchies for `Sync`, `Command`, `Ack`, `Error`, `Result`.
- Use stable DTOs from `:watchbridge-contract` only.
- Never expose internal Element X / matrix-rust-sdk models.
- Include timeout and idempotency strategy per command.
- Backward compatibility: old clients must at least be able to parse `protocolVersion` and emit a graceful "unsupported" error.

## Checklist

- [ ] `protocolVersion` present
- [ ] `payloadType` explicit enum
- [ ] `requestId` present
- [ ] DTO size acceptable (< 8 KB target, < 100 KB hard limit per Wear Data Layer norms)
- [ ] No server-only or encryption-only details leak to the watch
- [ ] Timeout strategy documented
- [ ] Idempotency behavior documented

## Transports

- Small messages → `MessageClient` (fire-and-forget, with ack envelope).
- Small snapshots & invalidations → `DataClient` (keyed by `/watchbridge/…` paths).
- Voice draft audio files → `ChannelClient` (streamed).
- Capabilities (phone app present / supported) → `CapabilityClient`.
