package dev.p71link.app;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.StateListAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

/** Small programmatic view kit that follows the approved mockup. */
final class Ui {
    static final int BG = 0xFF101715, CARD = 0xFF1D2423, HERO = 0xFF233326, ACCENT = 0xFFC9F57B,
            ON_ACCENT = 0xFF15251D, TEXT = 0xFFF5F7F0, TEXT2 = 0xFFB4C0BA, MUTED = 0xFF9AAFA0,
            DIVIDER = 0xFF29352E, SECONDARY = 0xFF29352E, WARN = 0xFFF2C46D, ERROR = 0xFFFF9B8A, GREEN = 0xFF577E4B;
    /** Hairline border that groups a surface without a heavy shadow. */
    static final int LINE = 0xFF2C3833;
    /** Strong ease-out for interface responses: fast start, soft landing. */
    static final PathInterpolator EASE_OUT = new PathInterpolator(0.23f, 1f, 0.32f, 1f);
    static final long PRESS_MS = 140, RELEASE_MS = 120;

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
        TextView t = text(c, s, 28, TEXT);
        t.setLetterSpacing(-0.01f); // Large headings read better slightly tighter.
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
            pressable(row);
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

    // ---- Motion -------------------------------------------------------------------------------

    /**
     * False when the system disabled or removed animations (animator duration scale 0, which the
     * accessibility "Remove animations" setting also sets). Then no movement plays; colour and ripple
     * still show state. Nothing writes system settings.
     */
    static boolean motionEnabled() { return ValueAnimator.areAnimatorsEnabled(); }

    /** Press feedback: scale to 0.97 in 140 ms with a strong ease-out, back in 120 ms. Interruptible. */
    static void pressable(View v) {
        if (!motionEnabled()) {
            v.setStateListAnimator(null);
            return;
        }
        StateListAnimator s = new StateListAnimator();
        s.addState(new int[]{android.R.attr.state_pressed, android.R.attr.state_enabled}, scaleTo(0.97f, PRESS_MS));
        s.addState(new int[0], scaleTo(1f, RELEASE_MS));
        v.setStateListAnimator(s);
    }

    private static Animator scaleTo(float to, long ms) {
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder((Object) null,
                PropertyValuesHolder.ofFloat(View.SCALE_X, to), PropertyValuesHolder.ofFloat(View.SCALE_Y, to));
        a.setDuration(ms);
        a.setInterpolator(EASE_OUT);
        return a;
    }

    // ---- Icons and grouped surfaces --------------------------------------------------------------

    static ImageView icon(Context c, int res, int color, int sizeDp) {
        ImageView i = new ImageView(c);
        i.setImageResource(res);
        i.setImageTintList(ColorStateList.valueOf(color));
        i.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return i;
    }

    /** A 36 dp rounded tile holding a 20 dp icon: the same size, padding and radius everywhere. */
    static View tile(Context c, int res, int color) {
        android.widget.FrameLayout f = new android.widget.FrameLayout(c);
        f.setBackground(rounded(HERO, dp(c, 10)));
        ImageView i = icon(c, res, color, 20);
        f.addView(i, new android.widget.FrameLayout.LayoutParams(dp(c, 20), dp(c, 20), Gravity.CENTER));
        f.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return f;
    }

    /** One surface for related rows: card colour, 16 dp radius, hairline border. */
    static LinearLayout group(Context c) {
        LinearLayout g = column(c);
        GradientDrawable bg = rounded(CARD, dp(c, 16));
        bg.setStroke(Math.max(1, dp(c, 1)), LINE);
        g.setBackground(bg);
        g.setClipToOutline(true);
        return g;
    }

    /** Add a row to a group, with a hairline divider inset past the icon tile. */
    static void addToGroup(LinearLayout group, View row) { addToGroup(group, row, 62); }

