# LegionDock core: runs as shell (Shizuku) or root. The app prepends variables:
#   ACTION APK PANEL_METHOD + action-specific ones (see each action)
exec 2>&1
D=/data/local/tmp/legiondock
STATE=$D/state.saved      # dock layer
HH=$D/hh.saved            # handheld layer
APP=$D/app.saved          # per-app layer (always on top)
mkdir -p $D 2>/dev/null
SDK=$(getprop ro.build.version.sdk)
# never let a helper hang the whole script
TO=""; timeout 1 true 2>/dev/null && TO="timeout 10"

sget() { settings get "$1" "$2" 2>/dev/null; }
sset() {
    if [ -z "$3" ] || [ "$3" = "null" ]; then settings delete "$1" "$2" >/dev/null 2>&1
    else settings put "$1" "$2" "$3" >/dev/null 2>&1; fi
}

current_home() {
    cmd package resolve-activity --brief -a android.intent.action.MAIN \
        -c android.intent.category.HOME 2>/dev/null | tail -n1 | tr -d ' \r'
}

set_home() {
    out=$(cmd package set-home-activity "$1" 2>&1)
    case "$out" in *uccess*) echo "home: $1";; *) echo "home change refused: $out";; esac
}

dnd() {  # on | off | restore <zen_mode value>
    case "$1" in
        on) cmd notification set_dnd priority >/dev/null 2>&1 ;;
        off) cmd notification set_dnd off >/dev/null 2>&1 ;;
        restore)
            case "$2" in
                1) cmd notification set_dnd priority ;; 2) cmd notification set_dnd none ;;
                3) cmd notification set_dnd alarms ;; *) cmd notification set_dnd off ;;
            esac >/dev/null 2>&1 ;;
    esac
}

refresh() { [ "$1" -gt 0 ] 2>/dev/null && { sset system peak_refresh_rate "$1.0"; sset system min_refresh_rate "$1.0"; }; }

audio_route() {  # tv | bt | speaker | clear
    [ -z "$1" ] && return 0
    out=$(CLASSPATH="$APK" $TO app_process /system/bin io.legiondock.AudioRoute "$1" 2>&1 | grep -E 'AUDIO_' | head -n1)
    case "$out" in AUDIO_OK*) echo "audio: ${out#AUDIO_OK }";; *) echo "audio route failed: ${out#AUDIO_FAIL }";; esac
}

kill_helpers() {
    # only real helper processes ("app_process /system/bin io.legiondock.X"), never this script
    # (pkill -f would match our own "sh -c <script>" command line and kill us)
    ps -A -o PID=,ARGS= 2>/dev/null | while read -r pid a1 a2 a3 rest; do
        case "$a1 $a2 $a3" in
            "app_process /system/bin io.legiondock."*) [ "$pid" != "$$" ] && kill -9 "$pid" 2>/dev/null ;;
        esac
    done
    return 0
}

mem_free_mb() { echo $(( $(grep MemAvailable /proc/meminfo | tr -s ' ' | cut -d' ' -f2) / 1024 )); }

clean_mem() {  # uses CLEAN_LIST (space separated packages) and KEEP (package to spare)
    before=$(mem_free_mb)
    am kill-all >/dev/null 2>&1
    for p in $CLEAN_LIST; do
        [ "$p" = "$KEEP" ] && continue
        am force-stop "$p" >/dev/null 2>&1
    done
    sleep 1
    after=$(mem_free_mb)
    echo "memory: freed $((after - before)) MB, $after MB available"
}

# ── panel ────────────────────────────────────────────────────

sf_panel() {
    out=$(CLASSPATH="$APK" $TO app_process /system/bin io.legiondock.PanelPower "$1" 2>&1)
    echo "$out" | grep -q PANEL_OK && return 0
    echo "panel helper: $(echo "$out" | grep -E 'PANEL_FAIL|Exception|Error' | head -n2)"
    return 1
}

dim_panel() {
    sset system screen_brightness_mode 0
    sset system screen_brightness 1
    echo "panel: dimmed (fallback)"
}

