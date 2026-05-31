# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`pano-mc-plugin` is the **in-game Minecraft plugin** that bridges a running Minecraft server with a
self-hosted Pano website (`pano-web-platform`). It links accounts, syncs players/permissions, and
relays server status. It connects to the backend over a **Vert.x WebSocket/WebClient** using a
host/port/token + encryption key from its local `PanoConfig`, with **AES-256-GCM** encrypted
messaging. See the ecosystem overview in `../CLAUDE.md`.

## Stack & layout

**Kotlin + Gradle (Kotlin DSL)**, shadow-jar packaging, multi-module — a shared `Core` plus one
module per platform (`settings.gradle.kts`):

- `Core` — shared logic (Vert.x 5 client, `PlatformManager` WebSocket connection, config).
- `Spigot` — Bukkit/Spigot/Paper (Java 8 bytecode; `plugin.yml`).
- `Bungeecord` — proxy (Java 8; `bungee.yml`).
- `Velocity` — modern proxy (Java 17; `velocity-plugin.json`).
- `Fabric` — mod platform.

Optional LuckPerms integration for permissions.

## Commands

```bash
./gradlew build                 # build all platform jars
./gradlew :Spigot:build         # one platform (→ pano-spigot-<version>.jar); also :Velocity, :Bungeecord, :Fabric
./gradlew buildDev              # dev build (MODE=DEVELOPMENT in the manifest)
./gradlew buildPluginDev        # build + copy jars into local test servers' plugin folders
./gradlew :Pano:test            # unit tests (if present)
```

Use **JDK 17+ to run Gradle**. Test the built jars against the servers in
`../minecraft test servers/`.

## Conventions

- Releases are automated by **semantic-release** on `alpha`/`beta`/`main` → use **conventional
  commit** messages.
- Per-platform manifest values come from `gradle.properties` and each module's build script; bump
  versions there, not by hand.