    /** Add a row to a group with a divider inset by {@code insetDp}. */
    static void addToGroup(LinearLayout group, View row, int insetDp) {
        Context c = group.getContext();
        if (group.getChildCount() > 0) {
            View d = new View(c);
            d.setBackgroundColor(DIVIDER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1)));
            lp.setMarginStart(dp(c, insetDp));
            group.addView(d, lp);
        }
        group.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** A grouped row: icon tile, title, optional subtitle, and a trailing view or chevron when tappable. */
    static LinearLayout item(Context c, int iconRes, String title, String sub, View trailing, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, 64));
        row.setPaddingRelative(dp(c, 12), dp(c, 10), dp(c, 14), dp(c, 10));
        row.addView(tile(c, iconRes, TEXT2), new LinearLayout.LayoutParams(dp(c, 36), dp(c, 36)));
        LinearLayout texts = column(c);
        texts.addView(text(c, title, 16, TEXT));
        if (sub != null && !sub.isEmpty()) {
            TextView s = text(c, sub, 13, TEXT2);
            s.setPadding(0, dp(c, 2), 0, 0);
            texts.addView(s);
        }
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tl.setMarginStart(dp(c, 14));
        row.addView(texts, tl);
        if (trailing == null && click != null) trailing = icon(c, R.drawable.ic_chev, MUTED, 18);
        if (trailing != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(dp(c, 12));
            row.addView(trailing, lp);
        }
        if (click != null) {
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33C9F57B), null, new ColorDrawable(Color.WHITE)));
            row.setOnClickListener(click);
            row.setClickable(true);
            row.setFocusable(true);
            pressable(row);
        }
        row.setContentDescription(sub == null || sub.isEmpty() ? title : title + ". " + sub);
        return row;
    }

    /** Stands in for a switch whose value was not read: visibly unknown and never shown as off. */
    static TextView unknownToggle(Context c) {
        TextView t = text(c, "?", 13, TEXT2);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, 12));
        g.setStroke(Math.max(1, dp(c, 1)), TEXT2, dp(c, 3), dp(c, 3));
        t.setBackground(g);
        t.setMinWidth(dp(c, 44));
        t.setMinHeight(dp(c, 26));
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return t;
    }

    /** Small rounded status chip; {@code live} adds an accent dot for a confirmed state. */
    static TextView chip(Context c, String s, boolean live) {
        TextView t = text(c, (live ? "●  " : "") + s, 13, live ? TEXT : TEXT2);
        t.setBackground(rounded(HERO, dp(c, 14)));
        t.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6));
        t.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        return t;
    }

    /**
     * Page title with an optional chip. When both fit on one line at their natural width, the chip sits
     * at the end of the title line. Otherwise (large font or narrow screen) the chip moves below the
     * title, and both wrap within the full width, so neither is squeezed or cut off.
     */
    static ViewGroup header(Context c, String title, View chip) {
        TextView t = title(c, title);
        if (chip == null) {
            LinearLayout row = column(c);
            row.addView(t);
            return row;
        }
        return new HeaderLayout(c, t, chip);
    }

    /** Two children: title then chip, side by side when they fit, otherwise stacked. */
    static final class HeaderLayout extends ViewGroup {
        private final View title, chip;
        private final int gap, stackGap;
        private boolean stacked;

        HeaderLayout(Context c, View title, View chip) {
            super(c);
            this.title = title;
            this.chip = chip;
            gap = dp(c, 12);
            stackGap = dp(c, 4);
            addView(title);
            addView(chip);
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            boolean bounded = MeasureSpec.getMode(ws) != MeasureSpec.UNSPECIFIED;
            int width = MeasureSpec.getSize(ws);
            int natural = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            title.measure(natural, natural);
            chip.measure(natural, natural);
            stacked = bounded && title.getMeasuredWidth() + gap + chip.getMeasuredWidth() > width;
            int height;
            if (stacked) {
                int atMost = MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST);
                title.measure(atMost, natural);
                chip.measure(atMost, natural);
                height = title.getMeasuredHeight() + stackGap + chip.getMeasuredHeight() + dp(getContext(), 4);
            } else {
                height = Math.max(title.getMeasuredHeight(), chip.getMeasuredHeight());
                if (!bounded) width = title.getMeasuredWidth() + gap + chip.getMeasuredWidth();
            }
            setMeasuredDimension(width, resolveSize(height, hs));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int w = r - l, h = b - t;
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            int tw = title.getMeasuredWidth(), th = title.getMeasuredHeight();
            int cw = chip.getMeasuredWidth(), ch = chip.getMeasuredHeight();
            if (stacked) {
                int tx = rtl ? w - tw : 0, cx = rtl ? w - cw : 0;
                title.layout(tx, 0, tx + tw, th);
                chip.layout(cx, th + stackGap, cx + cw, th + stackGap + ch);
            } else {
                int ty = (h - th) / 2, cy = (h - ch) / 2;
                int tx = rtl ? w - tw : 0, cx = rtl ? 0 : w - cw;
                title.layout(tx, ty, tx + tw, ty + th);
                chip.layout(cx, cy, cx + cw, cy + ch);
            }
        }
    }

    /** Section label with an optional quiet text action on the right, at least 48 dp tall. */
    static LinearLayout section(Context c, String s, String action, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        row.addView(section(c, s), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (action != null) row.addView(link(c, action, click));
        return row;
    }

    /** A quiet accent text action with a 48 dp touch target. */
    static TextView link(Context c, String s, View.OnClickListener click) {
        TextView t = text(c, s, 14, ACCENT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinHeight(dp(c, 48));
        t.setPadding(dp(c, 8), 0, dp(c, 4), 0);
        t.setOnClickListener(click);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33C9F57B), null, new ColorDrawable(Color.WHITE)));
        return t;
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
        GradientDrawable bg = rounded(primary ? ACCENT : SECONDARY, dp(c, 14));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x33000000 : 0x33C9F57B), bg, null));
        b.setOnClickListener(click);
        if (click == null) {
            // A button without an action is really disabled, for touch and for screen readers.
            b.setEnabled(false);
            b.setAlpha(0.5f);
            b.setStateListAnimator(null);
        } else {
            pressable(b);
        }
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