panel_off() {
    case "${PANEL_METHOD:-sf}" in
        sf) if [ -n "$APK" ] && sf_panel off; then echo "panel: off"; return 0; fi; dim_panel ;;
        android)
            if [ "$SDK" -ge 35 ] 2>/dev/null && cmd display power-off 0 >/dev/null 2>&1; then
                echo "panel: off (android)"; return 0
            fi
            dim_panel ;;
        *) dim_panel ;;
    esac
}

panel_on() {
    [ -n "$APK" ] && sf_panel on >/dev/null 2>&1
    [ "$SDK" -ge 35 ] 2>/dev/null && cmd display power-on 0 >/dev/null 2>&1
    echo "panel: on"
}

# ── dock layer ───────────────────────────────────────────────
# vars: RENDER DENSITY_MODE REFRESH LANDSCAPE PANEL_OFF STAY_AWAKE ANIM DND HOME_COMP LAUNCH_COMP AUDIO CLEAN CLEAN_LIST

APPLIED=$D/applied.last   # values LegionDock itself set last time: never treat them as "original"

save_state() {
    [ -f "$STATE" ] && return 0
    cur_size=$(wm size | sed -n 's/^Override size: //p' | tr -d '\r')
    cur_dens=$(wm density | sed -n 's/^Override density: //p' | tr -d '\r')
    cur_wa=$(sget global window_animation_scale); cur_ta=$(sget global transition_animation_scale); cur_ad=$(sget global animator_duration_scale)
    if [ -f "$APPLIED" ]; then
        . "$APPLIED"
        [ -n "$cur_size" ] && [ "$cur_size" = "$ap_size" ] && cur_size=""
        [ -n "$cur_dens" ] && [ "$cur_dens" = "$ap_dens" ] && cur_dens=""
        [ "$cur_wa" = "$ap_anim" ] && cur_wa=null
        [ "$cur_ta" = "$ap_anim" ] && cur_ta=null
        [ "$cur_ad" = "$ap_anim" ] && cur_ad=null
    fi
    {
        echo "wm_size='$cur_size'"
        echo "wm_density='$cur_dens'"
        echo "s_accel='$(sget system accelerometer_rotation)'"
        echo "s_userrot='$(sget system user_rotation)'"
        echo "g_stayon='$(sget global stay_on_while_plugged_in)'"
        echo "s_bmode='$(sget system screen_brightness_mode)'"
        echo "s_bright='$(sget system screen_brightness)'"
        echo "s_peak='$(sget system peak_refresh_rate)'"
        echo "s_min='$(sget system min_refresh_rate)'"
        echo "g_wa='$cur_wa'"
        echo "g_ta='$cur_ta'"
        echo "g_ad='$cur_ad'"
        echo "g_zen='$(sget global zen_mode)'"
        echo "x_mirror='$(sget secure mirror_built_in_display)'"
        echo "x_desktop='$(sget system zui_external_display_desktop_mode)'"
        echo "x_haptic='$(sget system haptic_feedback_enabled)'"
        echo "x_bt='$(sget global bluetooth_on)'"
        echo "x_bypass='$(sget global key_game_assistant_bypass_charging)'"
        echo "home='$(current_home)'"
    } > "$STATE"
    echo "state saved"
}

