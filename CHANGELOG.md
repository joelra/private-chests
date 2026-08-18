# Changelog

## 1.4.0

### Security fixes

- **Locks are now dimension-aware.** Previously a chest at matching coordinates in another dimension resolved to the same lock, and interacting with it could delete the real lock entirely. Existing saves migrate automatically (pre-existing locks are treated as overworld).
- Placement restrictions (signs on locked containers, chests next to locked chests) can no longer be bypassed from the off-hand.
- `adminPermissionLevel` is now validated to 1-4; a value of 0 no longer silently makes every player an admin.
- A malformed or empty config file no longer crashes the server on startup.

### Fixes

- Breaking a protection sign (as owner or admin) or a banned owner's container now removes the lock record instead of leaving phantom explosion/fire protection behind.
- Placing a second chest to extend a locked chest now correctly extends the lock to cover both halves.

### Features

- Droppers can no longer insert items into locked containers.
- One release now ships jars for Minecraft 1.21.11, 26.1.x, and 26.2 — pick the jar matching your server version (`+1.21.11` needs Java 21+, `+26.1.1`/`+26.2` need Java 25+).

### Internal

- Added 33 unit tests and 25 in-world game tests covering all container types, access control, destruction protection, placement restrictions, and automation blocking; CI runs both suites for every supported Minecraft version.
- Multi-version builds via Stonecutter from a single codebase.

## 1.3.0

- Ported to Minecraft 26.2.
- Added `[public]` sign support: anyone can open, only the owner or an admin can manage.
- Added dormant protection signs that reactivate when the primary protection is removed.

## 1.1.0

- Ported to Minecraft 26.1.1.
- Consolidated banned-owner handling; offline owners' ban status is now checked correctly.

## 1.0.0

- Initial release for Minecraft 1.21.11: sign-based chest and barrel protection with owner-based access control, double chest support, hopper blocking, and explosion/fire protection.
