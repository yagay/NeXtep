# Floating workspace button — permission and lifecycle fix

## Why the previous icon did not appear

Previous implementation attempted to create a privileged `TYPE_PHONE` window
from injected SystemUI; it never declared or requested `SYSTEM_ALERT_WINDOW`.
OEM window policy or missing SystemUI initialization could reject the button.

## New app-owned overlay

1. Enable **Floating workspace icon** in settings.
2. When needed, **Display over other apps** authorization opens using
   `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` for the NeXtep package.
3. Returning to the settings app resumes an app-owned foreground service
   (Android 14+ `specialUse` type) with a persistent notification.
4. The service creates a `TYPE_APPLICATION_OVERLAY` window. Drag the bubble
   to either side; its position is remembered across service restarts.
5. Tap the bubble to invoke the existing `TriggerCoordinator.requestToggle`.
   All four real VirtualDisplay small windows and task exchanges remain in
   SystemUI / LSPosed. An unavailable hook shows a user-visible failure.
6. Disable the setting or use the notification's stop action to remove
   the icon. Screen-off removes it; unlock restores it.

The old SystemUI-owned bubble class is no longer instantiated.
Do not enable NeXtep and Panes in parallel.

**Limitation:** The bubble opens the existing resized main-window layout. It
does not yet provide four independently floating live app previews over
an unchanged full-screen foreground app.
