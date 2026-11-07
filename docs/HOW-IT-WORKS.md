# How it works

LegionDock is a plain-Java Android app (no AndroidX, no libraries, ~4,000 lines) with a POSIX shell backend.

```
┌──────────────── app process (io.legiondock) ─────────────────┐
│  MainActivity     settings UI                                 │
│  DockService      foreground service: watches displays,      │
│                   docks / undocks, applies game profiles      │
│  GuideService     accessibility service: Guide button,        │
│                   quick menu, virtual mouse, overlay,         │
│                   foreground-app tracking                     │
│  Shell ──► ShizukuBridge (raw binder) ──┐   or  su (root)     │
└─────────────────────────────────────────┼────────────────────┘
                                          ▼
                         core.sh  (runs as the shell user)
                         wm / settings / cmd / dumpsys …
                         app_process helpers: PanelPower, AudioRoute
```

## The pieces

| File | Role |
|---|---|
| [`DockService.java`](../app/src/main/java/io/legiondock/DockService.java) | Listens for external displays (`DisplayManager`), decides when to dock, runs the layers below, re-applies the refresh rate, syncs Switch saves. |
| [`GuideService.java`](../app/src/main/java/io/legiondock/GuideService.java) | Accessibility service. Filters key events (Guide, Select+Start, mouse combo), shows the quick menu and transition screen, tracks the foreground app. |
| [`core.sh`](../app/src/main/assets/core.sh) | Every privileged action: `dock`, `undock`, `restore_all`, `hh_on`, `app_enter`, `pc_scale`, `sync_saves`, `caps`, `diag`… |
| [`ShizukuBridge.java`](../app/src/main/java/io/legiondock/ShizukuBridge.java) | Talks to Shizuku's binder directly (`newProcess` transaction), so no Shizuku library is needed. |
| [`PanelPower.java`](../app/src/main/java/io/legiondock/PanelPower.java) | Run with `app_process` as shell; turns the built-in panel off at the SurfaceControl level so apps keep rendering (unlike `cmd display power-off`, which pauses them). |
| [`AudioRoute.java`](../app/src/main/java/io/legiondock/AudioRoute.java) | Forces audio to HDMI / Bluetooth / speaker while docked. |
| [`Modes.java`](../app/src/main/java/io/legiondock/Modes.java) / [`Profiles.java`](../app/src/main/java/io/legiondock/Profiles.java) | Settings model: per-mode and per-game options with defaults. |

## Layers and undo

Changes are applied in layers, and each layer saves what it overwrote:

1. **Dock layer**: resolution, UI size, refresh rate, rotation, audio, home screen, Bluetooth, PC mode… saved in `/data/local/tmp/legiondock/state.saved`.
2. **Handheld layer**: `hh.saved`.
3. **App layer**: the game profile of the app in front, `app.saved`.

Undock pops the layers in reverse order. `applied.last` records the values LegionDock itself set, so if the app is killed halfway, those are never mistaken for your originals on the next dock.

## Lenovo-specific tricks

- **Landscape on the TV.** Lenovo's mirror mode pins the built-in display's rotation, so LegionDock doesn't rotate. It sets a landscape-shaped resolution (`wm size 1920x1080`) at rotation 0.
- **PC mode.** Toggled with `settings put system zui_pc_mode_switch 1_<timestamp>` (the same switch as the quick-settings tile). The scale is set *before* entering, because the taskbar is laid out for the density PC mode starts with; a scale change briefly re-enters PC mode.
- **Refresh rate.** ZUI's display service rewrites `peak_refresh_rate` on app switches, so it's re-applied every 4 s while docked.
- **Bypass charging.** `settings put global key_game_assistant_bypass_charging 1` (Lenovo Game Assistant's switch).
- **App Clone.** ZUI may clone LegionDock into user 900; the clone detects this and stays idle.
- **Capability check.** `core.sh caps` reports whether PC mode and bypass charging exist, and the UI hides what the tablet lacks.

## Controller input

Accessibility key filtering sees every controller button system-wide, which is how the Guide button and combos work in any app.
The D-pad on many controllers arrives as a hat axis (not key events), so the quick menu is a focusable window and handles both.
The virtual mouse is an accessibility overlay plus gesture dispatch: no input injection permission is needed.
