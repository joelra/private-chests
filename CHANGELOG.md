# Changelog

## 1.4.0 — 2026-08-17

This release closes an exploit that let players remove other players' locks, fixes several protection gaps, and — for the first time — ships jars for Minecraft 1.21.11 and 26.1.x alongside 26.2.

### Security fixes

- **Closed an exploit that could remove someone else's lock from another dimension.** A chest placed at the same coordinates in the Nether or End previously shared the lock with the overworld chest, and interacting with it could delete the real lock entirely. Locks now only apply in their own dimension; existing locks migrate automatically.
- Sneak-placing from the off-hand no longer bypasses the rules against placing signs on, or chests next to, someone else's locked container.
- Setting `adminPermissionLevel` to 0 no longer makes every player an admin. The valid range is now 1-4; out-of-range values reset to the default of 3 on startup.
- A broken or empty config file no longer crashes the server on startup — defaults are used instead.

### Fixes

- Breaking your own protection sign, or a banned player's container, now fully removes the lock. Previously a leftover record kept silently protecting that spot from explosions.
- Extending a locked single chest into a double chest now correctly extends the protection to the new half.

### Changes

- Locked containers can no longer be filled through droppers (hoppers and hopper minecarts were already blocked).

### Compatibility notes

- Three jars, one per Minecraft version — install the one matching your server:
  - `private-chests-1.4.0+1.21.11.jar` — Minecraft 1.21.11, Java 21+
  - `private-chests-1.4.0+26.1.1.jar` — Minecraft 26.1.x, Java 25+
  - `private-chests-1.4.0+26.2.jar` — Minecraft 26.2, Java 25+
- Requires Fabric Loader 0.19.3+ and Fabric API. Server-side only — players do not need to install anything.
- Existing configs and lock data work unchanged. Locks created before 1.4.0 are treated as overworld locks (the only place they could have been created).
- If your config intentionally set `adminPermissionLevel: 0`, note it will be reset to 3 — every player having admin bypass is no longer supported.

## 1.3.0

- Ported to Minecraft 26.2.
- Added `[public]` sign support: anyone can open, only the owner or an admin can manage.
- Added dormant protection signs that reactivate when the primary protection is removed.

## 1.1.0

- Ported to Minecraft 26.1.1.
- Consolidated banned-owner handling; offline owners' ban status is now checked correctly.

## 1.0.0

- Initial release for Minecraft 1.21.11: sign-based chest and barrel protection with owner-based access control, double chest support, hopper blocking, and explosion/fire protection.
