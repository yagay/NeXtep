# Floating windows are an additional mode

There are **two independent user entry points**:

1. **Original workspace** — Status-bar gesture, four-finger gesture and Quick
   Settings tile retain the existing one-main/four-small panel and their
   original task presentation, window controls and side workspace semantics.
2. **New floating panel** — Optional app-owned draggable bubble (overlay
   permission + foreground service); a tap opens a SystemUI-hosted, independent
   2x2 array of four real VirtualDisplay previews over the existing fullscreen
   foreground app.

The floating mode does **not** replace the original workspace, its geometry,
its triggers or its task coordinator instance. It has an independent task
coordinator and a `MainTaskPresentationCoordinator(preserveFullscreen=true)`.
The regular workspace uses `preserveFullscreen=false` as before.

To avoid two competing owners of the same application task, simultaneous
expansion is blocked. Opening the original workspace after the floating mode
first parks all floating tasks and closes the dock; on failure it refuses to
enter the other mode rather than detaching live displays. Disabling the optional
bubble closes only the floating dock. Screen off hides the floating dock before
attempting task-safe cleanup, with another cleanup attempt after unlock.

The floating dock has + Add for launchable apps, click on an occupied preview
to exchange it with the fullscreen main app, and X to close. The app's
`TYPE_APPLICATION_OVERLAY` bubble and SystemUI's floating panel are separate
windows. The bubble permission is requested by the module's settings screen.

**Verification:** Debug compile and tests plus OxygenOS 16 device testing
are required. No real-device UI or runtime stability is claimed solely from
the CI result. If the SystemUI window type is rejected, the bubble displays
the returned error; the original workspace still works independently.
