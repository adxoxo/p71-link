package dev.p71link.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

import dev.p71link.protocol.BulkProtocol;

import java.util.List;

/**
 * A face image in a watch-shaped frame: verified artwork, or a stable placeholder that invents nothing.
 * The image is fitted without distortion. Position markers are optional and never show readings.
 */
final class FaceArtView extends View {
    static final int W = PhotoPage.DEFAULT_W, H = PhotoPage.DEFAULT_H;

    private Bitmap bitmap;
    private String placeholder = "Preview unavailable";
    private boolean current, markers, add;
    private List<BulkProtocol.LayoutEntry> layout;
    private int maxWidth;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), image = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG),
            dashed = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
            markerText = new Paint(Paint.ANTI_ALIAS_FLAG), markerBox = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG), badge = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frame = new RectF(), dst = new RectF();
    private final Path clip = new Path();

    FaceArtView(Context c) {
        super(c);
        dashed.setStyle(Paint.Style.STROKE);
        dashed.setStrokeWidth(Ui.dp(c, 1.5f));
        dashed.setColor(0xFF45504B);
        dashed.setPathEffect(new DashPathEffect(new float[]{Ui.dp(c, 5), Ui.dp(c, 4)}, 0));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Ui.dp(c, 2.5f));
        stroke.setColor(Ui.ACCENT);
        label.setColor(Ui.TEXT2);
        label.setTextSize(Ui.dp(c, 13));
        badge.setColor(Ui.ON_ACCENT);
        badge.setTextSize(Ui.dp(c, 12));
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        styleMarkers(markerText, markerBox, Ui.dp(c, 1));
        // The surrounding card or caption describes the image for accessibility.
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    static void styleMarkers(Paint text, Paint box, float px) {
        text.setColor(0xFFFFFFFF);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        box.setStyle(Paint.Style.STROKE);
        box.setStrokeWidth(px);
        box.setColor(0xCCFFFFFF);
        box.setPathEffect(new DashPathEffect(new float[]{3 * px, 2 * px}, 0));
    }

    /** Show {@code b}, or {@code text} when there is no verified image. */
    void setArt(Bitmap b, String text) {
        bitmap = b;
        placeholder = text;
        invalidate();
    }

    /** Placeholder with a plus symbol, for an action card rather than a missing image. */
    void setAdd(String text) {
        bitmap = null;
        placeholder = text;
        add = true;
        invalidate();
    }

    void setCurrent(boolean c) {
        current = c;
        invalidate();
    }

    /** Draw position markers from the watch's stored layout, or illustrative defaults when it is null. */
    void setMarkers(boolean show, List<BulkProtocol.LayoutEntry> fromWatch) {
        markers = show;
        layout = fromWatch;
        invalidate();
    }

    void setMaxWidth(int px) {
        maxWidth = px;
        requestLayout();
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getMode(ws) == MeasureSpec.UNSPECIFIED ? Ui.dp(getContext(), 160) : MeasureSpec.getSize(ws);
        if (maxWidth > 0) w = Math.min(w, maxWidth);
        setMeasuredDimension(w, Math.round(w * (float) H / W));
    }

    @Override
    protected void onDraw(Canvas c) {
        float inset = stroke.getStrokeWidth() / 2f;
        frame.set(inset, inset, getWidth() - inset, getHeight() - inset);
        float scale = frame.width() / W;
        float r = PhotoPage.RADIUS * scale;
        fill.setColor(Ui.HERO);
        c.drawRoundRect(frame, r, r, fill);
        if (bitmap != null && !bitmap.isRecycled()) {
            float s = Math.min(frame.width() / bitmap.getWidth(), frame.height() / bitmap.getHeight());
            float bw = bitmap.getWidth() * s, bh = bitmap.getHeight() * s;
            dst.set(frame.centerX() - bw / 2f, frame.centerY() - bh / 2f, frame.centerX() + bw / 2f, frame.centerY() + bh / 2f);
            clip.reset();
            clip.addRoundRect(frame, r, r, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawBitmap(bitmap, null, dst, image);
            c.restore();
        } else {
            drawPlaceholder(c, r);
        }
        if (markers) drawMarkers(c, frame, scale, layout, markerText, markerBox);
        if (current) {
            c.drawRoundRect(frame, r, r, stroke);
            String t = "Current";
            float tw = badge.measureText(t), pad = Ui.dp(getContext(), 10), h = Ui.dp(getContext(), 22);
            RectF pill = new RectF(frame.centerX() - tw / 2f - pad, frame.top + Ui.dp(getContext(), 8),
                    frame.centerX() + tw / 2f + pad, frame.top + Ui.dp(getContext(), 8) + h);
            fill.setColor(Ui.ACCENT);
            c.drawRoundRect(pill, h / 2f, h / 2f, fill);
            Paint.FontMetrics fm = badge.getFontMetrics();
            c.drawText(t, pill.left + pad, pill.centerY() - (fm.ascent + fm.descent) / 2f, badge);
        }
    }

    private void drawPlaceholder(Canvas c, float r) {
        float in = frame.width() * 0.06f;
        RectF box = new RectF(frame.left + in, frame.top + in, frame.right - in, frame.bottom - in);
        c.drawRoundRect(box, Math.max(0, r - in), Math.max(0, r - in), dashed);
        // A simple crossed-out picture symbol.
        float iw = Math.min(frame.width() * 0.22f, Ui.dp(getContext(), 34)), ih = iw * 0.78f;
        float cx = frame.centerX(), top = frame.centerY() - ih - Ui.dp(getContext(), 6);
        RectF icon = new RectF(cx - iw / 2f, top, cx + iw / 2f, top + ih);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.dp(getContext(), 1.5f));
        p.setColor(Ui.MUTED);
        if (add) {
            float rr = ih / 2f + Ui.dp(getContext(), 2);
            p.setColor(Ui.ACCENT);
            c.drawCircle(cx, icon.centerY(), rr, p);
            c.drawLine(cx - rr * 0.5f, icon.centerY(), cx + rr * 0.5f, icon.centerY(), p);
            c.drawLine(cx, icon.centerY() - rr * 0.5f, cx, icon.centerY() + rr * 0.5f, p);
        } else {
            c.drawRoundRect(icon, Ui.dp(getContext(), 3), Ui.dp(getContext(), 3), p);
            c.drawLine(icon.left - iw * 0.12f, icon.top - ih * 0.12f, icon.right + iw * 0.12f, icon.bottom + ih * 0.12f, p);
        }
        if (placeholder == null || placeholder.isEmpty()) return;
        int width = Math.max(1, Math.round(box.width() - 2 * Ui.dp(getContext(), 8)));
        StaticLayout l = StaticLayout.Builder.obtain(placeholder, 0, placeholder.length(), label, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(3).build();
        c.save();
        c.translate(cx - width / 2f, icon.bottom + Ui.dp(getContext(), 10));
        l.draw(c);
        c.restore();
    }

    /**
     * Bracketed labels where the watch draws its own time, battery and data. With a layout read from
     * the watch they use its positions; otherwise they use the typical defaults, which callers label as
     * illustrative. They are never readings and are never sent.
     */
    static void drawMarkers(Canvas c, RectF frame, float scale, List<BulkProtocol.LayoutEntry> layout, Paint text, Paint box) {
        int[][] defaults = {{BulkProtocol.LayoutEntry.CLOCK, 28, 78}, {BulkProtocol.LayoutEntry.BATTERY, 93, 17},
                {BulkProtocol.LayoutEntry.DATA, 39, 215}};
        Rect bounds = new Rect();
        for (int[] d : defaults) {
            int x = d[1], y = d[2];
            if (layout != null) {
                BulkProtocol.LayoutEntry e = null;
                for (BulkProtocol.LayoutEntry l : layout) if (l.type == d[0]) e = l;
                if (e == null) continue;
                x = e.x;
                y = e.y;
            }
            boolean time = d[0] == BulkProtocol.LayoutEntry.CLOCK;
            String s = time ? "[time]" : d[0] == BulkProtocol.LayoutEntry.BATTERY ? "[battery]" : "[data]";
            text.setTextSize((time ? 30 : 16) * scale);
            float baseline = frame.top + (y + (time ? 34 : d[0] == BulkProtocol.LayoutEntry.BATTERY ? 20 : 26)) * scale;
            float left = frame.left + x * scale;
            c.drawText(s, left, baseline, text);
            text.getTextBounds(s, 0, s.length(), bounds);
            float pad = 3 * scale;
            c.drawRect(left + bounds.left - pad, baseline + bounds.top - pad, left + bounds.right + pad, baseline + bounds.bottom + pad, box);
        }
    }
}
