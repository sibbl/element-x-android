# Architecture Review

## Objective

Review every proposed Wear OS companion change against the companion-only architecture before it lands.

## Validate

- Does the phone remain the **system of record**?
- Is any Matrix server / homeserver / sync / E2EE logic being moved to the watch? (Must be "no".)
- Are existing Element X core modules changed only minimally?
- Can the change live in `:wearapp`, `:watchbridge`, or `:watchbridge-contract` instead of touching existing code?
- Are DTO boundaries respected? (No Matrix SDK types cross `:watchbridge-contract`.)
- Is the change behind a feature flag?

## Reject or revise if

- The watch starts acting like a second Matrix client.
- Encryption, device-keys, or session concerns move to the watch.
- Large internal domain models are transferred directly.
- Broad refactors are proposed without strong justification.
- Transport messages are ad-hoc (missing `protocolVersion` / `requestId`).

## Output of a review

Each review should state:

1. Green / Yellow / Red verdict.
2. List of existing files touched and merge-conflict risk label (low / medium / high).
3. Concrete follow-up items if Yellow.
