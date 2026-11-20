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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class QuestionAssets {
    static final int MAX_BYTES = 6 * 1024 * 1024;
    private static QuestionAssets instance;
    private final File directory;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> loading = new HashSet<>();
    private final LruCache<String, Bitmap> bitmaps = new LruCache<String, Bitmap>(40 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
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
                bitmaps.put(asset, bitmap);
                main.post(() -> result.accept(asset));
            } catch (IOException failure) {
                main.post(() -> result.accept(null));
            } finally {
                if (temp != null && temp.exists()) temp.delete();
            }
        });
    }

    Bitmap visible(String asset, Runnable ready) {
        Bitmap bitmap = bitmaps.get(asset);
        if (bitmap != null || !loading.add(asset)) return bitmap;
        worker.execute(() -> {
            Bitmap loaded = BitmapFactory.decodeFile(file(asset).getPath());
            if (loaded != null) bitmaps.put(asset, loaded);
            main.post(() -> {
                if (loaded != null) {
                    loading.remove(asset);
                    ready.run();
                }
            });
        });
        return null;
    }

    private File file(String asset) { return new File(directory, asset + ".image"); }
}
