# Panes 3.5.13 compatibility work

This branch is a **clean-room feature parity effort**, based on the
Apache-2.0 NeXtep source. The proprietary Panes APK is reference material
for *observable behaviour*, not source code to copy.

## Phase 1: four real application slots

- The rail now creates **four** VirtualDisplay-backed task slots instead of three.
- Slot geometries use the shared recorded portrait canvas and a common gap.
- Workspace layout wire format has advanced from **V5 (three slots)** to
  **V6 (four slots)**. Validation of V2-V5 records is retained; legacy
  geometries are recalibrated from their recorded physical display, insets,
  density and rotation before a four-slot workspace is opened.
- No change to the three SystemUI *window hosts*: these host the app strip,
  control pane and side rail; they are not the number of application slots.
- Slot task handoff, failure handling and drag/drop still use the original
  NeXtep coordinators, with no changes to the proprietary Panes native library.

## Scope and limitations

This is **not** complete Panes parity. It does not yet add a runtime choice
between 3–6 slots, immersive Dock, saved workspace scenes, title widgets,
or all ROM-specific patches. Existing settings/UI remain NeXtep's.
The fourth full-resolution VirtualDisplay costs memory and graphics resources.
The target for initial device testing is OxygenOS 16 / Android 16; other
ROMs and Android releases are not assumed compatible.

## Validation plan

1. Build **Debug only** and run the JUnit layout tests.
2. Test left/right rail with gesture and 3-button navigation.
3. Enter and exit the workspace, rotate both directions, then lock/unlock.
4. Move tasks among all four slots and the main task, including rollback on
   failure, video playback, keyboard focus and overview changes.
5. Verify old V2–V5 layout records migrate and malformed profiles are rejected.
6. Keep the original NeXtep module disabled when trying this fork. Do not
   enable other workspace hooks concurrently; keep a recovery path available.

## Future implementation order

1. Configurable slot count with versioned cross-process configuration;
   implement one authoritative slot-count snapshot per active workspace.
2. Safe task-migration transaction and rollback diagnostics.
3. Immersive main-task mode and reversible side Dock.
4. Scene profiles and per-application policy / pause exclusions.
5. Screen/video/IME/notification adaptation on validated ROM versions.

## Source and attribution

- NeXtep: https://github.com/lujinxin/NeXtep (Apache-2.0).
- Panes public module: https://modules.lsposed.org/module/io.github.yishisanren.panes/

Preserve the upstream LICENSE and THIRD_PARTY_NOTICES. No Panes APK assets,
binary code, license-check implementation, branding, or signatures are imported.
