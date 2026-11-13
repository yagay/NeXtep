# Floating workspace entry — phase 1

A SystemUI-owned, draggable bubble opens the four-slot NeXtep workspace.

## Behaviour
- Enable **Floating workspace icon (experimental)** in the module settings.
- A small edge icon appears over ordinary apps. Drag it vertically or to either side; the chosen position is stored in SystemUI's device-protected preferences.
- Tap to open the existing workspace with its real VirtualDisplay slots.
- In the expanded workspace, **+** adds the foreground/recent app and tapping an occupied slot swaps its app to the main area, as before.
- Close the workspace from its own controls and the bubble returns.
- The bubble hides when the screen is off, the keyguard is locked or the workspace is active; window creation failures leave other triggers usable.

## Important limitation
This first phase supplies a *floating entry*, not yet a standalone freely floating mini-window panel. Opening the workspace still transforms the fullscreen main app to the existing main/rail layout. An overlay-only dock preserving the fullscreen main app requires a separately designed task-presentation mode, because the coordinator currently reconciles the main task surface against the workspace geometry.

## Safety
The bubble is off by default and can be disabled in settings. The code does not request accessibility or generic overlay permissions; it runs inside the injected SystemUI process. The default SystemUI window type is ROM-dependent. Do not enable Panes or another multi-window system hook concurrently. The original workspace tile and gestures are retained as recovery entry points.
