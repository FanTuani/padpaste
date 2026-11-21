package io.github.fantuani.draftcanvas;

import java.util.Arrays;

/** Closed, even-odd selection boundary in canvas coordinates. */
final class Lasso {
    final double[] points;
    final double left, top, right, bottom;

    private Lasso(double[] points) {
        this.points = points;
        double l = points[0], t = points[1], r = l, b = t;
        for (int i = 2; i < points.length; i += 2) {
            l = Math.min(l, points[i]); r = Math.max(r, points[i]);
            t = Math.min(t, points[i + 1]); b = Math.max(b, points[i + 1]);
        }
        left = l; top = t; right = r; bottom = b;
    }

    static Lasso rectangle(double left, double top, double right, double bottom) {
        return new Lasso(new double[]{left, top, right, top, right, bottom, left, bottom});
    }

    Lasso transform(double scale, double dx, double dy) {
        double[] transformed = points.clone();
        for (int i = 0; i < transformed.length; i += 2) {
            transformed[i] = left + (points[i] - left) * scale + dx;
            transformed[i + 1] = top + (points[i + 1] - top) * scale + dy;
        }
        return new Lasso(transformed);
    }

    boolean contains(double x, double y) {
        if (x < left || x > right || y < top || y > bottom) return false;
        boolean inside = false;
        for (int i = 0, j = points.length - 2; i < points.length; j = i, i += 2) {
            double x1 = points[j], y1 = points[j + 1], x2 = points[i], y2 = points[i + 1];
            if (Eraser.distanceSquared(x, y, x1, y1, x2, y2) == 0) return true;
            if ((y1 > y) != (y2 > y) && x < x1 + (x2 - x1) * (y - y1) / (y2 - y1)) inside = !inside;
        }
        return inside;
    }

    boolean intersectsStrokeSegment(double x1, double y1, double x2, double y2, double radius) {
        if (Math.max(x1, x2) + radius < left || Math.min(x1, x2) - radius > right
                || Math.max(y1, y2) + radius < top || Math.min(y1, y2) - radius > bottom) return false;
        if (contains(x1, y1) || contains(x2, y2)) return true;
        double limit = radius * radius;
        for (int i = 0, j = points.length - 2; i < points.length; j = i, i += 2) {
            double a = points[j], b = points[j + 1], c = points[i], d = points[i + 1];
            if (opposite(cross(x1, y1, x2, y2, a, b), cross(x1, y1, x2, y2, c, d))
                    && opposite(cross(a, b, c, d, x1, y1), cross(a, b, c, d, x2, y2))) return true;
            if (Eraser.distanceSquared(x1, y1, a, b, c, d) <= limit
                    || Eraser.distanceSquared(x2, y2, a, b, c, d) <= limit
                    || Eraser.distanceSquared(a, b, x1, y1, x2, y2) <= limit
                    || Eraser.distanceSquared(c, d, x1, y1, x2, y2) <= limit) return true;
        }
        return false;
    }

    private static double cross(double ax, double ay, double bx, double by, double x, double y) {
        return (bx - ax) * (y - ay) - (by - ay) * (x - ax);
    }

    private static boolean opposite(double a, double b) {
        return (a < 0 && b > 0) || (a > 0 && b < 0);
    }

    static final class Trace {
        double[] points = new double[64];
        int size;

        void clear() { size = 0; }

        boolean add(double x, double y, double minDistance) {
            if (size != 0 && Math.hypot(x - points[size - 2], y - points[size - 1]) < minDistance) return false;
            if (size == points.length) points = Arrays.copyOf(points, size * 2);
            points[size++] = x;
            points[size++] = y;
            return true;
        }

        Lasso finish(double minimumArea) {
            if (size < 6) return null;
            int farthest = 2;
            double span = 0;
            for (int i = 2; i < size; i += 2) {
                double distance = Math.hypot(points[i] - points[0], points[i + 1] - points[1]);
                if (distance > span) {
                    farthest = i;
                    span = distance;
                }
            }
            for (int i = 2; i < size; i += 2) {
                if (Math.abs(cross(points[0], points[1], points[farthest], points[farthest + 1],
                        points[i], points[i + 1]))
                        >= minimumArea) return new Lasso(Arrays.copyOf(points, size));
            }
            return null;
        }
    }
}
