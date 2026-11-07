#!/system/bin/sh
# LegionDock rescue: puts the tablet back to normal if it is ever stuck in TV mode
# and the app can't be opened. Run it from a computer:
#
#   adb push rescue.sh /data/local/tmp/
#   adb shell sh /data/local/tmp/rescue.sh
#
# It only resets display/system settings LegionDock may have changed. It deletes nothing.

echo "LegionDock rescue"

# stop LegionDock and its helper processes
am force-stop io.legiondock 2>/dev/null
for p in $(ps -A -o PID,ARGS 2>/dev/null | grep 'app_process.*io\.legiondock\.' | grep -v grep | awk '{print $1}'); do kill "$p" 2>/dev/null; done

# leave Lenovo PC mode / external desktop mode if they are on
if [ "$(settings get system zui_pc_mode 2>/dev/null)" = 1 ]; then
    settings put system zui_pc_mode_switch "0_$(date +%s)000"; sleep 4
fi
settings put system zui_external_display_desktop_mode 0 2>/dev/null

# screen size, UI size, animations
wm size reset
wm density reset
settings put global window_animation_scale 1
settings put global transition_animation_scale 1
settings put global animator_duration_scale 1

# auto-rotate
cmd window user-rotation -d 0 free 2>/dev/null
cmd window fixed-to-user-rotation -d 0 default 2>/dev/null
settings put system accelerometer_rotation 1

# power, notifications, vibration, charging
svc power stayon false
cmd notification set_dnd off 2>/dev/null
settings put system haptic_feedback_enabled 1
settings delete global key_game_assistant_bypass_charging 2>/dev/null

# refresh rate: allow the panel's highest rate again
max=$(dumpsys display 2>/dev/null | grep -m1 'mBaseDisplayInfo' | grep -o 'fps=[0-9.]*' | cut -d= -f2 | sort -n | tail -1)
[ -n "$max" ] && settings put system peak_refresh_rate "$max"
settings delete system min_refresh_rate 2>/dev/null

# make sure the tablet screen is on
input keyevent WAKEUP

rm -rf /data/local/tmp/legiondock
echo "Done. If the screen still looks wrong, restart the tablet."
