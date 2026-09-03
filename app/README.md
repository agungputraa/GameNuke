# Game Nuke 1.2.0 — Split Trapezoid Module Cockpit

Android game-booster project for a user-started foreground gaming session. The main floating
cockpit is implemented with Jetpack Compose, Material 3 and Canvas; child tools use bounded native
overlay windows with scrollable content and explicit controls.

Version 1.2.0 renders the main cockpit as two independent, mirrored trapezoid overlay windows so
the gameplay gap remains visible and touchable. The right wing adds a hash-pinned ModuleShop
catalog with Material 3 search, explicit install and verified `exec.sh`/`del.sh` switches.

## Active floating modules

1. Frame Control — native refresh path, measured FPS sampling and optional per-game GPU relief.
2. Deep Clean — selectable, measured cache/memory reclaim layers.
3. Crosshair — Canvas preview and live overlay controls.
4. Live Monitor — CPU/RAM trends, display telemetry and touch-through mode.
5. Network — Wi-Fi performance lock, link telemetry and Android network panel.
6. Pressure Radar — protected, explicit multi-select background release.
7. Screen & HUD — rotation, keep-awake, opacity, scale and legacy display recovery.
8. CPU Monitor — read-only batched per-core clock chart.
9. ModuleShop — 169 searchable remote modules in the audited snapshot, with signed catalog trust,
   per-archive hashes, private atomic extraction and shell-UID-only execution.

## Safety properties

- Boost startup does not write global `wm size` or `wm density`.
- Device mutations are capability-checked, verified and ownership-restored.
- Slow panel operations are single-flight so repeated taps do not queue duplicate commands or
  settings screens.
- Floating windows are constrained to current safe display bounds and child content scrolls.
- The edge handle is circular and remains available when the main panel is minimized.
- Remote modules cannot queue on repeated taps, cannot run as root, cannot traverse ZIP paths and
  settle their switches only after real script output is checked.

## Toolchain

- JDK 17
- Android Gradle Plugin 8.13.2
- Gradle 8.13
- Kotlin 2.3.0
- compileSdk / targetSdk 36
- minSdk 30

See `../BUILD_WINDOWS.md`, `../IMPLEMENTATION_1.2.0.md` and `../MODULESHOP_SECURITY_AUDIT.md`.
