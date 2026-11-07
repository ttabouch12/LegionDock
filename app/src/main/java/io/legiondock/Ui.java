package io.legiondock;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.widget.TextView;

/** Shared palette + tiny view helpers (activity and overlays). */
final class Ui {
    static final int BG = 0xFF0C1018, SIDEBAR = 0xFF0F141E, SURFACE = 0xFF151B27, SURFACE2 = 0xFF1C2433,
            STROKE = 0xFF242D3D, ACCENT = 0xFFFF5A6A, ACCENT_SOFT = 0xFF3A1C24, TEXT = 0xFFEDF1F7,
            SUB = 0xFF93A0B4, MUTED = 0xFF5F6B7E, GOOD = 0xFF34D399, GOOD_SOFT = 0xFF103325,
            WARN = 0xFFFBBF24, WARN_SOFT = 0xFF3A2E0F;

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(true);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static GradientDrawable round(int color, float radiusPx, int stroke, int strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        if (stroke != 0) g.setStroke(strokePx, stroke);
        return g;
    }

    /** Background that lights up with an accent ring when focused (controller) or pressed. */
    static StateListDrawable focusable(Context c, int base, int radiusDp, int strokeColor) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable f = round(SURFACE2, dp(c, radiusDp), ACCENT, dp(c, 2));
        GradientDrawable p = round(SURFACE2, dp(c, radiusDp), 0, 0);
        s.addState(new int[]{android.R.attr.state_focused}, f);
        s.addState(new int[]{android.R.attr.state_pressed}, p);
        s.addState(new int[]{}, round(base, dp(c, radiusDp), strokeColor, strokeColor == 0 ? 0 : dp(c, 1)));
        return s;
    }
}
