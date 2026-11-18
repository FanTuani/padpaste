package io.github.fantuani.draftcanvas;

/** Keeps Android key repeats and canceled gestures out of the tool callbacks. */
final class PenButtons {
    static final long HOLD_MS = 200;

    interface Listener {
        void eraser(boolean held);
        void undo();
        void menu();
        void beginSelection();
        void endSelection(boolean canceled);
    }

    private final Listener listener;
    private boolean lowerDown, erasing, upperDown, selecting;
    private long lowerTime, upperTime;

    PenButtons(Listener listener) {
        this.listener = listener;
    }

    long lowerDeadline() { return lowerTime + HOLD_MS; }
    long upperDeadline() { return upperTime + HOLD_MS; }

    void lower(boolean down, long time, boolean canceled) {
        if (down) {
            if (!lowerDown && !canceled && !upperDown) {
                lowerDown = true;
                lowerTime = time;
            }
            return;
        }
        if (!lowerDown) return;
        lowerDown = false;
        if (erasing) {
            erasing = false;
            listener.eraser(false);
        } else if (!canceled && time >= lowerTime && time < lowerDeadline()) {
            listener.undo();
        }
    }

    void hold(long time) {
        if (lowerDown && !erasing && time >= lowerDeadline()) {
            erasing = true;
            listener.eraser(true);
        }
    }

    void upper(boolean down, long time, boolean canceled) {
        if (down) {
            if (!upperDown && !canceled && !lowerDown) {
                upperDown = true;
                upperTime = time;
            }
            return;
        }
        if (!upperDown) return;
        // A busy UI can deliver the UP before the hold callback runs.
        if (!canceled) holdUpper(time);
        upperDown = false;
        if (selecting) {
            selecting = false;
            listener.endSelection(canceled);
        } else if (!canceled && time >= upperTime) {
            listener.menu();
        }
    }

    void holdUpper(long time) {
        if (upperDown && !selecting && time >= upperDeadline()) {
            selecting = true;
            listener.beginSelection();
        }
    }

    void reset() {
        upperDown = lowerDown = false;
        if (selecting) {
            selecting = false;
            listener.endSelection(true);
        }
        if (erasing) {
            erasing = false;
            listener.eraser(false);
        }
    }
}
