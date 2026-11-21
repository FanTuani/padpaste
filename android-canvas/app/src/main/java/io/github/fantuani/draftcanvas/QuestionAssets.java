package io.github.fantuani.draftcanvas;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class QuestionAssets {
    static final int MAX_BYTES = 6 * 1024 * 1024;
    private static final int CACHE_BYTES = 40 * 1024 * 1024;
    private static final int MOTION_EDGE = 960;
    private static QuestionAssets instance;
    private final File directory;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> loading = new HashSet<>();
    private final HashMap<String, Integer> sourceEdges = new HashMap<>();
    private record Sample(String asset, int divisor) {}
    private final LruCache<Sample, Bitmap> bitmaps = new LruCache<Sample, Bitmap>(CACHE_BYTES) {
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
                bitmaps.put(new Sample(asset, 1), bitmap);
                main.post(() -> {
                    sourceEdges.put(asset, Math.max(bitmap.getWidth(), bitmap.getHeight()));
                    result.accept(asset);
                });
            } catch (IOException failure) {
                main.post(() -> result.accept(null));
            } finally {
                if (temp != null && temp.exists()) temp.delete();
            }
        });
    }

    static int edgeLimit(int images) {
        // Keep the visible working set below the LRU limit, including during a resolution change.
        return (int) Math.sqrt(CACHE_BYTES * .68 / (4L * Math.max(1, images)));
    }

    static int motionEdgeLimit(int edgeLimit) {
        return Math.max(1, Math.min(MOTION_EDGE, edgeLimit));
    }

    Bitmap visible(String asset, int edge, Runnable ready) {
        Integer source = sourceEdges.get(asset);
        if (source == null) {
            load(asset, edge, ready);
            return null;
        }
        if (source == 0) return null;
        int divisor = sampleSize(source, edge);
        Bitmap desired = bitmaps.get(new Sample(asset, divisor));
        if (desired != null) return desired;
        load(asset, edge, ready);
        // Keep displaying a previously decoded image while its requested resolution is loading.
        for (int smaller = divisor * 2; smaller <= source * 2; smaller *= 2) {
            Bitmap fallback = bitmaps.get(new Sample(asset, smaller));
            if (fallback != null) return fallback;
        }
        for (int larger = divisor / 2; larger >= 1; larger /= 2) {
            Bitmap fallback = bitmaps.get(new Sample(asset, larger));
            if (fallback != null) return fallback;
        }
        return null;
    }

    private void load(String asset, int edge, Runnable ready) {
        if (!loading.add(asset)) return;
        Integer knownEdge = sourceEdges.get(asset);
        worker.execute(() -> {
            String path = file(asset).getPath();
            BitmapFactory.Options options = new BitmapFactory.Options();
            int source = knownEdge == null ? 0 : knownEdge;
            if (source == 0) {
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, options);
                source = Math.max(options.outWidth, options.outHeight);
            }
            options.inJustDecodeBounds = false;
            options.inSampleSize = sampleSize(source, edge);
            Bitmap loaded = source > 0 ? BitmapFactory.decodeFile(path, options) : null;
            if (loaded != null) {
                loaded.prepareToDraw();
                bitmaps.put(new Sample(asset, options.inSampleSize), loaded);
            }
            int sourceEdge = loaded == null ? 0 : source;
            main.post(() -> {
                sourceEdges.put(asset, sourceEdge);
                loading.remove(asset);
                ready.run();
            });
        });
    }

    private static int sampleSize(int source, int edge) {
        int divisor = 1;
        while (((source + divisor - 1) / divisor) > edge) divisor *= 2;
        return divisor;
    }

    private File file(String asset) { return new File(directory, asset + ".image"); }
}
