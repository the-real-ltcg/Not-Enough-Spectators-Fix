# Not Enough Spectators (Fix / 26.2 port)

Share your singleplayer or multiplayer gameplay by letting others **spectate your game** — a
26.2 port of [Cheezer1656/NotEnoughSpectators](https://github.com/Cheezer1656/NotEnoughSpectators)
with an **embedded, opt-in cross-version layer** (ViaVersion) so spectators can *attempt* to join
from other Minecraft versions.

> **Fabric · Minecraft 26.2 · Java 25**

## Downloads

- **[Modrinth](https://modrinth.com/mod/not-enough-spectators-fix)** (recommended)
- **[GitHub Releases](../../releases)**
- **[CurseForge](https://www.curseforge.com/minecraft/mc-mods/not-enough-spectators-memory-leak-fix)**

## Companion mod

Prefer two separate mods over the all-in-one embedded build? The cross-version layer is also
available as a standalone companion — **[NES ViaBridge](https://github.com/the-real-ltcg/NES-ViaBridge)**
([Modrinth](https://modrinth.com/mod/nes-via-bridge)) — to pair with a *plain* NES build.

---

## What this fork adds

- **Ported from Minecraft 1.21.8 (Yarn) to 26.2 (official Mojang mappings).** Every source file,
  the access widener, and the mixins were migrated; the network-pipeline mixin was rewritten against
  the real 26.2 `Connection` class.
- **Embedded ViaVersion stack** (ViaVersion + ViaBackwards + ViaRewind, fat-jarred in). NES boots it
  automatically and splices Via's translation handlers into each spectator connection, so it behaves
  like a backend server with ViaVersion installed.
- **`/nes via`** command to check cross-version status and see connected spectators + their versions.

## Install

Drop the jar from [Releases](../../releases) into your Fabric `mods/` folder, together with
**Fabric API**, on a **Minecraft 26.2** profile. Only the **host** needs the mod.

## Usage

| Command | What it does |
|---|---|
| `/nes share [localPort]` | Start sharing your game |
| `/nes stop` | Stop sharing |
| `/nes limit [count]` | Get/set the spectator limit (0 = unlimited) |
| `/nes via` | Show cross-version status + connected spectators |

`/nes` is an alias for `/notenoughspectators`.

## How it works

The host client captures the packets it receives, then replays them to spectator clients that join a
lightweight dummy Minecraft server it runs. That server is exposed to the internet by tunnelling the
port with [Bore](https://github.com/ekzhang/bore) (public `bore.pub` by default). With the embedded
Via layer, incoming spectator connections are treated like clients of a backend server, so their
protocol is translated to/from the host's native 26.2.

## Cross-version support — current status

Same-version (26.2) spectating works. **Older-version spectating is limited by ViaBackwards, not by
this mod.** A spectator on e.g. 1.21.8 currently completes handshake, login, and the entire
configuration phase, then fails when its client parses 26.2's registry data — ViaBackwards has not
yet finished converting the brand-new 26.2 registries (`sulfur_cube_archetype`, `dialog`,
`wolf_sound_variant`, …) down to older formats. This is upstream and will start working once
ViaBackwards ships 26.2 down-conversion — a plain rebuild picks it up (deps track the snapshot). See
the [wiki](../../wiki) for details.

## Building

Requires **JDK 25**.

```bash
./gradlew build          # -> build/libs/not-enough-spectators-<version>.jar (Via bundled)
```

## Licensing

This project is licensed under the **GNU General Public License v3.0** — see [LICENSE](LICENSE).

- Release jars bundle **ViaVersion, ViaBackwards and ViaRewind** (also GPL-3.0); see
  [NOTICE-Via.md](NOTICE-Via.md). GPL-3.0 is compatible with those, so the distributed jar is a clean
  GPL-3.0 combined work.
- The project derives from the upstream CC0-1.0 mod (public domain), which is compatible with
  relicensing the combined project under GPL-3.0.

```
Copyright (C) 2026 the-real-ltcg
This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, version 3.
```

## Credits

- Original mod: **Cheezer** — <https://github.com/Cheezer1656/NotEnoughSpectators>
- ViaVersion / ViaBackwards / ViaRewind: the **ViaVersion** team — <https://viaversion.com>