restore_state() {
    [ -f "$STATE" ] || { echo "nothing to restore"; return 0; }
    . "$STATE"
    # leave Lenovo PC mode first, so it doesn't re-apply its own size afterwards
    if [ "$x_pcmode" = 1 ] && [ "$(sget system zui_pc_mode)" = 1 ]; then pc_mode 0; sleep 4; echo "pc mode: off"; fi
    if [ -n "$wm_size" ]; then wm size "$wm_size"; else wm size reset; fi
    if [ -n "$wm_density" ]; then wm density "$wm_density"; else wm density reset; fi
    sset system accelerometer_rotation "$s_accel"
    sset system user_rotation "$s_userrot"
    sset global stay_on_while_plugged_in "$g_stayon"
    sset system screen_brightness_mode "$s_bmode"
    sset system screen_brightness "$s_bright"
    sset system peak_refresh_rate "$s_peak"
    sset system min_refresh_rate "$s_min"
    sset global window_animation_scale "$g_wa"
    sset global transition_animation_scale "$g_ta"
    sset global animator_duration_scale "$g_ad"
    dnd restore "$g_zen"
    [ -n "$x_mirror" ] && sset secure mirror_built_in_display "$x_mirror"
    sset system zui_external_display_desktop_mode "${x_desktop:-0}"
    [ -n "$x_ext_uid$EXT_ID" ] && ext_scale_reset "$x_ext_uid"
    sset global key_game_assistant_bypass_charging "$x_bypass"
    [ -n "$x_haptic" ] && sset system haptic_feedback_enabled "$x_haptic"
    [ "$x_bt" = 0 ] && [ "$BT_OFF_AFTER" = 1 ] && cmd bluetooth_manager disable >/dev/null 2>&1
    # the per-display rotation lock lives in WindowManager, not in settings: free it
    if [ "$s_accel" != 0 ]; then
        cmd window user-rotation -d 0 free >/dev/null 2>&1
        sset system accelerometer_rotation 1
    fi
    cmd window fixed-to-user-rotation -d 0 default >/dev/null 2>&1
    rm -f "$APPLIED"
    case "$home" in ""|*ResolverActivity*|*No*activity*) ;; *) set_home "$home" ;; esac
    rm -f "$STATE"
    echo "state restored"
}

pc_mode() {   # 1 = enter Lenovo PC mode, 0 = leave (same switch the quick-settings tile uses)
    settings put system zui_pc_mode_switch "${1}_$(date +%s)000" >/dev/null 2>&1
}

