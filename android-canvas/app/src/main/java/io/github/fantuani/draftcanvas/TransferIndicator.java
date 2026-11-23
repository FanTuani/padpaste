package io.github.fantuani.draftcanvas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

import io.github.fantuani.padpaste.DuplexSync;

final class TransferIndicator extends View {
    private final DuplexSync link;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private DuplexSync.TransferProgress progress;
    private long shownAt;
    private final Runnable frame = this::refresh;

    TransferIndicator(Context context) {
        super(context);
        link = DuplexSync.get(context);
        setVisibility(GONE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void start() {
        DuplexSync.TransferProgress current = link.transferProgress();
        if (current != null && current.finished()) {
            progress = current;
            shownAt = SystemClock.uptimeMillis() - 360;
        }
        link.observeProgress(this::refresh);
    }

    void stop() {
        link.observeProgress(null);
        removeCallbacks(frame);
        progress = null;
        setVisibility(GONE);
    }

    private void refresh() {
        removeCallbacks(frame);
        long now = SystemClock.uptimeMillis();
        DuplexSync.TransferProgress current = link.transferProgress();
        if (current == null) return;
        if (current != progress) {
            progress = current;
            shownAt = now;
            setContentDescription(current.uploading ? "正在上传截图" : "正在下载题图");
        }
        if (current.finished() && now - shownAt >= 360) {
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);
        invalidate();
        postDelayed(frame, 32);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (progress == null) return;
        float x = getWidth() * .5f, y = getHeight() * .5f;
        float unit = Math.min(x, y) / 16f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xF7FAFAF7);
        canvas.drawCircle(x, y, 15 * unit, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(2 * unit);
        float radius = 12 * unit;
        paint.setColor(0xFFC7DCD6);
        canvas.drawCircle(x, y, radius, paint);
        paint.setColor(0xFF167B70);
        float fraction = progress.fraction();
        if (fraction > 0) {
            canvas.drawArc(x - radius, y - radius, x + radius, y + radius,
                    -90, 360 * fraction, false, paint);
        }
        float tip = (progress.uploading ? -5 : 5) * unit;
        float wing = (progress.uploading ? -1 : 1) * unit;
        canvas.drawLine(x, y - tip, x, y + tip, paint);
        canvas.drawLine(x - 4 * unit, y + wing, x, y + tip, paint);
        canvas.drawLine(x + 4 * unit, y + wing, x, y + tip, paint);
    }
}
