# Private Chests

A server-side Minecraft mod for protecting chests and barrels using wall signs with `[private]` and `[public]` markers.

[![License: LGPL v3](https://img.shields.io/badge/License-LGPL%20v3-blue.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-green.svg)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Mod%20Loader-Fabric-orange.svg)](https://fabricmc.net/)

## Features

- **Sign-Based Protection**: Place a wall sign on any chest or barrel with `[private]` or `[public]` on the first line (front or back)
- **Multi-User Access**: Add usernames on remaining lines to grant access (comma-separated or line-by-line)
- **Two-Sided Signs**: Use both front and back of signs
- **Double Chest Support**: Automatically handles double chests and chest expansion
- **Comprehensive Protection**:
  - Prevents unauthorized opening
  - Blocks chest placement next to locked containers (non-owners)
  - Prevents additional signs on locked containers
  - Blocks hopper and hopper-minecart extraction and insertion
  - Protects against TNT explosions
  - Prevents fire spread
- **Bedrock Compatible**: Full support for Bedrock players via Floodgate/Geyser
- **Admin Tools**: Commands for managing locks and viewing protection info
- **Server-Side Only**: No client mod required

## Installation

### Requirements
- Minecraft 26.2
- Fabric Loader 0.19.3+
- Fabric API
- Java 25+

### Steps
1. Download the latest release from [Releases](https://github.com/joelra/private-chests/releases)
2. Place the `.jar` file in your server's `mods` folder
3. Start the server

## Usage

### Creating a Protected Container

1. Place a chest or barrel
2. Attach a wall sign to it
3. On the first line (front or back of the sign), write `[private]` or `[public]` (case-insensitive)
4. For `[private]`, add usernames on the remaining lines to grant access. For example:
   ```
   [private]
   PlayerName
   AnotherUser,User2
   ```

### Sign Format Rules

- `[private]` and `[public]` must be on line 1 only (exact match, case-insensitive)
- Can be on front OR back of sign
- Usernames on lines 2-4 of front and back
- Supports comma-separated names: `Player1, Player2` or `Player1,Player2`
- Owner automatically has access (don't need to list yourself)

### Managing Access

**Edit existing sign**: Only the owner or admin can edit the controlling protection sign

**Remove protection**: Remove the active protection marker from both sides of the sign

**Break chest**: Owners can break their locked chest directly (auto-removes lock)

### Public containers

- `[public]` lets anyone open the container
- `[public]` still keeps the container protected from block breaking, explosions, and fire
- Only the sign owner or an admin can edit or remove a `[public]` sign
- Extra protection signs placed on an already-protected container stay dormant until the current protection is gone and that sign's owner reactivates it

## Player Commands

### `/lock`

Available to every player:

```
/lock list
```
List all containers you have locked, with their mode and allowed users.

```
/lock info <x> <y> <z>
```
Show details about a lock. Only the lock's owner (or an admin) may view it.

```
/lock transfer <player> <x> <y> <z>
```
Transfer ownership of one of your locks to another (online) player.

## Admin Commands

All commands require admin permission level 3 (configurable).

### `/private_chests` (alias: `/pchests`)

**List all locks:**
``` 
/private_chests list
/private_chests list public
/private_chests list private
```

**List locks in area:**
``` 
/private_chests list_in_area [radius]
/private_chests list_in_area [radius] public
/private_chests list_in_area [radius] private
```
Radius in chunks (default: 1 = 2x2 chunks). Max: 10.

**Get lock info:**
```
/private_chests info <x> <y> <z>
```
Shows owner, allowed users, and timestamps.

**Remove lock:**
```
/private_chests unlock <x> <y> <z>
```

**Cleanup dangling records:**
```
/private_chests cleanup
```
Removes stale active locks and stale dormant protection-sign records in loaded chunks.

## Configuration

Config file: `config/private-chests.json`

```json
{
  "floodgatePrefix": ".",
  "adminPermissionLevel": 3,
  "listMaxEntries": 50,
  "listPreviewEntries": 20,
  "disableProtectionIfOwnerBanned": true,
  "maxLocksPerPlayer": 0
}
```

### Options

- **floodgatePrefix**: Prefix for Bedrock players (default: `.`)
- **adminPermissionLevel**: Permission level to bypass locks and use commands (1-4, default: 3)
- **listMaxEntries**: Max locks shown in `/list` before abbreviating (default: 50)
- **listPreviewEntries**: Number shown when abbreviated (default: 20)
- **disableProtectionIfOwnerBanned**: Remove protection if owner is banned (default: true)
- **maxLocksPerPlayer**: Maximum containers a single player may lock, 0 = unlimited (default: 0)

Invalid values are auto-corrected on startup.

## Bedrock Support

Fully compatible with Floodgate/Geyser for Bedrock players:
- Bedrock players can create and use private chests
- Configure `floodgatePrefix` to match your Floodgate settings
- Default prefix is `.` (e.g., `.BedrockPlayer`)

## Technical Details

- **Server-side only**: No client mod needed
- **Data persistence**: Locks saved to `world/data/private_chests.dat`
- **Dimension-aware**: Locks are tracked per dimension; matching coordinates in another dimension are unrelated
- **Performance**: Packet-level interception and caching

## Known Limitations

- **Access is granted by username, not UUID.** If a listed player changes their Minecraft name and someone else later claims the old name, that person would match the sign entry. Review allowed-user lists after name changes.
- **Underscores match spaces.** To support Bedrock names (which may contain spaces), `_` and ` ` are treated as equal when matching sign entries, so `John_Doe` (Java) and `John Doe` (Bedrock) cannot be distinguished.

## Known Issues

Due to Minecraft's client-side prediction system, some visual glitches may occur when unauthorized actions are blocked:

### Sign Text Disappearing
When a player attempts to break someone else's private sign:
- The sign remains intact (protected on server)
- The sign text may disappear on the client
- **Fix**: Left-click or right-click the sign to restore the text display
- Sign data is always preserved on the server; this is purely visual

### Item "Disappearing" When Placing Blocks
When attempting to place blocks on/near locked containers:
- The block placement is denied by the server
- The item may temporarily disappear from the hotbar
- **Fix**: Interact with that hotbar slot (switch to it, or move the item)
- The item is never lost; it's still in your inventory

These are Minecraft client limitations and do not affect functionality or data integrity. I'm sure there's ways to solve this, but it requires further investigations.

## License

This project is licensed under the GNU Lesser General Public License v3.0 - see the [LICENSE](LICENSE) file for details.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

### Development Setup

1. Clone the repository
2. Open in IntelliJ IDEA or your preferred IDE
3. Run `./gradlew genSources` to generate Minecraft sources
4. Use the included run configurations for testing

### Reporting Issues

Found a bug? [Open an issue](https://github.com/joelra/private-chests/issues) with:
- Minecraft version
- Mod version
- Steps to reproduce
- Server logs (if applicable)

## Acknowledgments

Built with:
- [Fabric](https://fabricmc.net/) - Modding toolchain
- [Fabric API](https://github.com/FabricMC/fabric) - Essential hooks and API

---

**Server admins**: Secure your community's belongings with Private Chests!
