# Troubleshooting

## The tablet is stuck looking like TV mode

(Wrong resolution, tiny or huge text, screen stays landscape, panel stays black…)

1. **Unplug the TV.** LegionDock restores the tablet automatically.
2. Open LegionDock → Home → **Restore tablet**. This undoes every change LegionDock made.
3. If the tablet screen is black, hold the Guide button and choose **Tablet screen**, or tap **Tablet screen on** on the Home page (it also comes back on undock).
4. Still wrong? Use the rescue script from a computer with [adb](https://developer.android.com/tools/releases/platform-tools):
   ```
   adb push tools/rescue.sh /data/local/tmp/
   adb shell sh /data/local/tmp/rescue.sh
   ```
   It resets the display and system settings LegionDock may have touched, and deletes nothing.
5. Last resort: restart the tablet. Screen-off and audio routing never survive a reboot; resolution and UI size are fixed by step 2 or 4.

## "Shizuku isn't running" / nothing happens when I plug in the TV

- Shizuku stops when the tablet restarts. Open Shizuku and tap **Start** (Wi-Fi must be on for wireless debugging).
- Check LegionDock is allowed in Shizuku (Shizuku → *Authorized applications*).
- Make sure the mode under *When the TV connects* isn't **Off**.

## The TV shows a portrait picture or black bars

- Turn on **Lock landscape** for the mode (TV modes → your mode → Picture).
- Pick a 16:9 render resolution (1080p or 1440p) to fill the TV; *Native 16:10* leaves thin bars on a 16:9 TV.

## The Guide button / quick menu does nothing

- Turn on the **LegionDock controller service** in *Settings → Accessibility* (Home → Finish setup shows it if it's off).
- Some firmware turns accessibility services off after an app update. Turn it on again.
- Controller without a Guide button? Use **Select + Start**.
- By default the Guide button only works while docked or in handheld mode; turn on *Use the Guide button everywhere* in Controller.

## Desktop mode

- **The Desktop tile is missing.** Your tablet doesn't have Lenovo's PC mode (ZUI/ZUXOS only).
- **The app drawer shows only a few apps per page.** Pick the *16:9 (fills the TV)* or *Native* resolution. Lenovo's PC launcher needs the panel's full width.
- **Windows have no minimise/maximise bar.** Maximised windows hide it until the pointer touches the top edge (Lenovo's design). Restore the window to keep the bar visible.
- **"Only mirror mode is supported in PC mode" toast** when changing the scale: harmless, it's Lenovo's message while PC mode restarts.

## Sound comes out of the wrong device

Set **Audio output** in the mode's settings to *TV* or *Bluetooth* instead of *Auto*.

## The refresh rate keeps changing back

Lenovo's display service resets the refresh rate when apps switch. LegionDock re-applies yours every few seconds while docked; a brief flicker on app switch is normal.

## Still stuck? Report it

Open LegionDock → **System → Diagnostics → Copy** and paste the report into a [new issue](https://github.com/ttabouch12/LegionDock/issues/new), with your tablet model and what you were doing.
