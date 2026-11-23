package io.github.fantuani.draftcanvas;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class QuestionAssets {
    static final int MAX_BYTES = 6 * 1024 * 1024;
    private static final int CACHE_BYTES = 40 * 1024 * 1024;
    private static final int PREVIEW_EDGE = 1024;
    private static final int TILE_EDGE = 384;
    private static final int PREFETCH_PX = 64;
    private static QuestionAssets instance;
    private final File directory;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> loading = new HashSet<>();
    private final Set<String> tiling = new HashSet<>(), unavailableTiles = new HashSet<>();
    private final HashMap<String, Source> sources = new HashMap<>();
    // These rectangles are only used by draw() on the UI thread.
    private final RectF tileBounds = new RectF(), destination = new RectF();
    private record Source(int width, int height) {
        int edge() { return Math.max(width, height); }
    }
    private record Sample(String asset, int divisor) {}
    private record Tile(String asset, int divisor, int x, int y) {}
    private record Region(Tile tile, Rect bounds) {}
    private final LruCache<Tile, Bitmap> tiles = new LruCache<Tile, Bitmap>(CACHE_BYTES * 3 / 4) {
        @Override protected int sizeOf(Tile key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private final LruCache<Sample, Bitmap> bitmaps = new LruCache<Sample, Bitmap>(CACHE_BYTES / 4) {
        @Override protected int sizeOf(Sample key, Bitmap value) { return value.getAllocationByteCount(); }
    };

    static QuestionAssets get(Context context) {
        if (instance == null) instance = new QuestionAssets(context.getApplicationContext());
        return instance;
    }

    private QuestionAssets(Context context) {
        directory = new File(context.getFilesDir(), "questions");
    }

    static Bitmap decode(byte[] data) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (data.length > MAX_BYTES || bounds.outWidth < 1 || bounds.outHeight < 1
                || bounds.outWidth > 2400 || bounds.outHeight > 2400
                || !("image/png".equals(bounds.outMimeType) || "image/jpeg".equals(bounds.outMimeType))) {
            throw new IOException("Invalid question image");
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (bitmap == null) throw new IOException("Incomplete question image");
        bitmap.prepareToDraw();
        return bitmap;
    }

    void save(byte[] data, Bitmap bitmap, java.util.function.Consumer<String> result) {
        worker.execute(() -> {
            String asset = UUID.randomUUID().toString().replace("-", "");
            File temp = null;
            try {
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create image directory");
                temp = File.createTempFile("incoming-", ".tmp", directory);
                try (FileOutputStream stream = new FileOutputStream(temp)) {
                    stream.write(data);
                    stream.getFD().sync();
                }
                if (!temp.renameTo(file(asset))) throw new IOException("Cannot save question");
                if (bitmap.getAllocationByteCount() <= CACHE_BYTES / 4) {
                    bitmaps.put(new Sample(asset, 1), bitmap);
                }
                main.post(() -> {
                    sources.put(asset, new Source(bitmap.getWidth(), bitmap.getHeight()));
                    result.accept(asset);
                });
            } catch (IOException failure) {
                main.post(() -> result.accept(null));
            } finally {
                if (temp != null && temp.exists()) temp.delete();
            }
        });
    }

    void draw(String asset, Canvas canvas, RectF bounds, int displayEdge, int viewWidth, int viewHeight,
              Paint paint, QuestionInk ink, Runnable ready) {
        Source source = sources.get(asset);
        if (source == null) {
            loadPreview(asset, displayEdge, ready);
            return;
        }
        if (source.edge() == 0) return;

        int divisor = sampleSize(source.edge(), displayEdge);
        Bitmap fallback = null;
        for (int finer = divisor; finer >= 1; finer /= 2) {
            Bitmap bitmap = bitmaps.get(new Sample(asset, finer));
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, null, bounds, paint);
                if (ink != null) ink.add(bitmap, bounds, 255);
                return;
            }
        }
        for (int smaller = divisor * 2; smaller <= source.edge() * 2; smaller *= 2) {
            fallback = bitmaps.get(new Sample(asset, smaller));
            if (fallback != null) break;
        }
        int previewDivisor = previewDivisor(source.edge());
        if (divisor < previewDivisor && !unavailableTiles.contains(asset)) {
            drawTiles(asset, source, divisor, canvas, bounds, viewWidth, viewHeight, paint, ink, fallback, ready);
            return;
        }
        if (divisor >= previewDivisor || fallback == null) loadPreview(asset, displayEdge, ready);
        if (fallback != null) {
            canvas.drawBitmap(fallback, null, bounds, paint);
            if (ink != null) ink.add(fallback, bounds, 255);
        }
    }

    private void drawTiles(String asset, Source source, int divisor, Canvas canvas, RectF bounds,
                           int viewWidth, int viewHeight, Paint paint, QuestionInk ink, Bitmap fallback,
                           Runnable ready) {
        float sx = bounds.width() / source.width(), sy = bounds.height() / source.height();
        int step = TILE_EDGE * divisor;
        int firstX = Math.max(0, (int) Math.floor((-PREFETCH_PX - bounds.left) / sx / step)) * step;
        int firstY = Math.max(0, (int) Math.floor((-PREFETCH_PX - bounds.top) / sy / step)) * step;
        float lastX = Math.min(source.width(), (viewWidth + PREFETCH_PX - bounds.left) / sx);
        float lastY = Math.min(source.height(), (viewHeight + PREFETCH_PX - bounds.top) / sy);
        List<Region> missing = null;
        boolean canLoad = !tiling.contains(asset);
        boolean visibleGap = false;
        for (int y = firstY; y < lastY; y += step) {
            for (int x = firstX; x < lastX; x += step) {
                int right = Math.min(x + step, source.width()), bottom = Math.min(y + step, source.height());
                Tile key = new Tile(asset, divisor, x, y);
                Bitmap tile = tiles.get(key);
                // Keep a one-sample gutter so bilinear filtering matches across tile boundaries.
                int l = Math.max(0, x - divisor), t = Math.max(0, y - divisor);
                int r = Math.min(source.width(), right + divisor), b = Math.min(source.height(), bottom + divisor);
                if (tile == null && canLoad) {
                    if (missing == null) missing = new ArrayList<>();
                    missing.add(new Region(key, new Rect(l, t, r, b)));
                }
                tileBounds.set(bounds.left + x * sx, bounds.top + y * sy,
                        bounds.left + right * sx, bounds.top + bottom * sy);
                if (!tileBounds.intersects(0, 0, viewWidth, viewHeight)) continue;
                if (tile == null && fallback == null) {
                    visibleGap = true;
                    continue;
                }
                destination.set(bounds.left + l * sx, bounds.top + t * sy,
                        bounds.left + r * sx, bounds.top + b * sy);
                Bitmap bitmap = tile == null ? fallback : tile;
                RectF target = tile == null ? bounds : destination;
                int save = canvas.save();
                canvas.clipRect(tileBounds);
                canvas.drawBitmap(bitmap, null, target, paint);
                canvas.restoreToCount(save);
                if (ink != null) ink.add(bitmap, tileBounds, target, 255);
            }
        }
        if (missing != null) loadTiles(asset, divisor, missing, ready);
        if (visibleGap) loadPreview(asset, source.edge(), ready);
    }

    @SuppressWarnings("deprecation")
    private void loadTiles(String asset, int divisor, List<Region> regions, Runnable ready) {
        tiling.add(asset);
        worker.execute(() -> {
            BitmapRegionDecoder decoder = null;
            boolean complete = false;
            try {
                decoder = BitmapRegionDecoder.newInstance(file(asset).getPath(), false);
                if (decoder == null) return;
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = divisor;
                for (Region region : regions) {
                    Bitmap bitmap = decoder.decodeRegion(region.bounds(), options);
                    if (bitmap == null) return;
                    bitmap.prepareToDraw();
                    tiles.put(region.tile(), bitmap);
                }
                complete = true;
            } catch (IOException | IllegalArgumentException ignored) {
                // Keep the last available preview if the source can no longer be read.
            } finally {
                if (decoder != null) decoder.recycle();
                boolean decoded = complete;
                main.post(() -> {
                    if (!decoded) unavailableTiles.add(asset);
                    tiling.remove(asset);
                    ready.run();
                });
            }
        });
    }

    private void loadPreview(String asset, int edge, Runnable ready) {
        if (!loading.add(asset)) return;
        Source known = sources.get(asset);
        worker.execute(() -> {
            String path = file(asset).getPath();
            BitmapFactory.Options options = new BitmapFactory.Options();
            Source source = known;
            if (source == null) {
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, options);
                source = new Source(Math.max(0, options.outWidth), Math.max(0, options.outHeight));
            }
            options.inJustDecodeBounds = false;
            options.inSampleSize = Math.max(sampleSize(source.edge(), edge), previewDivisor(source.edge()));
            Bitmap loaded = source.edge() > 0 ? BitmapFactory.decodeFile(path, options) : null;
            if (loaded != null) {
                loaded.prepareToDraw();
                bitmaps.put(new Sample(asset, options.inSampleSize), loaded);
            }
            Source resolved = loaded == null ? new Source(0, 0) : source;
            main.post(() -> {
                sources.put(asset, resolved);
                loading.remove(asset);
                ready.run();
            });
        });
    }

    private static int sampleSize(int source, int edge) {
        int divisor = 1;
        while (source / divisor / 2 >= edge) divisor *= 2;
        return divisor;
    }

    private static int previewDivisor(int source) {
        int divisor = 1;
        while ((source + divisor - 1) / divisor > PREVIEW_EDGE) divisor *= 2;
        return divisor;
    }

    private File file(String asset) { return new File(directory, asset + ".image"); }
}
