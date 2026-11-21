package io.github.fantuani.draftcanvas;

import android.graphics.Path;

/** Builds the filled envelope of a centerline with a radius at each point. */
final class PressurePath {
    private PressurePath() {}

    static void append(Path path, StrokeData data, double start, double end, float radius) {
        float x = data.coordinate(start, 0), y = data.coordinate(start, 1);
        float r = radius * data.coordinate(start, 2);
        path.addCircle(x, y, r, Path.Direction.CCW);
        while (start < end) {
            start = Math.min(end, Math.floor(start) + 1);
            float nextX = data.coordinate(start, 0), nextY = data.coordinate(start, 1);
            float nextR = radius * data.coordinate(start, 2);
            segment(path, x, y, r, nextX, nextY, nextR);
            x = nextX;
            y = nextY;
            r = nextR;
        }
    }

    static void quadratic(Path path, float x, float y, float r, float cx, float cy, float cr,
                          float ex, float ey, float er, float step) {
        int count = StrokeGeometry.subdivisions(x, y, cx, cy, ex, ey, step);
        float lastX = x, lastY = y, lastR = r;
        for (int i = 1; i <= count; i++) {
            float t = (float) i / count;
            float nextX = StrokeGeometry.curve(x, cx, ex, t);
            float nextY = StrokeGeometry.curve(y, cy, ey, t);
            float nextR = StrokeGeometry.curve(r, cr, er, t);
            segment(path, lastX, lastY, lastR, nextX, nextY, nextR);
            lastX = nextX;
            lastY = nextY;
            lastR = nextR;
        }
    }

    private static void segment(Path path, float x, float y, float r, float nx, float ny, float nr) {
        path.addCircle(nx, ny, nr, Path.Direction.CCW);
        double dx = nx - x, dy = ny - y, distance = Math.hypot(dx, dy);
        double delta = r - nr;
        if (distance <= Math.abs(delta)) return;

        // The two outer tangents meet the endpoint circles without a ridge.
        double along = delta / distance, across = Math.sqrt(1 - along * along);
        float ax = (float) ((along * dx - across * dy) / distance);
        float ay = (float) ((along * dy + across * dx) / distance);
        float bx = (float) ((along * dx + across * dy) / distance);
        float by = (float) ((along * dy - across * dx) / distance);
        path.moveTo(x + r * ax, y + r * ay);
        path.lineTo(nx + nr * ax, ny + nr * ay);
        path.lineTo(nx + nr * bx, ny + nr * by);
        path.lineTo(x + r * bx, y + r * by);
        path.close();
    }
}