pc_scale() {   # SCALE percent; 100% = Lenovo's own PC-mode size for the current resolution
    sz=$(wm size | sed -n 's/^Override size: //p' | tr -d '\r')
    [ -n "$sz" ] || sz=$(wm size | sed -n 's/^Physical size: //p' | tr -d '\r')
    a=${sz%x*}; b=${sz#*x}; short=$a; [ "$b" -lt "$a" ] 2>/dev/null && short=$b
    base=$((short * 160 / 540))
    sc=${SCALE:-80}
    d=$((base * sc / 100)); [ "$d" -lt 120 ] && d=120
    wm density $d
    sed -i "s/^ap_dens=.*/ap_dens='$d'/" "$APPLIED" 2>/dev/null
    echo "pc scale: ${sc}% (density $d)"
}

pc_relayout() {   # re-apply the scale and re-enter PC mode so the taskbar is laid out for it
    pc_scale
    pc_mode 0; sleep 2; am force-stop com.zui.desktoplauncher >/dev/null 2>&1; sleep 1; pc_mode 1; sleep 3
}

ext_scale() {   # EXT_ID EXT_UID SCALE (percent of the TV's native density)
    [ -z "$EXT_ID" ] && return 0
    base=$(wm density -d "$EXT_ID" 2>/dev/null | sed -n 's/^Physical density: //p' | tr -d '\r')
    [ -z "$base" ] && { echo "desktop scale: TV display not found"; return 0; }
    if [ "${SCALE:-100}" -ge 100 ] 2>/dev/null; then
        wm density reset -d "$EXT_ID" >/dev/null 2>&1
        echo "desktop scale: 100% ($base dpi)"
    else
        n=$((base * SCALE / 100))
        wm density "$n" -d "$EXT_ID" >/dev/null 2>&1
        echo "desktop scale: $SCALE% ($n dpi)"
    fi
}

ext_scale_reset() {   # undo the TV density override (by display id if still connected, else by unique id)
    if [ -n "$EXT_ID" ] && wm density reset -d "$EXT_ID" >/dev/null 2>&1; then return 0; fi
    [ -n "$1" ] && wm density reset -u "$1" >/dev/null 2>&1
    return 0
}

grant_perms() {
    cmd appops set io.legiondock 214 allow >/dev/null 2>&1      # ZUI: GET_INSTALLED_APP
    cmd deviceidle whitelist +io.legiondock >/dev/null 2>&1
}

do_dock() {
    kill_helpers
    grant_perms
    save_state
    [ "$BT" = 1 ] && { cmd bluetooth_manager enable >/dev/null 2>&1; echo "bluetooth: on"; }
    [ "$BYPASS" = 1 ] && { sset global key_game_assistant_bypass_charging 1; echo "bypass charging: on"; }
    [ "$NO_HAPTIC" = 1 ] && { sset system haptic_feedback_enabled 0; echo "touch vibration: off"; }
    sset system zui_external_display_desktop_mode 0
    if [ "$DESKTOP" = 1 ]; then
        # Desktop = Lenovo PC mode (taskbar + windows) on the tablet's own screen, mirrored to the TV in landscape
        LANDSCAPE=1; DENSITY_MODE=none; HOME_COMP=""; LAUNCH_COMP=""; CLEAN=0
        # Lenovo's PC launcher only lays out its app drawer properly at the panel's full width:
        # 16:9 at full width (fills a TV, e.g. 2560x1440) or the native 16:10 shape
        [ "$RENDER" = 0 ] || RENDER=WIDE
    fi
    # ask Android to mirror the tablet instead of starting a separate desktop on the TV
    if [ "$FORCE_MIRROR" = 1 ]; then sset secure mirror_built_in_display 1; fi
    phys=$(wm size | sed -n 's/^Physical size: //p' | tr -d '\r')
    pw=${phys%x*}; ph=${phys#*x}
    short=$pw; long=$ph; [ "$pw" -gt "$ph" ] 2>/dev/null && { short=$ph; long=$pw; }
    [ "$RENDER" = WIDE ] && RENDER=$((long * 9 / 16))
    # Landscape is done with a landscape-shaped resolution at rotation 0, not by rotating:
    # Lenovo's mirror mode pins the built-in display's rotation.
    if [ "$RENDER" -gt 0 ] 2>/dev/null; then
        s=$RENDER; l=$((RENDER * 16 / 9))
    else
        s=$short; l=$long
    fi
    if [ "$LANDSCAPE" = 1 ] || [ "$RENDER" -gt 0 ] 2>/dev/null; then
        if [ "$LANDSCAPE" = 1 ]; then wm size "${l}x${s}"; else
            if [ "$pw" -lt "$ph" ]; then wm size "${s}x${l}"; else wm size "${l}x${s}"; fi
        fi
        echo "render: ${l}x${s}"
    fi
    base=$((s * 160 / 620))
    case "$DENSITY_MODE" in
        auto)  wm density $base ;;
        small) wm density $((base * 85 / 100)) ;;
        large) wm density $((base * 115 / 100)) ;;
    esac
    if [ "$LANDSCAPE" = 1 ]; then
        sset system accelerometer_rotation 0
        sset system user_rotation 0
        cmd window user-rotation -d 0 lock 0 >/dev/null 2>&1
    fi
    [ "$STAY_AWAKE" = 1 ] && svc power stayon true
    refresh "$REFRESH"
    if [ "$ANIM" = 1 ]; then
        sset global window_animation_scale 0.5
        sset global transition_animation_scale 0.5
        sset global animator_duration_scale 0.5
    fi
    {
        echo "ap_size='$(wm size | sed -n 's/^Override size: //p' | tr -d '\r')'"
        echo "ap_dens='$(wm density | sed -n 's/^Override density: //p' | tr -d '\r')'"
        echo "ap_anim='$( [ "$ANIM" = 1 ] && echo 0.5 || echo none)'"
    } > "$APPLIED"
    [ "$DND" = 1 ] && dnd on
    audio_route "$AUDIO"
    if [ "$DESKTOP" = 1 ]; then
        echo "x_pcmode=1" >> "$STATE"
        am force-stop com.zui.desktoplauncher >/dev/null 2>&1   # drop a drawer grid cached for another size
        # scale first: PC mode lays out its taskbar for the density it starts with
        pc_scale
        pc_mode 1
        sleep 5
        # Lenovo may apply its own size on entry; put ours back and re-lay out if so
        want=$(sed -n "s/^ap_dens='\(.*\)'/\1/p" "$APPLIED")
        if [ "$(wm density | sed -n 's/^Override density: //p' | tr -d '\r')" != "$want" ]; then pc_relayout; fi
        if [ "$PANEL_OFF" = 1 ]; then sleep 1; panel_off; fi
        sleep 1; refresh "$REFRESH"
        echo "DOCKED"
        return 0
    fi
    [ "$CLEAN" = 1 ] && KEEP="" clean_mem
    [ -n "$HOME_COMP" ] && set_home "$HOME_COMP"
    sleep 1
    am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1
    if [ -n "$LAUNCH_COMP" ]; then
        sleep 1; am start -n "$LAUNCH_COMP" >/dev/null 2>&1 && echo "launched: $LAUNCH_COMP"
    fi
    if [ "$PANEL_OFF" = 1 ]; then sleep 2; panel_off; fi
    # Lenovo resets the refresh cap after a resolution change: apply it again at the end
    sleep 1; refresh "$REFRESH"
    echo "DOCKED"
}

