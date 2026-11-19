package io.github.fantuani.draftcanvas;

import android.graphics.*;
import android.os.*;

import java.util.*;
import java.util.concurrent.*;

/** Offscreen ink tiles live in canvas coordinates and are shared by translation frames. */
final class AsyncInkTiles {
    private static final int SIZE = 256;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task ->
            new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                task.run();
            }, "draft-raster"));
    private final Runnable invalidate;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF destination = new RectF();
    private final Map<Ink.Stroke, Path> paths = new LinkedHashMap<Ink.Stroke, Path>(1024, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Ink.Stroke, Path> entry) {
            return size() > 1024;
        }
    };
    private final ArrayList<Op> ops = new ArrayList<>();
    private Phase phase, previous;
    private Camera camera;
    private Ink document;
    private Ink.Stroke[] snapshot;
    private boolean busy, closed;
    private long sequence, epoch, seenRevision = -1;
    private BitmapShader paper;
    private final Matrix shaderMatrix = new Matrix();
    private double paperStep, patternScale;
    private float patternDensity;

    AsyncInkTiles(Runnable invalidate) {
        this.invalidate = invalidate;
    }

    static final class Camera {
        final int width, height;
        final double x, y, scale;
        final float density;

        Camera(int width, int height, double x, double y, double scale, float density) {
            this.width = width;
            this.height = height;
            this.x = x;
            this.y = y;
            this.scale = scale;
            this.density = density;
        }

        double wx(double sx) { return x + (sx - width / 2.0) / scale; }
        double wy(double sy) { return y + (sy - height / 2.0) / scale; }
        float sx(double wx) { return (float) ((wx - x) * scale + width / 2.0); }
        float sy(double wy) { return (float) ((wy - y) * scale + height / 2.0); }
    }

    private static final class Range {
        final int left, top, right, bottom;

        Range(Phase phase, Camera camera, int padding) {
            left = phase.column(camera.wx(-padding * (double) camera.width));
            top = phase.row(camera.wy(-padding * (double) camera.height));
            right = phase.column(camera.wx((1 + padding) * (double) camera.width));
            bottom = phase.row(camera.wy((1 + padding) * (double) camera.height));
        }

        boolean same(Range other) {
            return other != null && left == other.left && top == other.top
                    && right == other.right && bottom == other.bottom;
        }

        boolean contains(Tile tile) {
            return tile.column >= left && tile.column <= right && tile.row >= top && tile.row <= bottom;
        }
    }

    private static final class Tile {
        final int column, row;
        final double left, top, right, bottom;
        Bitmap bitmap;
        long sequence = -1, dirty, drawModelUntil = -1;

        Tile(Phase phase, int column, int row, long dirty) {
            this.column = column;
            this.row = row;
            left = phase.left + column * phase.span;
            top = phase.top + row * phase.span;
            right = phase.left + (column + 1.0) * phase.span;
            bottom = phase.top + (row + 1.0) * phase.span;
            this.dirty = dirty;
        }

        boolean needsRender() { return sequence < dirty; }
        long key() { return key(column, row); }
        static long key(int column, int row) { return ((long) column << 32) | (row & 0xffffffffL); }
    }

    private static final class Phase {
        final double left, top, scale, span;
        final Map<Long, Tile> tiles = new HashMap<>();
        Range visible, nearby;
        boolean haloIndexed;
        int bitmapCount;

        Phase(Camera camera) {
            scale = camera.scale;
            span = SIZE / scale;
            left = camera.wx(0);
            top = camera.wy(0);
        }

        int column(double x) { return (int) Math.floor((x - left) / span); }
        int row(double y) { return (int) Math.floor((y - top) / span); }
        Tile get(int column, int row) { return tiles.get(Tile.key(column, row)); }

        Tile add(int column, int row, long sequence) {
            Tile tile = new Tile(this, column, row, sequence);
            tiles.put(tile.key(), tile);
            return tile;
        }

        void removeOutside(Range range) {
            Iterator<Tile> entries = tiles.values().iterator();
            while (entries.hasNext()) {
                Tile tile = entries.next();
                if (!range.contains(tile)) {
                    if (tile.bitmap != null) bitmapCount--;
                    entries.remove();
                }
            }
        }
    }

    private static final class Op {
        final long seq;
        final double ax, ay, bx, by, radius, left, top, right, bottom;
        final Ink.Stroke stroke;
        Path path;

        Op(long seq, double ax, double ay, double bx, double by, double radius) {
            this.seq = seq;
            this.ax = ax;
            this.ay = ay;
            this.bx = bx;
            this.by = by;
            this.radius = radius;
            stroke = null;
            left = Math.min(ax, bx) - radius;
            top = Math.min(ay, by) - radius;
            right = Math.max(ax, bx) + radius;
            bottom = Math.max(ay, by) + radius;
        }

        Op(long seq, Ink.Stroke stroke) {
            this.seq = seq;
            this.stroke = stroke;
            ax = ay = bx = by = radius = 0;
            left = stroke.left;
            top = stroke.top;
            right = stroke.right;
            bottom = stroke.bottom;
        }

        boolean intersects(Tile tile) {
            return right >= tile.left && left <= tile.right && bottom >= tile.top && top <= tile.bottom;
        }
    }

    void noteRevision(Ink ink) { seenRevision = ink.revision; }

    void modelChanged(Ink ink) {
        document = ink;
        if (seenRevision == ink.revision) return;
        seenRevision = ink.revision;
        epoch++;
        sequence++;
        snapshot = null;
        ops.clear();
        previous = null;
        if (phase != null) {
            for (Tile tile : phase.tiles.values()) tile.dirty = sequence;
        }
    }

    void stroke(Ink ink, Ink.Stroke stroke) {
        document = ink;
        seenRevision = ink.revision;
        Op op = new Op(++sequence, stroke);
        ops.add(op);
        dirty(op.left, op.top, op.right, op.bottom, false);
    }

    void erase(Ink ink, double ax, double ay, double bx, double by, double radius, double padding) {
        document = ink;
        seenRevision = ink.revision;
        Op op = new Op(++sequence, ax, ay, bx, by, radius);
        ops.add(op);
        dirty(op.left - padding, op.top - padding, op.right + padding, op.bottom + padding, false);
    }

    void move(Ink ink, double[] from, double dx, double dy) {
        document = ink;
        seenRevision = ink.revision;
        sequence++;
        dirty(from[0], from[1], from[2], from[3], true);
        dirty(from[0] + dx, from[1] + dy, from[2] + dx, from[3] + dy, true);
    }

    private void dirty(double left, double top, double right, double bottom, boolean drawModel) {
        snapshot = null;
        previous = null;
        if (phase == null) return;
        for (Tile tile : phase.tiles.values()) {
            if (right >= tile.left && left <= tile.right && bottom >= tile.top && top <= tile.bottom) {
                tile.dirty = sequence;
                if (drawModel) tile.drawModelUntil = sequence;
            }
        }
    }

    void draw(Canvas canvas, Ink ink, Camera nextCamera, boolean navigating, boolean fillMissing,
              InkTiles.Drawer drawModel) {
        document = ink;
        camera = nextCamera;
        if (seenRevision < 0) seenRevision = ink.revision;
        if (ink.strokes.isEmpty()) {
            if (phase != null || previous != null) epoch++;
            phase = previous = null;
            snapshot = null;
            ops.clear();
            return;
        }
        if (needsNewPhase(navigating)) {
            // Keep an already rendered view as a temporary fallback when the raster resolution changes.
            if (phase != null && (previous == null || visibleReady())) {
                phase.removeOutside(phase.visible);
                previous = phase;
            }
            phase = new Phase(camera);
        }
        phase.visible = new Range(phase, camera, 0);
        Range nearby = new Range(phase, camera, 1);
        if (!nearby.same(phase.nearby)) {
            phase.nearby = nearby;
            phase.haloIndexed = false;
            phase.removeOutside(nearby);
        }
        if (visibleReady()) previous = null;
        if (!ops.isEmpty()) updatePaperShader();

        for (int row = phase.visible.top; row <= phase.visible.bottom; row++) {
            for (int col = phase.visible.left; col <= phase.visible.right; col++) {
                Tile tile = phase.get(col, row);
                if (tile == null) tile = phase.add(col, row, sequence);
                drawTile(canvas, tile, fillMissing, drawModel);
            }
        }
        pump();
    }

    private boolean needsNewPhase(boolean navigating) {
        if (phase == null) return true;
        double ratio = camera.scale / phase.scale;
        return ratio < .8 || ratio > 1.25 || (!navigating && ratio != 1)
                || Math.abs((camera.x - phase.left) / phase.span) > 1_000_000
                || Math.abs((camera.y - phase.top) / phase.span) > 1_000_000;
    }

    private void updatePaperShader() {
        double step = camera.density * 32;
        while (step * camera.scale < camera.density * 22) step *= 2;
        while (step * camera.scale > camera.density * 60) step /= 2;
        if (paper == null || step != paperStep || patternScale != camera.scale || patternDensity != camera.density) {
            paperStep = step;
            patternScale = camera.scale;
            patternDensity = camera.density;
            Bitmap pattern = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(pattern);
            c.drawColor(0xFFFAFAF7);
            Paint dots = new Paint(Paint.ANTI_ALIAS_FLAG);
            dots.setColor(0xFFB9C4BC);
            float radius = (float) (camera.density * .8 / camera.scale * 128 / step);
            c.drawCircle(0, 0, radius, dots);
            c.drawCircle(0, 128, radius, dots);
            c.drawCircle(128, 0, radius, dots);
            c.drawCircle(128, 128, radius, dots);
            paper = new BitmapShader(pattern, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        }
        shaderMatrix.setScale((float) (step / 128), (float) (step / 128));
        shaderMatrix.postTranslate((float) (-camera.x % step), (float) (-camera.y % step));
        paper.setLocalMatrix(shaderMatrix);
    }

    private void drawTile(Canvas canvas, Tile tile, boolean fillMissing, InkTiles.Drawer drawModel) {
        float left = camera.sx(tile.left), top = camera.sy(tile.top);
        float right = camera.sx(tile.right), bottom = camera.sy(tile.bottom);
        canvas.save();
        canvas.clipRect(left, top, right, bottom);
        if (tile.sequence < tile.drawModelUntil || (fillMissing && tile.sequence < 0 && containsInk(tile))) {
            // A move removes ink from its old position and can uncover earlier strokes.
            // Draw current vectors until this tile contains that revision.
            drawModel.draw(canvas, (int) Math.floor(left), (int) Math.floor(top),
                    (int) Math.ceil(right), (int) Math.ceil(bottom));
            canvas.restore();
            return;
        }
        if (tile.sequence < 0) {
            if (containsInk(tile)) {
                drawFallback(canvas, tile);
            } else {
                // Empty space never needs a bitmap or a trip through the worker.
                tile.sequence = sequence;
            }
        }
        drawBitmap(canvas, tile);
        drawOps(canvas, tile);
        canvas.restore();
    }

    private void drawFallback(Canvas canvas, Tile region) {
        if (previous == null || !ops.isEmpty()) return;
        double ratio = camera.scale / previous.scale;
        if (ratio < .5 || ratio > 2) return;
        int left = previous.column(region.left), right = previous.column(region.right);
        int top = previous.row(region.top), bottom = previous.row(region.bottom);
        for (int row = top; row <= bottom; row++) {
            for (int col = left; col <= right; col++) {
                Tile tile = previous.get(col, row);
                if (tile != null && tile.sequence >= tile.drawModelUntil) drawBitmap(canvas, tile);
            }
        }
    }

    private void drawBitmap(Canvas canvas, Tile tile) {
        if (tile.bitmap == null) return;
        destination.set(camera.sx(tile.left), camera.sy(tile.top), camera.sx(tile.right), camera.sy(tile.bottom));
        canvas.drawBitmap(tile.bitmap, null, destination, bitmapPaint);
    }

    private void drawOps(Canvas canvas, Tile tile) {
        if (ops.isEmpty()) return;
        canvas.save();
        canvas.translate(camera.width / 2f, camera.height / 2f);
        canvas.scale((float) camera.scale, (float) camera.scale);
        for (Op op : ops) {
            if (op.seq <= tile.sequence || !op.intersects(tile)) continue;
            if (op.stroke == null) {
                overlayPaint.setShader(paper);
                overlayPaint.setStyle(Paint.Style.STROKE);
                overlayPaint.setStrokeCap(Paint.Cap.ROUND);
                overlayPaint.setStrokeWidth((float) (op.radius * 2));
                canvas.drawLine((float) (op.ax - camera.x), (float) (op.ay - camera.y),
                        (float) (op.bx - camera.x), (float) (op.by - camera.y), overlayPaint);
            } else {
                Ink.Stroke stroke = op.stroke;
                if (op.path == null) op.path = strokePath(stroke);
                overlayPaint.setShader(null);
                canvas.save();
                canvas.translate((float) (stroke.x - camera.x), (float) (stroke.y - camera.y));
                drawStroke(canvas, stroke, op.path, overlayPaint);
                canvas.restore();
            }
        }
        canvas.restore();
    }

    private boolean containsInk(Tile tile) {
        return containsInk(tile.left, tile.top, tile.right, tile.bottom);
    }

    private boolean containsInk(double left, double top, double right, double bottom) {
        for (Ink.Stroke stroke : document.strokes) {
            if (stroke.intersects(left, top, right, bottom)) return true;
        }
        return false;
    }

    boolean recordZoomPreview(Canvas canvas, Ink ink, double left, double top, double scale,
                              int width, int height) {
        if (phase == null || phase.scale != scale || seenRevision != ink.revision || !ops.isEmpty()) return false;
        int firstCol = phase.column(left), firstRow = phase.row(top);
        int lastCol = phase.column(left + width / scale), lastRow = phase.row(top + height / scale);
        for (int row = firstRow; row <= lastRow; row++) {
            for (int col = firstCol; col <= lastCol; col++) {
                Tile tile = phase.get(col, row);
                if (tile != null && !tile.needsRender()) continue;
                double x = phase.left + col * phase.span, y = phase.top + row * phase.span;
                if (containsInk(x, y, x + phase.span, y + phase.span)
                        || (tile != null && tile.bitmap != null)) return false;
            }
        }
        for (int row = firstRow; row <= lastRow; row++) {
            for (int col = firstCol; col <= lastCol; col++) {
                Tile tile = phase.get(col, row);
                if (tile == null || tile.bitmap == null) continue;
                destination.set((float) ((tile.left - left) * scale), (float) ((tile.top - top) * scale),
                        (float) ((tile.right - left) * scale), (float) ((tile.bottom - top) * scale));
                canvas.drawBitmap(tile.bitmap, null, destination, bitmapPaint);
            }
        }
        return true;
    }

    private void pump() {
        if (closed || busy || phase == null || document == null) return;
        Phase target = phase;
        Tile tile = nextTile(target);
        if (tile == null) {
            pruneOps();
            return;
        }
        busy = true;
        long revision = sequence, generation = epoch;
        if (snapshot == null) snapshot = document.strokes.toArray(new Ink.Stroke[0]);
        Ink.Stroke[] strokes = snapshot;
        worker.execute(() -> {
            Bitmap bitmap;
            try {
                bitmap = raster(target.scale, tile, strokes);
            } catch (RuntimeException error) {
                android.util.Log.e("DraftRaster", "Tile render failed", error);
                main.post(() -> busy = false);
                return;
            }
            main.post(() -> {
                busy = false;
                if (!closed && phase == target && epoch == generation && target.tiles.get(tile.key()) == tile) {
                    if (tile.bitmap != null) target.bitmapCount--;
                    tile.bitmap = bitmap;
                    tile.sequence = revision;
                    if (bitmap != null) target.bitmapCount++;
                    trimBitmaps(target);
                    if (target.visible.contains(tile)) invalidate.run();
                    pruneOps();
                } else if (bitmap != null) {
                    bitmap.recycle();
                }
                pump();
            });
        });
    }

    private Tile nextTile(Phase target) {
        Tile visible = null;
        double visibleDistance = Double.POSITIVE_INFINITY;
        for (Tile tile : target.tiles.values()) {
            if (!tile.needsRender() || !target.visible.contains(tile)) continue;
            double distance = distance(tile);
            if (distance < visibleDistance) {
                visible = tile;
                visibleDistance = distance;
            }
        }
        if (visible != null) return visible;
        boolean hasRoom = target.bitmapCount < bitmapLimit(target);
        if (hasRoom && !target.haloIndexed) {
            for (int row = target.nearby.top; row <= target.nearby.bottom; row++) {
                for (int col = target.nearby.left; col <= target.nearby.right; col++) {
                    if (target.get(col, row) != null) continue;
                    Tile tile = target.add(col, row, sequence);
                    if (!containsInk(tile)) tile.sequence = sequence;
                }
            }
            target.haloIndexed = true;
        }
        Tile nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (Tile tile : target.tiles.values()) {
            if (!tile.needsRender() || (!hasRoom && tile.bitmap == null)) continue;
            double distance = distance(tile);
            if (distance < nearestDistance) {
                nearest = tile;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private double distance(Tile tile) {
        double x = ((tile.left + tile.right) / 2 - camera.x) * camera.scale / camera.width;
        double y = ((tile.top + tile.bottom) / 2 - camera.y) * camera.scale / camera.height;
        return x * x + y * y;
    }

    private int bitmapLimit(Phase target) {
        Range r = target.visible;
        int visibleTiles = (r.right - r.left + 1) * (r.bottom - r.top + 1);
        return Math.max(visibleTiles + 8, 192);
    }

    private void trimBitmaps(Phase target) {
        while (target.bitmapCount > bitmapLimit(target)) {
            Tile farthest = null;
            double maxDistance = -1;
            for (Tile tile : target.tiles.values()) {
                if (tile.bitmap == null || target.visible.contains(tile)) continue;
                double distance = distance(tile);
                if (distance > maxDistance) {
                    farthest = tile;
                    maxDistance = distance;
                }
            }
            if (farthest == null) return;
            target.tiles.remove(farthest.key());
            target.bitmapCount--;
        }
    }

    private void pruneOps() {
        if (ops.isEmpty() || phase == null) return;
        ops.removeIf(op -> !needsPreview(phase, op, false) && !needsPreview(previous, op, true));
    }

    private boolean needsPreview(Phase source, Op op, boolean fallback) {
        if (source == null) return false;
        for (Tile tile : source.tiles.values()) {
            if (tile.sequence < op.seq && op.intersects(tile)
                    && (tile.bitmap != null || (!fallback && source.visible.contains(tile)))) return true;
        }
        return false;
    }

    private boolean visibleReady() {
        if (phase == null) return false;
        Range visible = phase.visible;
        for (int row = visible.top; row <= visible.bottom; row++) {
            for (int col = visible.left; col <= visible.right; col++) {
                Tile tile = phase.get(col, row);
                if (tile == null || tile.needsRender()) return false;
            }
        }
        return true;
    }
    boolean ready() { return document != null && (document.strokes.isEmpty() || visibleReady()); }

    void close() {
        closed = true;
        worker.shutdownNow();
        phase = previous = null;
        ops.clear();
    }

    private Bitmap raster(double scale, Tile tile, Ink.Stroke[] strokes) {
        Bitmap bitmap = null;
        Canvas canvas = null;
        Paint paint = null;
        for (Ink.Stroke stroke : strokes) {
            if (!stroke.intersects(tile.left, tile.top, tile.right, tile.bottom)) continue;
            if (bitmap == null) {
                bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
                canvas = new Canvas(bitmap);
                paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            }
            canvas.save();
            canvas.translate((float) ((stroke.x - tile.left) * scale), (float) ((stroke.y - tile.top) * scale));
            canvas.scale((float) scale, (float) scale);
            Path path = null;
            if (stroke.points.length > 3) {
                path = paths.get(stroke);
                if (path == null) {
                    path = strokePath(stroke);
                    paths.put(stroke, path);
                }
            }
            drawStroke(canvas, stroke, path, paint);
            canvas.restore();
        }
        return bitmap;
    }

    private static void drawStroke(Canvas canvas, Ink.Stroke stroke, Path path, Paint paint) {
        paint.setColor(stroke.color);
        if (stroke.points.length == 3) {
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(stroke.points[0], stroke.points[1], stroke.width / 2, paint);
        } else {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke.width);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(path, paint);
        }
    }

    static Path strokePath(Ink.Stroke stroke){
        StrokeData data=stroke.data();Path path=new Path();if(data.segments<1)return path;
        double[] ranges=stroke.sections;int count=ranges==null?2:ranges.length;
        for(int k=0;k<count;k+=2){double a=ranges==null?0:ranges[k],b=ranges==null?data.segments:ranges[k+1];path.moveTo(data.coordinate(a,0),data.coordinate(a,1));for(int i=(int)Math.floor(a)+1;i<b;i++)path.lineTo(data.points[i*3],data.points[i*3+1]);path.lineTo(data.coordinate(b,0),data.coordinate(b,1));}
        return path;
    }
}
