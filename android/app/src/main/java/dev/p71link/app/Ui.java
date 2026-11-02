package dev.p71link.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

/** Small programmatic view kit that follows the approved mockup. */
final class Ui {
    static final int BG = 0xFF101715, CARD = 0xFF1D2423, HERO = 0xFF233326, ACCENT = 0xFFC9F57B,
            ON_ACCENT = 0xFF15251D, TEXT = 0xFFF5F7F0, TEXT2 = 0xFFB4C0BA, MUTED = 0xFF9AAFA0,
            DIVIDER = 0xFF29352E, SECONDARY = 0xFF29352E, WARN = 0xFFF2C46D, ERROR = 0xFFFF9B8A, GREEN = 0xFF577E4B;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    static TextView title(Context c, String s) {
        TextView t = text(c, s, 30, TEXT);
        t.setPadding(0, dp(c, 12), 0, dp(c, 4));
        t.setAccessibilityHeading(true);
        return t;
    }

    static TextView subtitle(Context c, String s) {
        TextView t = text(c, s, 16, TEXT2);
        t.setPadding(0, 0, 0, dp(c, 16));
        return t;
    }

    static TextView section(Context c, String s) {
        TextView t = text(c, s.toUpperCase(), 12, MUTED);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(c, 4), dp(c, 18), 0, dp(c, 8));
        t.setAccessibilityHeading(true);
        return t;
    }

    static TextView note(Context c, String s) {
        TextView t = text(c, s, 13, MUTED);
        t.setPadding(dp(c, 4), dp(c, 14), dp(c, 4), dp(c, 6));
        return t;
    }

    static TextView status(Context c, String s, int color) {
        TextView t = text(c, s, 14, color);
        t.setPadding(dp(c, 4), dp(c, 8), dp(c, 4), dp(c, 8));
        t.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        return t;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout card(Context c, int color) {
        LinearLayout l = column(c);
        l.setBackground(rounded(color, dp(c, 18)));
        int p = dp(c, 18);
        l.setPadding(p, p, p, p);
        return l;
    }

    static LinearLayout.LayoutParams spaced(Context c, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, topDp);
        return lp;
    }

    static void add(LinearLayout parent, View child, int topDp) { parent.addView(child, spaced(parent.getContext(), topDp)); }

    /** A tappable row with a title, an optional subtitle, and a trailing view or chevron. */
    static LinearLayout row(Context c, String title, String sub, View trailing, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, 64));
        int p = dp(c, 16);
        row.setPadding(p, dp(c, 12), p, dp(c, 12));
        GradientDrawable bg = rounded(CARD, dp(c, 16));
        row.setBackground(click == null ? bg : new RippleDrawable(ColorStateList.valueOf(0x33C9F57B), bg, null));
        LinearLayout texts = column(c);
        TextView t = text(c, title, 16, TEXT);
        texts.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = text(c, sub, 13, TEXT2);
            s.setPadding(0, dp(c, 2), 0, 0);
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (trailing == null && click != null) trailing = chevron(c);
        if (trailing != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(c, 12);
            row.addView(trailing, lp);
        }
        if (click != null) {
            row.setOnClickListener(click);
            row.setClickable(true);
            row.setFocusable(true);
        }
        row.setContentDescription(sub == null ? title : title + ". " + sub);
        return row;
    }

    static TextView chevron(Context c) {
        TextView t = text(c, "›", 26, ACCENT);
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return t;
    }

    static Switch toggle(Context c, boolean on, boolean enabled, String label) {
        Switch s = new Switch(c);
        s.setChecked(on);
        s.setEnabled(enabled);
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[]{ON_ACCENT, 0xFF62756B}));
        s.setTrackTintList(new ColorStateList(states, new int[]{ACCENT, 0xFF45504B}));
        s.setMinimumHeight(dp(c, 48));
        s.setMinimumWidth(dp(c, 56));
        s.setContentDescription(label);
        return s;
    }

    static Button button(Context c, String label, boolean primary, View.OnClickListener click) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? ON_ACCENT : TEXT);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        b.setMinHeight(dp(c, 52));
        b.setStateListAnimator(null);
        GradientDrawable bg = rounded(primary ? ACCENT : SECONDARY, dp(c, 14));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x33000000 : 0x33C9F57B), bg, null));
        b.setOnClickListener(click);
        return b;
    }

    static LinearLayout buttonPair(Context c, Button a, Button b) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lb.leftMargin = dp(c, 12);
        l.addView(a, la);
        l.addView(b, lb);
        return l;
    }

    static ProgressBar progress(Context c) {
        ProgressBar p = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        p.setProgressTintList(ColorStateList.valueOf(ACCENT));
        p.setProgressBackgroundTintList(ColorStateList.valueOf(DIVIDER));
        return p;
    }

    static String hhmm(int h, int m) { return String.format(java.util.Locale.ROOT, "%02d:%02d", h, m); }
}