# ── Switch save sync (Eden <-> Citron): newest save per game wins ──
sync_saves() {
    A=/sdcard/Android/data; BK=/sdcard/Games/Saves-Backup
    e=$(ls -d $A/dev.eden.eden_emulator/files/nand/user/save/0000000000000000/*/ 2>/dev/null | head -1)
    c=$(ls -d $A/org.citron.citron_emu/files/nand/user/save/0000000000000000/*/ 2>/dev/null | head -1)
    if [ -z "$e" ] || [ -z "$c" ]; then echo "sync: both emulators need a save folder first"; return 0; fi
    e=${e%/}; c=${c%/}
    newest() { [ -d "$1" ] || { echo 0; return; }; v=$(find "$1" -type f -exec stat -c %Y {} \; 2>/dev/null | sort -n | tail -1); echo ${v:-0}; }
    erun=$(pidof dev.eden.eden_emulator); crun=$(pidof org.citron.citron_emu)
    plan=""
    for t in $( (ls "$e"; ls "$c") | sort -u); do
        te=$(newest "$e/$t"); tc=$(newest "$c/$t")
        if [ "$te" -gt "$tc" ]; then plan="$plan E:$t"; elif [ "$tc" -gt "$te" ]; then plan="$plan C:$t"; fi
    done
    [ -n "$plan" ] || { echo "sync: saves already match"; return 0; }
    snap=$BK/auto-$(date +%Y%m%d-%H%M%S); mkdir -p "$snap"
    cp -a "$e" "$snap/eden"; cp -a "$c" "$snap/citron"
    # keep the 5 newest automatic snapshots
    n=$(ls -d $BK/auto-* 2>/dev/null | wc -l)
    [ "$n" -gt 5 ] && ls -d $BK/auto-* | sort | head -n $((n - 5)) | while read -r o; do rm -rf "$o"; done
    done_n=0; skip_n=0
    for item in $plan; do
        t=${item#*:}
        case "$item" in
            E:*) if [ -n "$crun" ]; then skip_n=$((skip_n+1)); else rm -rf "$c/$t"; cp -a "$e/$t" "$c/$t"; done_n=$((done_n+1)); fi ;;
            C:*) if [ -n "$erun" ]; then skip_n=$((skip_n+1)); else rm -rf "$e/$t"; cp -a "$c/$t" "$e/$t"; done_n=$((done_n+1)); fi ;;
        esac
    done
    echo "sync: $done_n game save(s) updated$( [ $skip_n -gt 0 ] && echo ", $skip_n waiting (emulator still open)")"
}

# ── handheld layer ───────────────────────────────────────────
# vars: REFRESH DND CLEAN CLEAN_LIST

hh_on() {
    if [ ! -f "$HH" ]; then
        { echo "h_peak='$(sget system peak_refresh_rate)'"; echo "h_min='$(sget system min_refresh_rate)'"
          echo "h_zen='$(sget global zen_mode)'"; } > "$HH"
    fi
    refresh "$REFRESH"
    [ "$DND" = 1 ] && dnd on
    [ "$CLEAN" = 1 ] && KEEP="" clean_mem
    echo "HANDHELD_ON"
}

hh_off() {
    [ -f "$HH" ] || { echo "HANDHELD_OFF"; return 0; }
    . "$HH"
    sset system peak_refresh_rate "$h_peak"; sset system min_refresh_rate "$h_min"
    dnd restore "$h_zen"
    rm -f "$HH"
    echo "HANDHELD_OFF"
}

