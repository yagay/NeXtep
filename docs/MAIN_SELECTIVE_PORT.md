# Selected floating-workspace port to main

This refactor starts from the original `main` commit
`f76bb1dbb1d90cef814209e8025173061c47ddcb`.
It is **not** a merge of `feature/floating-lockscreen-audio`.

## Included
- Four native virtual-display slots (V6 layout; legacy V2–V5 decoding).
- User-authorized movable floating bubble and separately owned SystemUI dock.
- Tap empty slot to park a foreground task, app picker, task removal confirmation.
- Compact horizontal/vertical layout, scale, auto-fold on outside tap.
- Stable enlarged interactive live preview and display-scoped touch forwarding.
- **Opt-in** SystemUI-owned floating-display lock lifecycle guard.
- User-initiated SAF diagnostic ZIP export and safe per-scope process restart.
- Grouped settings, localized UI, Debug-only Actions workflow.

## Deliberately excluded
- Temporary native fullscreen task migration, desktop-task stealing and auto-return.
- Pad/independent-display focus flags and alternative virtual-display profiles.
- MediaSession auto-resume, player-specific Xposed hooks, and app allowlists.

## Ownership / safety
- The original three-slot main workspace becomes four slots; its task exchange
  and surface presentation logic are otherwise kept as the baseline.
- Original `NeXtep-slot-N` displays disconnect their output on lock as before.
- Optional floating `NeXtep-floating-slot-N` displays preserve their surface.
- The lock guard is disabled by default and only recognizes SystemUI-owned
  floating displays with positive IDs and the virtual display type.
- Stable zoom does not migrate Task IDs or move apps to physical Display 0.
- Collapsing the dock does not detach TextureViews or destroy its session.
- Closing the session deliberately checks that tasks can be parked safely.

## Debug validation
- `:app:testDebugUnitTest :app:assembleDebug` (Debug only).
- Verify four-slot task exchanges and old recorded profile migration.
- Verify floating add/remove, lock/unlock, fold/reopen, portrait/landscape.
- Verify touch injection under ColorOS; permission failures must log and
  leave tasks unchanged.
- Verify enabling the experimental lock guard in system_server does not alter
  any original workspace display, unrelated virtual display, or force-stopped app.
- Test all lifecycle changes on a recovery-capable device before releasing.
