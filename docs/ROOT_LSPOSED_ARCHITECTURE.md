# NeXtep: Root + LSPosed-first architecture (test)

## Decision

Use an existing privileged execution context before introducing a nonprivileged workaround:

1. **LSPosed system_server hook** for platform lifecycle, display policy, Task/WindowManager state and physical key policy.
2. **LSPosed SystemUI / Launcher hook** for navigation gestures, Overview, ShellTaskOrganizer, real surface transactions and window presentation.
3. **Privileged APIs from those hooked processes** for task moves, virtual-display input and media-session coordination.
4. **Root with explicit, narrowly scoped commands** for restarting approved app scopes, obtaining diagnostics, or reading device-specific input sources where there is no suitable system hook.
5. **Ordinary APIs** for UI, preferences, benign user-facing operations and public Android lifecycle signals where they are already accurate.

Root/LSPosed are **not** synonymous with guaranteed foreground playback or a guaranteed always-on virtual display. Disabling an app's freeze policy does not override all of its own playback or DRM/decoder lifecycle decisions.

## Source audit (test branch)

| Area | Current source | Current implementation | Preferred direction |
|---|---|---|---|
| Home / Back / Recents buttons | `SystemUiHook`, `SystemNavigationKeyObserver` | OEM SystemUI candidates; new passive `PhoneWindowManager` KeyEvent tracing | Confirm actual key path, then gated system_server -> SystemUI collapse event; no polling |
| Home / Recents gestures | `SystemUiHook`, `SystemUiRootTouchHooker` | Incomplete OEM candidate coverage | Hook actual Launcher/Overview gesture-completed callbacks; never infer swipe from generic touch-up |
| Outside-tap fold | `FloatingSlotDockController` | `FLAG_WATCH_OUTSIDE_TOUCH` plus root blank taps | Keep as first-line local behavior; only consider privileged InputMonitor if proved necessary, without pilfering input |
| Task ownership and movement | `TaskSurfaceCompat`, `SlotTaskCoordinator`, `ActivityTaskManagerCompat` | Shell callback and privileged task/surface operations already in use | Keep system service + Shell hooks; remove task-id-change as a navigation substitute |
| Floating display creation and input | `VirtualDisplayFactory`, `FloatingVirtualDisplayInput` | SystemUI-owned displays and privileged InputManager injection | Keep privileged path, add per-device flag diagnostics and fail closed |
| Lock-screen lifecycle | `NeXtepSlotLifecycleGuard` | system_server ActivityRecord / TaskFragment and ColorOS freezer hooks | Verify match/protection counts and stop conditions with device logs; limit intervention to live NeXtep slots |
| Virtual display power | `NeXtepDisplayPowerDiagnostics` | Passive display group tracing | Trace actual floating display identity and power state; do not force physical display ON |
| Playback continuity | `PlaybackCompatAppHook`, `FloatingPlaybackCompatController` | App-scoped LSPosed diagnostics and MediaSession one-shot recovery | First inspect which event paused playback; use scoped player hook only if actually needed and opt-in |
| Floating native fullscreen/Pad | `FloatingSlotDockController`, `FloatingPadFocusPolicy` | Privileged Task promotion and independent-display flag candidates | Keep genuine Task semantics, do not spoof global TOP_RESUMED |
| Share, drag/drop, permissions | `SystemDragShareHook`, `ContentDropPermissionHook`, `ContentShareLaunchPolicy` | system_server interceptors already available | Keep platform-scoped hooks with source-target identity checks |
| IME, navigation bar, display layout | `WorkspaceImeTargetHook`, `WorkspaceNavigationBarHook`, `WorkspacePanelRotationHook` | system_server policy hooks | Keep scoped privileged integration |
| Restart of LSPosed scopes | `ScopeProcessRestarter` | Root `su -c` with bounded process selection | Keep constrained process allowlist, never force-stop system_server |
| Status bar collapse | `SystemUiPanelCollapser` | Reflective StatusBarManager in SystemUI | Already executes inside privileged SystemUI; root shell command would be a regression |
| UI, layout, language and settings | `SettingsScreen`, `SettingsRepository` | Ordinary view and preferences code | Keep ordinary Android APIs; system hooks would offer no advantage |

## Key-event observer introduced on test

`SystemNavigationKeyObserver` observes, without changing results, `PhoneWindowManager.interceptKeyBeforeQueueing` and `interceptKeyBeforeDispatching` when present. It only logs UP events for keycodes 3 (HOME), 4 (BACK), 187 (APP_SWITCH), plus install/missing-method diagnostics.

Important: **this is not yet the auto-collapse trigger**. The originating display of an injected virtual-display Back event must be distinguished from the physical navigation path before forwarding. Some ColorOS navigation modes do not produce these KeyEvents, and then the appropriate SystemUI/Launcher gesture-completion hook must be discovered by device logs/decompiled OEM classes.

## Implementation sequence

1. **Input truth**: observe real key events and OEM gesture callbacks, record hook readiness and event source; verify on device.
2. **Event channel**: dispatch only confirmed physical navigation events from system_server to SystemUI, with an explicit permission boundary, event deduplication and no binder operations on the input-policy hot path.
3. **Unified fold policy**: user Home/Back/Overview/outside touch folds the panel; internal virtual display input and dock controls do not. Do not destroy Task/VirtualDisplay or synthesize app lifecycle.
4. **Playback and lock**: trace actual Activity, VD power, freezer and media pause transitions before any additional interception; enable only selected live-slot processes.
5. **Remove unreliable fallbacks** once a proven privileged path exists, keeping minimal fail-open behavior for unsupported OS versions.
6. **Verify** Debug-only compile/unit tests and log-based device cases for 3-key navigation, gestures, outside touches, pad mode, lock/unlock and panel folds.

## Risk controls

- Do not globally disable screen lock, device power policy, OEM freeze, keyguard, audio focus or app permission checks.
- Do not block Home/Back key delivery; do not hook or restart every installed process by default.
- Ensure each platform hook can fail open without bootlooping SystemUI or system_server.
- Root is reserved for functions that strictly require it, not as a substitute for already available privileged framework APIs.
- Do not claim working lockscreen playback, navigation folding, or a passing build until observed in a fresh device log or CI result.