# ── per-app layer ────────────────────────────────────────────
# vars: PKG REFRESH FPS DNDX(on/off/"") AUDIO CLEAN CLEAN_LIST | exit: BASE_AUDIO

app_enter() {
    if [ ! -f "$APP" ]; then
        { echo "a_peak='$(sget system peak_refresh_rate)'"; echo "a_min='$(sget system min_refresh_rate)'"
          echo "a_zen='$(sget global zen_mode)'"; echo "a_pkg='$PKG'"; echo "a_fps='$FPS'"; echo "a_audio='$AUDIO'"; } > "$APP"
    fi
    refresh "$REFRESH"
    case "$DNDX" in on) dnd on ;; off) dnd off ;; esac
    if [ "$FPS" -gt 0 ] 2>/dev/null; then
        o1=$(cmd game set --mode 2 --fps "$FPS" "$PKG" 2>&1); o2=$(cmd game mode 2 "$PKG" 2>&1)
        case "$o1$o2" in *rror*|*xception*|*nknown*) echo "fps limit not supported for this app: $o1 $o2" ;; *) echo "fps limit: $FPS" ;; esac
    fi
    audio_route "$AUDIO"
    [ "$CLEAN" = 1 ] && KEEP="$PKG" clean_mem
    echo "PROFILE_ON $PKG"
}

app_exit() {
    [ -f "$APP" ] || { echo "PROFILE_OFF"; return 0; }
    . "$APP"
    sset system peak_refresh_rate "$a_peak"; sset system min_refresh_rate "$a_min"
    dnd restore "$a_zen"
    [ "$a_fps" -gt 0 ] 2>/dev/null && cmd game reset "$a_pkg" >/dev/null 2>&1
    [ -n "$a_audio" ] && audio_route "${BASE_AUDIO:-clear}"
    rm -f "$APP"
    echo "PROFILE_OFF"
}

# ── emergency restore: undo every layer, then make sure the panel/display are sane ──

restore_all() {
    kill_helpers
    had=0; [ -f "$STATE" ] && had=1
    BASE_AUDIO=clear app_exit >/dev/null
    hh_off >/dev/null
    panel_on
    audio_route clear
    if [ $had = 1 ]; then
        restore_state
    else
        wm size reset; wm density reset
        sset system accelerometer_rotation 1
        cmd window user-rotation -d 0 free >/dev/null 2>&1
        cmd window fixed-to-user-rotation -d 0 default >/dev/null 2>&1
        sset system zui_external_display_desktop_mode 0
        svc power stayon false
        echo "display reset to tablet defaults"
    fi
    ext_scale_reset "$EXT_UID"
    rm -f "$STATE" "$HH" "$APP"
    echo "RESTORED"
}

# ── perf monitor stream (long-running) ───────────────────────

perf_stream() {
    cz=""; gz=""
    for z in /sys/class/thermal/thermal_zone*; do
        t=$(cat "$z/type" 2>/dev/null)
        case "$t" in
            cpu*|*cpuss*|*cpu-*|*cpu_*) cz="$cz $z/temp" ;;
            gpu*|*gpuss*) gz="$gz $z/temp" ;;
        esac
    done
    big=/sys/devices/system/cpu/cpu7/cpufreq/scaling_cur_freq
    [ -r $big ] || big=/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq
    while :; do
        f=$(service call SurfaceFlinger 1013 2>/dev/null | sed -n 's/.*Parcel(\([0-9a-f]*\) *\([0-9a-f]*\).*/\2/p')
        c=0; for p in $cz; do v=$(cat "$p" 2>/dev/null); [ "${v:-0}" -gt "$c" ] 2>/dev/null && c=$v; done
        g=0; for p in $gz; do v=$(cat "$p" 2>/dev/null); [ "${v:-0}" -gt "$g" ] 2>/dev/null && g=$v; done
        b=$(cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null | tr -d ' %')
        q=$(cat $big 2>/dev/null)
        echo "S ${f:-x} $c $g ${b:-x} ${q:-x}"
        sleep 1
    done
}

