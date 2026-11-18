package io.github.fantuani.draftcanvas;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Each phase is invoked in a new process to check the actual file, not a retained model. */
final class DraftPersistenceCheck {
    static void run(Instrumentation runner, String phase) {
        Bundle result = new Bundle();
        try {
            File path = new File(runner.getTargetContext().getCacheDir(), "test-draft.bin");
            DraftStore store = new DraftStore(path);
            CountDownLatch opened = new CountDownLatch(1);
            AtomicReference<Ink> captured = new AtomicReference<>();
            runner.runOnMainSync(() -> store.open(ink -> {
                captured.set(ink);
                opened.countDown();
            }));
            if (!opened.await(8, TimeUnit.SECONDS)) throw new AssertionError("Draft load timed out");
            Ink ink = captured.get();
            if ("write".equals(phase)) {
                runner.runOnMainSync(() -> {
                    ink.clear();
                    ink.add(new Ink.Stroke(ink.nextId(), 0xFFCF4941, 4.25f, 300, -48,
                            new float[]{0,0,1,100,0,1}));
                    ink.begin();
                    ink.erase(350, -58, 350, -38, 10);
                    ink.commit();
                    ink.view.x = 144;
                    ink.view.y = -84.5;
                    ink.view.scale = 2.5;
                    store.observe();
                });
                // Exercise scheduled autosave rather than calling flush in the write phase.
                long deadline = android.os.SystemClock.uptimeMillis() + 8000;
                while (path.length() == 0 && android.os.SystemClock.uptimeMillis() < deadline) {
                    Thread.sleep(25);
                }
                store.awaitWrites(4000);
                check(path.length() > 36, "Autosave did not finish");
            } else if ("empty".equals(phase)) {
                check(ink.strokes.isEmpty(), "Cleared ink reappeared in a fresh process");
            } else {
                check(ink.strokes.size() == 1, "Wrong number of restored strokes");
                Ink.Stroke stroke = ink.strokes.get(0);
                check(stroke.color == 0xFFCF4941 && stroke.width == 4.25f, "Style did not survive");
                check(stroke.hit(320, -48, 0) && !stroke.hit(350, -48, 0) && stroke.hit(380, -48, 0),
                        "Restoring the stroke filled its erased gap");
                check(ink.view.x == 144 && ink.view.y == -84.5 && ink.view.scale == 2.5,
                        "Wrong restored viewport");
                if ("clear".equals(phase)) {
                    runner.runOnMainSync(() -> {
                        ink.clear();
                        store.flush();
                    });
                    store.awaitWrites(4000);
                }
            }
            result.putString("stream", "PASS: draft persistence " + phase + "\n");
            runner.finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + error);
            runner.finish(Activity.RESULT_CANCELED, result);
        }
    }

    private static void check(boolean value, String failure) {
        if (!value) throw new AssertionError(failure);
    }
}
