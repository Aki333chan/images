# AurumUI

Optional Fabric 26.2 client for the Aurum Paper plugin ecosystem.

## Runtime layout

`AurumCompanion` owns the Minecraft custom-payload transport. It detects the
client mod with a versioned handshake and gathers independent HUD panels from
the gameplay plugins. `AurumUI` renders every active panel as a compact stack
along the right edge. The current priority order is arena, party, guild.

Players without the mod never complete the handshake. AurumArena and
AurumGuilds therefore keep using their normal vanilla scoreboards. If
Companion is disabled or its heartbeat disappears, the client drops stale
panels and the server plugins restore the vanilla view.

The Fabric channel does not use Companion's HTTP server. No extra allocation,
public port or panel token is needed.

## Installation

Server (`plugins/`):

1. Replace AurumCompanion with version 0.4.0 or newer. Older Companion versions
   remain compatible with the HUD, but do not expose the object editor.
2. Replace AurumGuilds with version 0.2.0 or newer.
3. Replace AurumArena with version 1.4.0, AddonsNPC with 1.7.0 and AurumSlots
   with 1.3.0 when their administration tabs are needed.
4. Restart Paper. A reload is not recommended for protocol changes.

Client (`mods/`):

1. Minecraft Java 26.2.
2. Fabric Loader 0.19.5 or newer.
3. Fabric API for 26.2.
4. `AurumUI-0.4.0.jar`.

## Client controls

- `U` opens the single AurumUI control centre.
- Arena, party and guild panels can be hidden independently.
- Compact, normal and large scale plus three background opacity levels are
  persisted in `config/aurumui.properties` on the client.
- Administrators get Arena, NPC and Slots tabs when Companion confirms both
  `aurumui.admin` and the corresponding gameplay-plugin permission.

The key can be rebound in Minecraft's normal Controls screen.

Existing Companion configs are valid. The defaults are used when the new
`ui` section is absent. To tune the transport, copy the `ui` section from the
new default config.

## Administration architecture

The administration screen reuses Companion rather than introduce another
server plugin. Protocol 3 is separate from the read-only HUD transport:

1. The client requests a bounded list for one scope: arenas, NPCs, shops,
   buyers, offers or slot machines.
2. Companion checks `aurumui.admin`, the owning plugin permission and the
   presence of a compatible provider API.
3. The owning plugin returns immutable string fields for the cards. Private
   runtime objects and databases never cross the network.
4. A click sends a typed action id, selected object id and bounded arguments.
5. The owning plugin validates the permission, object, action and value again,
   performs the change on the server thread, persists it and returns a fresh
   snapshot. Arbitrary commands cannot be submitted by the client.
6. Stop/remove/replace operations require a separate confirmation screen.

Implemented providers:

- Arena: live state and teams, final prize pool, team limit, radius, betting,
  kit, friendly fire, boss bar, XP rewards/mode, teleport and start/stop.
- NPC: position and rotation, model/name visibility, look mode, entity type,
  pose, skin, equipment, interaction/dialogue modes and basic text settings.
- Shop/buyer: GUI opening, global promotion and a nested visual offer editor.
  Offers support price, quantity/stock, exact/material matching, bulk sale,
  per-offer promotion, item-from-hand replacement and confirmed removal.
- Slots: status, economy mode, teleport and the bet of the selected machine.
  Physical shelf/button/hopper linking intentionally remains in-world.

## Build

The Fabric client requires JDK 25:

```text
gradlew.bat build
```

The release jar is created in `build/libs/`.