# ── dispatcher ───────────────────────────────────────────────

case "$ACTION" in
    dock)       do_dock ;;
    undock)     kill_helpers; panel_on; audio_route clear; restore_state; echo "UNDOCKED" ;;
    kill_helpers) kill_helpers; echo "helpers cleaned" ;;
    grant)      grant_perms; echo "granted" ;;
    ext_scale)  if [ "$(sget system zui_pc_mode)" = 1 ]; then pc_relayout; else pc_scale; fi ;;
    tasks)      dumpsys activity activities 2>/dev/null | grep -E 'Display #[0-9]+|ActivityRecord\{[^ ]+ u0 [^ ]+ t[0-9]+' | grep -vE 'mLastPausedActivity|mLastFocusedRootTask' ;;
    reset_ui)   wm size reset; wm density reset; for k in window_animation_scale transition_animation_scale animator_duration_scale; do sset global $k 1; done
                cmd window user-rotation -d 0 free >/dev/null 2>&1; sset system accelerometer_rotation 1; rm -f "$APPLIED"; echo "ui reset" ;;
    refresh_check)
        if [ "$REFRESH" -gt 0 ] 2>/dev/null; then
            c=$(sget system peak_refresh_rate)
            [ "$c" = "$REFRESH.0" ] || { refresh "$REFRESH"; echo "refresh cap re-applied ($c -> $REFRESH Hz)"; }
        fi ;;
    hh_on)      hh_on ;;
    hh_off)     hh_off ;;
    app_enter)  app_enter ;;
    app_exit)   app_exit ;;
    clean)      clean_mem ;;
    close_app)  am force-stop "$PKG" && echo "closed $PKG" ;;
    panel_off)  panel_off ;;
    panel_on)   panel_on ;;
    perf)       perf_stream ;;
    detect)     n=$(dumpsys SurfaceFlinger --display-id 2>/dev/null | grep -c '^Display [0-9]'); echo "displays=$n" ;;
    gamepads)   getevent -lp 2>/dev/null ;;
    restore_all) restore_all ;;
    sync_saves) sync_saves ;;
    caps)
        pc=0; pm path com.zui.desktoplauncher >/dev/null 2>&1 && pc=1
        bp=0; { pm path com.zui.game.service >/dev/null 2>&1 || [ "$(sget global key_game_assistant_bypass_charging)" != null ]; } && bp=1
        echo "pcmode=$pc bypass=$bp model=$(getprop ro.product.model) rom=$(getprop ro.build.display.id)" ;;
    top_app)    dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity | sed 's/.* u[0-9]* \([^/ ]*\)\/.*/\1/' ;;
    status)
        echo "LAYERS dock=$([ -f "$STATE" ] && echo 1 || echo 0) hh=$([ -f "$HH" ] && echo 1 || echo 0) app=$([ -f "$APP" ] && echo 1 || echo 0) wm=$(wm size | grep -c Override)" ;;
    diag)
        echo "uid: $(id -u)   sdk: $SDK   build: $(getprop ro.build.display.id)"
        wm size; wm density
        echo "--- SurfaceFlinger displays"; dumpsys SurfaceFlinger --display-id 2>&1 | head -8
        echo "--- panel helper (method ${PANEL_METHOD:-sf})"; CLASSPATH="$APK" $TO app_process /system/bin io.legiondock.PanelPower list 2>&1 | head -6
        echo "--- audio outputs"; CLASSPATH="$APK" $TO app_process /system/bin io.legiondock.AudioRoute list 2>&1 | head -10
        echo "--- page flips"; service call SurfaceFlinger 1013 2>&1 | head -2
        echo "--- layers"; for f in "$STATE" "$HH" "$APP"; do [ -f "$f" ] && echo "$(basename $f): yes"; done
        echo "--- mirror_built_in_display: $(sget secure mirror_built_in_display)"
        echo "--- stray helpers: $(ps -A -o ARGS= 2>/dev/null | grep -c '^app_process /system/bin io.legiondock')"
        echo "--- home"; current_home
        echo "--- memory: $(mem_free_mb) MB available"
        ;;
    *) echo "unknown action '$ACTION'" ;;
esac
