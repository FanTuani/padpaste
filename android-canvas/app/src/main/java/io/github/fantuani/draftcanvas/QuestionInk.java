package io.github.fantuani.draftcanvas;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;

import java.util.ArrayList;

/** Composites photo colors into the ink mask, in the same stacking order as the photos. */
final class QuestionInk {
    private static final class Photo {
        final RectF bounds = new RectF();
        final RectF destination = new RectF();
        Bitmap bitmap;
        int alpha;
    }

    private final ArrayList<Photo> photos = new ArrayList<>();
    private final Paint invert = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Path photoArea = new Path();
    private int count;

    QuestionInk() {
        invert.setColorFilter(new ColorMatrixColorFilter(new float[]{
                -1, 0, 0, 0, 255,
                0, -1, 0, 0, 255,
                0, 0, -1, 0, 255,
                0, 0, 0, 1, 0
        }));
        invert.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
    }

    void add(Bitmap bitmap, RectF bounds, int alpha) {
        add(bitmap, bounds, bounds, alpha);
    }

    void add(Bitmap bitmap, RectF bounds, RectF destination, int alpha) {
        if (count == photos.size()) photos.add(new Photo());
        Photo photo = photos.get(count++);
        photo.bitmap = bitmap;
        photo.bounds.set(bounds);
        photo.destination.set(destination);
        photo.alpha = alpha;
    }

    boolean isEmpty() { return count == 0; }

    boolean intersects(RectF bounds) {
        for (int index = 0; index < count; index++) {
            if (RectF.intersects(bounds, photos.get(index).bounds)) return true;
        }
        return false;
    }

    void clipOutside(Canvas canvas) {
        for (int index = 0; index < count; index++) canvas.clipOutRect(photos.get(index).bounds);
    }

    boolean clipInside(Canvas canvas) {
        photoArea.rewind();
        for (int index = 0; index < count; index++) photoArea.addRect(photos.get(index).bounds, Path.Direction.CW);
        return canvas.clipPath(photoArea);
    }

    void draw(Canvas canvas, RectF inkBounds) {
        for (int index = 0; index < count; index++) {
            Photo photo = photos.get(index);
            if (!RectF.intersects(inkBounds, photo.bounds)) continue;
            invert.setAlpha(photo.alpha);
            // SRC_ATOP replaces ink color through the bitmap alpha and preserves the ink mask.
            if (photo.bounds.equals(photo.destination)) {
                canvas.drawBitmap(photo.bitmap, null, photo.destination, invert);
            } else {
                int save = canvas.save();
                canvas.clipRect(photo.bounds);
                canvas.drawBitmap(photo.bitmap, null, photo.destination, invert);
                canvas.restoreToCount(save);
            }
        }
    }

    void clear() {
        for (int index = 0; index < count; index++) photos.get(index).bitmap = null;
        count = 0;
    }
}
