package io.github.fantuani.draftcanvas;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class QuestionDetailCheck {
    static void run(Instrumentation runner) {
        Bundle result = new Bundle();
        try {
            QuestionAssets assets = QuestionAssets.get(runner.getTargetContext());
            Bitmap source = Bitmap.createBitmap(2400, 1600, Bitmap.Config.ARGB_8888);
            int[] stripes = new int[2400];
            for (int x = 0; x < stripes.length; x++) stripes[x] = x % 2 == 0 ? Color.BLACK : Color.WHITE;
            for (int y = 0; y < 1600; y++) source.setPixels(stripes, 0, 2400, 0, y, 2400, 1);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            source.compress(Bitmap.CompressFormat.PNG, 100, png);
            byte[] bytes = png.toByteArray();
            AtomicReference<String> saved = new AtomicReference<>();
            CountDownLatch persisted = new CountDownLatch(1);
            runner.runOnMainSync(() -> assets.save(bytes, source, asset -> {
                saved.set(asset);
                persisted.countDown();
            }));
            if (!persisted.await(5, TimeUnit.SECONDS) || saved.get() == null) throw new AssertionError("Fixture not saved");

            AtomicReference<CanvasView> view = new AtomicReference<>();
            runner.runOnMainSync(() -> {
                Ink ink = new Ink();
                ink.view.x = 320;
                ink.view.y = 200;
                // Each photo has only a narrow strip on screen, with an additional tile boundary in view.
                ink.questions.add(new QuestionImage(1, saved.get(), -2210, 0, 2400, 1600));
                ink.questions.add(new QuestionImage(2, saved.get(), 420, 0, 2400, 1600));
                CanvasView canvas = new CanvasView(runner.getTargetContext(), ink);
                canvas.questionAssets = assets;
                canvas.layout(0, 0, 640, 400);
                view.set(canvas);
            });
            Bitmap frame = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888);
            Canvas target = new Canvas(frame);
            long deadline = SystemClock.uptimeMillis() + 5000;
            do {
                runner.runOnMainSync(() -> view.get().draw(target));
                // Check the trailing image edge, both sides of a tile boundary, and the next photo.
                if (stripesAt(frame, 188) && stripesAt(frame, 92) && stripesAt(frame, 94) && stripesAt(frame, 430)) {
                    result.putString("stream", "PASS: both partially visible images retain source-pixel detail\n");
                    runner.finish(Activity.RESULT_OK, result);
                    return;
                }
                SystemClock.sleep(32);
            } while (SystemClock.uptimeMillis() < deadline);
            throw new AssertionError("Partially visible source pixels were resampled");
        } catch (Throwable failure) {
            result.putString("stream", "FAIL: " + failure + "\n");
            runner.finish(Activity.RESULT_CANCELED, result);
        }
    }

    private static boolean stripesAt(Bitmap frame, int evenX) {
        return frame.getPixel(evenX, 150) == Color.BLACK && frame.getPixel(evenX + 1, 150) == Color.WHITE;
    }
}
