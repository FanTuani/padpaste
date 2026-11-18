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
    private boolean previewDirty;

    boolean isEmpty() { return size == 0; }

    void clear() {
        size = 0;
        prefix.rewind();
        preview.rewind();
    }

    boolean add(float x, float y, double scale, float width) {
        if (isEmpty()) {
            prefix.moveTo(x, y);
            anchorX = x;
            anchorY = y;
        } else {
            float lastX = points[size - 3], lastY = points[size - 2];
            if (Math.hypot(x - lastX, y - lastY) * scale < .25) return false;
            if (Math.hypot(lastX - anchorX, lastY - anchorY) >= StrokeGeometry.anchorSpacing(width)) {
                prefix.quadTo(anchorX, anchorY, (anchorX + lastX) * .5f, (anchorY + lastY) * .5f);
                anchorX = lastX;
                anchorY = lastY;
            }
        }
        if (size == points.length) points = Arrays.copyOf(points, size * 2);
        points[size++] = x;
        points[size++] = y;
        points[size++] = 1f;
        previewDirty = true;
        return true;
    }

    void draw(Canvas canvas, Paint paint, float width) {
        if (size == 3) {
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(points[0], points[1], width / 2, paint);
            return;
        }
        if (previewDirty) {
            float x = points[size - 3], y = points[size - 2];
            preview.set(prefix);
            preview.quadTo(anchorX, anchorY, (anchorX + x) * .5f, (anchorY + y) * .5f);
            preview.quadTo(x, y, x, y);
            previewDirty = false;
        }
        canvas.drawPath(preview, paint);
    }

    float[] copyPoints() { return Arrays.copyOf(points, size); }
}
