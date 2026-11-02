package dev.p71link.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * Pan and pinch a photo inside a watch-shaped frame. The output is rendered at the watch's
 * native pixel size; preview zoom never changes the wire dimensions.
 */
final class CropView extends View {
    private final Bitmap source;
    private final int outW, outH;
    private final float radius;
    private final Matrix matrix = new Matrix();
    private final RectF frame = new RectF();
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint();
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlay = new Paint(Paint.ANTI_ALIAS_FLAG), overlayBox = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Positions read from the watch, or null: then the markers show illustrative defaults. */
    private java.util.List<dev.p71link.protocol.BulkProtocol.LayoutEntry> layout;
    private final ScaleGestureDetector scaler;
    private float lastX, lastY, minScale;
    private boolean initialised;
    /** Position markers stay off until the user asks to see them. */
    boolean showOverlay;

    CropView(Context c, Bitmap source, int outW, int outH, int radius) {
        super(c);
        this.source = source;
        this.outW = outW;
        this.outH = outH;
        this.radius = radius;
        shade.setColor(0xB0101715);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(Ui.dp(c, 2));
        outline.setColor(Ui.ACCENT);
        FaceArtView.styleMarkers(overlay, overlayBox, Ui.dp(c, 1));
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float f = d.getScaleFactor();
                float current = currentScale();
                if (current * f < minScale) f = minScale / current;
                if (current * f > minScale * 6) f = minScale * 6 / current;
                matrix.postScale(f, f, d.getFocusX(), d.getFocusY());
                clamp();
                invalidate();
                return true;
            }
        });
        setContentDescription("Photo crop area. Drag to move the photo and pinch to zoom.");
    }

    /** Use the watch's stored layout for the markers, or null for illustrative default positions. */
    void setLayout(java.util.List<dev.p71link.protocol.BulkProtocol.LayoutEntry> fromWatch) {
        layout = fromWatch;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        int frameW = Math.min(width - Ui.dp(getContext(), 48), Ui.dp(getContext(), 300));
        int height = Math.round(frameW * (float) outH / outW) + Ui.dp(getContext(), 32);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float frameW = Math.min(w - Ui.dp(getContext(), 48), Ui.dp(getContext(), 300));
        float frameH = frameW * outH / outW;
        float left = (w - frameW) / 2f, top = (h - frameH) / 2f;
        frame.set(left, top, left + frameW, top + frameH);
        if (!initialised) {
            minScale = Math.max(frameW / source.getWidth(), frameH / source.getHeight());
            matrix.setScale(minScale, minScale);
            matrix.postTranslate(frame.centerX() - source.getWidth() * minScale / 2f,
                    frame.centerY() - source.getHeight() * minScale / 2f);
            initialised = true;
        }
    }

    private float currentScale() {
        float[] v = new float[9];
        matrix.getValues(v);
        return v[Matrix.MSCALE_X];
    }

    /** Keep the photo covering the whole frame. */
    private void clamp() {
        RectF r = new RectF(0, 0, source.getWidth(), source.getHeight());
        matrix.mapRect(r);
        float dx = 0, dy = 0;
        if (r.left > frame.left) dx = frame.left - r.left;
        if (r.right < frame.right) dx = frame.right - r.right;
        if (r.top > frame.top) dy = frame.top - r.top;
        if (r.bottom < frame.bottom) dy = frame.bottom - r.bottom;
        matrix.postTranslate(dx, dy);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX();
                lastY = e.getY();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!scaler.isInProgress() && e.getPointerCount() == 1) {
                    matrix.postTranslate(e.getX() - lastX, e.getY() - lastY);
                    clamp();
                    invalidate();
                }
                lastX = e.getX();
                lastY = e.getY();
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                int keep = e.getActionIndex() == 0 ? 1 : 0;
                lastX = e.getX(keep);
                lastY = e.getY(keep);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return true;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(Ui.BG);
        c.drawBitmap(source, matrix, image);
        float scale = frame.width() / outW;
        float r = radius * scale;
        Path outside = new Path();
        outside.setFillType(Path.FillType.EVEN_ODD);
        outside.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        outside.addRoundRect(frame, r, r, Path.Direction.CW);
        c.drawPath(outside, shade);
        c.drawRoundRect(frame, r, r, outline);
        if (showOverlay) {
            // Named placeholders mark where the watch draws its own items. They are not watch readings.
            FaceArtView.drawMarkers(c, frame, scale, layout, overlay, overlayBox);
        }
    }

    /** Render the crop at native size, flattened onto black so every pixel is opaque. */
    int[] render() {
        Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.BLACK);
        float s = outW / frame.width();
        c.scale(s, s);
        c.translate(-frame.left, -frame.top);
        c.drawBitmap(source, matrix, image);
        int[] px = new int[outW * outH];
        out.getPixels(px, 0, outW, 0, 0, outW, outH);
        out.recycle();
        for (int i = 0; i < px.length; i++) px[i] |= 0xff000000;
        return px;
    }
}
