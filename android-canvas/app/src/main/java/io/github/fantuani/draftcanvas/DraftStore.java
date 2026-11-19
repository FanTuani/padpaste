package io.github.fantuani.draftcanvas;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Saves the single local draft. The canvas owns the model; one worker owns the file. */
final class DraftStore {
    private static final int MAGIC = 0x44524146; // DRAF
    private static final int VERSION = 1;
    private static final long SAVE_DELAY_MS = 240;
    private static DraftStore instance;

    static DraftStore get(Context context) {
        if (instance == null) instance = new DraftStore(new File(context.getFilesDir(), "draft.bin"));
        return instance;
    }

    private final AtomicFile file;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Consumer<Ink>> callbacks = new ArrayList<>();
    private final Runnable autosave = this::flush;
    private final Object writeLock = new Object();
    private Snapshot pending;
    private boolean writing;
    private volatile boolean failed;
    private boolean saveScheduled;
    private Ink ink;
    private long requestedRevision;
    private double requestedX, requestedY, requestedScale;

    DraftStore(File file) {
        this.file = new AtomicFile(file);
    }

    void open(Consumer<Ink> callback) {
        if (ink != null) {
            callback.accept(ink);
            return;
        }
        callbacks.add(callback);
        if (callbacks.size() != 1) return;
        io.execute(() -> {
            Ink restored = read();
            main.post(() -> {
                ink = restored;
                rememberCurrent();
                List<Consumer<Ink>> ready = new ArrayList<>(callbacks);
                callbacks.clear();
                for (Consumer<Ink> waiting : ready) waiting.accept(restored);
            });
        });
    }

    void observe() {
        if (changed() && !saveScheduled) {
            saveScheduled = true;
            main.postDelayed(autosave, SAVE_DELAY_MS);
        }
    }

    void flush() {
        main.removeCallbacks(autosave);
        saveScheduled = false;
        if (!changed() || ink.hasOpenEdit()) return;
        Snapshot snapshot = new Snapshot(ink);
        rememberCurrent();
        synchronized (writeLock) {
            pending = snapshot;
            failed = false;
            if (!writing) {
                writing = true;
                io.execute(this::writePending);
            }
        }
    }

    // Give the final queued write a short head start before Android stops the activity.
    void awaitWrites(long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        synchronized (writeLock) {
            while (writing) {
                long wait = deadline - SystemClock.uptimeMillis();
                if (wait <= 0) return;
                try {
                    writeLock.wait(wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private boolean changed() {
        return ink != null && (failed || ink.revision != requestedRevision
                || ink.view.x != requestedX || ink.view.y != requestedY || ink.view.scale != requestedScale);
    }

    private void rememberCurrent() {
        requestedRevision = ink.revision;
        requestedX = ink.view.x;
        requestedY = ink.view.y;
        requestedScale = ink.view.scale;
    }

    private void writePending() {
        for (;;) {
            Snapshot snapshot;
            synchronized (writeLock) {
                snapshot = pending;
                pending = null;
                if (snapshot == null) {
                    writing = false;
                    writeLock.notifyAll();
                    return;
                }
            }
            failed = !write(snapshot);
        }
    }

    private boolean write(Snapshot snapshot) {
        FileOutputStream raw = null;
        try {
            raw = file.startWrite();
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(raw, 32 * 1024));
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeDouble(snapshot.x);
            out.writeDouble(snapshot.y);
            out.writeDouble(snapshot.scale);
            out.writeInt(snapshot.strokes.size());
            for (Ink.Stroke stroke : snapshot.strokes) {
                // The intervals refer to this exact sampled geometry, even if smoothing changes later.
                boolean flattened = stroke.hasFlattenedGeometry();
                float[] points = flattened ? stroke.pathPoints() : stroke.points;
                out.writeInt(stroke.color);
                out.writeFloat(stroke.width);
                out.writeDouble(stroke.x);
                out.writeDouble(stroke.y);
                out.writeBoolean(flattened);
                out.writeInt(points.length);
                for (float point : points) out.writeFloat(point);
                out.writeInt(stroke.sections == null ? -1 : stroke.sections.length);
                if (stroke.sections != null) {
                    for (double limit : stroke.sections) out.writeDouble(limit);
                }
            }
            out.flush();
            file.finishWrite(raw);
            return true;
        } catch (IOException error) {
            file.failWrite(raw);
            Log.e("DraftStore", "Could not save the local draft", error);
            return false;
        }
    }

    private Ink read() {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(file.openRead(), 32 * 1024))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Unknown draft format");
            Ink restored = new Ink();
            restored.view.x = coordinate(in.readDouble());
            restored.view.y = coordinate(in.readDouble());
            restored.view.scale = in.readDouble();
            if (!(restored.view.scale >= .0001 && restored.view.scale <= 10000)) {
                throw new IOException("Invalid draft scale");
            }
            int count = count(in.readInt(), in.available() / 45);
            for (int i = 0; i < count; i++) readStroke(in, restored);
            if (in.read() != -1) throw new IOException("Trailing draft data");
            return restored;
        } catch (FileNotFoundException firstLaunch) {
            return new Ink();
        } catch (IOException error) {
            Log.e("DraftStore", "Could not restore the local draft", error);
            return new Ink();
        }
    }

    private static void readStroke(DataInputStream in, Ink restored) throws IOException {
        int color = in.readInt();
        float width = in.readFloat();
        if (!Float.isFinite(width) || width <= 0) throw new IOException("Invalid stroke width");
        double x = coordinate(in.readDouble()), y = coordinate(in.readDouble());
        boolean flattened = in.readBoolean();
        int length = count(in.readInt(), in.available() / 4);
        if (length < 3 || length % 3 != 0) throw new IOException("Invalid points");
        float[] points = new float[length];
        for (int i = 0; i < length; i++) {
            points[i] = in.readFloat();
            if (!Float.isFinite(points[i])) throw new IOException("Invalid stroke point");
        }
        int sectionCount = in.readInt();
        double[] sections = null;
        if (sectionCount != -1) {
            count(sectionCount, in.available() / 8);
            if (!flattened || sectionCount < 2 || sectionCount % 2 != 0) throw new IOException("Invalid sections");
            sections = new double[sectionCount];
            double previous = -1;
            for (int i = 0; i < sectionCount; i++) {
                double limit = in.readDouble();
                if (!(limit >= 0 && limit > previous && limit <= length / 3 - 1)) {
                    throw new IOException("Invalid section boundary");
                }
                sections[i] = previous = limit;
            }
        }
        restored.strokes.add(new Ink.Stroke(restored.nextId(), color, width, x, y, points, flattened, sections));
    }

    private static int count(int value, int max) throws IOException {
        if (value < 0 || value > max) throw new IOException("Invalid draft record count");
        return value;
    }

    private static double coordinate(double value) throws IOException {
        if (!Double.isFinite(value)) throw new IOException("Invalid draft coordinate");
        return value;
    }

    private static final class Snapshot {
        final List<Ink.Stroke> strokes;
        final double x, y, scale;

        Snapshot(Ink ink) {
            strokes = new ArrayList<>(ink.strokes);
            x = ink.view.x;
            y = ink.view.y;
            scale = ink.view.scale;
        }
    }
}
