package io.github.fantuani.draftcanvas;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import java.util.Arrays;

/** Keeps the settled quadratic segments while the last sample follows the pen tip. */
final class LiveStroke {
    private final Path prefix = new Path();
    private final Path preview = new Path();
    private float[] points = new float[96];
    private int size;
    private float anchorX, anchorY;
    private float anchorWidth, prefixX, prefixY, prefixWidth;
    private boolean previewDirty;

    boolean isEmpty() { return size == 0; }

    void clear() {
        size = 0;
        prefix.rewind();
        preview.rewind();
    }

    boolean add(float x, float y, double scale, float width, float multiplier, boolean variableWidth) {
        if (isEmpty()) {
            if (variableWidth) prefix.addCircle(x, y, width * multiplier / 2, Path.Direction.CCW);
            else prefix.moveTo(x, y);
            anchorX = prefixX = x;
            anchorY = prefixY = y;
            anchorWidth = prefixWidth = multiplier;
        } else {
            float lastX = points[size - 3], lastY = points[size - 2];
            float lastWidth = points[size - 1];
            if (Math.hypot(x - lastX, y - lastY) * scale < .25) {
                if (!variableWidth || size != 3 || multiplier <= lastWidth) return false;
                anchorWidth = prefixWidth = points[2] = multiplier;
                prefix.rewind();
                prefix.addCircle(lastX, lastY, width * multiplier / 2, Path.Direction.CCW);
                return true;
            }
            if (Math.hypot(lastX - anchorX, lastY - anchorY) >= StrokeGeometry.anchorSpacing(width)) {
                float midX = (anchorX + lastX) * .5f, midY = (anchorY + lastY) * .5f;
                float midWidth = (anchorWidth + lastWidth) * .5f;
                if (variableWidth) {
                    PressurePath.quadratic(prefix, prefixX, prefixY, width * prefixWidth / 2,
                            anchorX, anchorY, width * anchorWidth / 2, midX, midY,
                            width * midWidth / 2, pressureStep(width, scale));
                } else prefix.quadTo(anchorX, anchorY, midX, midY);
                prefixX = midX;
                prefixY = midY;
                prefixWidth = midWidth;
                anchorX = lastX;
                anchorY = lastY;
                anchorWidth = lastWidth;
            }
        }
        if (size == points.length) points = Arrays.copyOf(points, size * 2);
        points[size++] = x;
        points[size++] = y;
        points[size++] = multiplier;
        previewDirty = true;
        return true;
    }

    void draw(Canvas canvas, Paint paint, float width, double scale, boolean variableWidth) {
        if (size == 3) {
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(points[0], points[1], width * points[2] / 2, paint);
            return;
        }
        if (previewDirty) {
            float x = points[size - 3], y = points[size - 2];
            float midX = (anchorX + x) * .5f, midY = (anchorY + y) * .5f;
            preview.set(prefix);
            if (variableWidth) {
                float step = pressureStep(width, scale);
                float endR = width * points[size - 1] / 2, anchorR = width * anchorWidth / 2;
                float midR = (anchorR + endR) * .5f;
                PressurePath.quadratic(preview, prefixX, prefixY, width * prefixWidth / 2,
                        anchorX, anchorY, anchorR, midX, midY, midR, step);
                PressurePath.quadratic(preview, midX, midY, midR, x, y, endR, x, y, endR, step);
            } else {
                preview.quadTo(anchorX, anchorY, midX, midY);
                preview.quadTo(x, y, x, y);
            }
            previewDirty = false;
        }
        if (variableWidth) paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(preview, paint);
    }

    private static float pressureStep(float width, double scale) {
        return Math.max((float) (1.5 / scale), width * .25f);
    }

    float[] copyPoints() { return Arrays.copyOf(points, size); }
}
