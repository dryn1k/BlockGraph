# AGENTS.md

Client-only Fabric mod for **Minecraft 1.21.11**. Mod id `blockgraph`, package `com.dryn1k.blockgraph`, author `dryn1k stydio`. It lets a player select blocks (Shift + double RMB) and draws colored graph lines with distance labels between them.

## Build / run

- Must set `JAVA_HOME` to JDK 25 (the only toolchain that works here):
  ```powershell
  $env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot'
  \gradlew clean build
  ```
- Compile client only: `.\gradlew compileClientJava --no-configuration-cache`
- Dev client: `.\gradlew runClient` — runs **standalone**. Mod Menu and Cloth Config are gone; only Fabric API + this mod load. Do not re-add them.
- Tabs: `build/libs/blockgraph-1.0.0.jar`. `build.gradle` has global `release = 21`.

## Architecture

- Split source sets via Loom: logic/entrypoints in `src/main/java`, client in `src/client/java`. `fabric.mod.json` declares `environment: client` with `main` → `BlockGraph`, `client` → `BlockGraphClient`.
- `BlockGraphClient` — keybinds, Shift+double-click selection, manual links, `UseBlockCallback` interception, `onEndTick`.
- `SelectionManager` — selected blocks, faces, forced links, edge building (`appendTwoNearest`, etc.).
- `BlockGraphRenderer` — draws outlines/lines/labels via `WorldRenderEvents.AFTER_ENTITIES`.
- `config/BlockGraphConfig` — Gson config + presets, `save()/load()`, `copy()/replace()` used by the settings screen.
- `config/BlockGraphConfigScreen` — **pure vanilla** UI (no Cloth/Mod Menu): `Button`, `AbstractSliderButton`, `EditBox`, `StringWidget`, custom `ColorSwatch`. Paginated rows; presets bottom-left.

## Key semantics / conventions

- **Graph mode `CLOSEST_PAIR`** (name kept for config compatibility): each block gets at most **2 automatic edges**, nearest-first; manual links (Alt/Shift + RMB) are added on top and **do not count** toward the limit.
- Selection is always Shift + double RMB; link modifier is configurable (Alt default / Shift).
- Distance labels and ranking use block centers; lines start at clicked-face centers; coincident adjacent faces get a lifted connector above the blocks.
- Settings screen opens with **K** keybind.
- `processResources` expands `${version}` in `fabric.mod.json`; don't run a plain `jar` check against the source, always go through Gradle.

## Traps

- **Do not delete** `run/saves/Новый мир` or `run/saves/Новый мир (1)` — they are user worlds.
- `BLOCKGRAPH_TEST=1` env enables `ConfigUiTest` (temporary harness that opens the settings screen and screenshots it). Remove `ConfigUiTest.java` and its registration in `BlockGraphClient.onInitializeClient` before the final build.
- `Screenshot.grab(dir, ...)` requires `dir` to **already exist** (it writes `dir/screenshots/<timestamp>.png` but does not create the directory).
- Keep setting labels/tooltips short — long tooltips are an explicitly rejected problem.
