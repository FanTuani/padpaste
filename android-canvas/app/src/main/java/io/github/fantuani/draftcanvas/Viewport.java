package io.github.fantuani.draftcanvas;

/** Double precision world coordinates, independent of Android rendering. */
public final class Viewport {
    public double x,y,scale=1;
    public double worldX(double screen,double width){return x+(screen-width/2)/scale;}
    public double worldY(double screen,double height){return y+(screen-height/2)/scale;}
    public void pan(double dx,double dy){x-=dx/scale;y-=dy/scale;}
    public void zoom(double factor,double focusX,double focusY,double width,double height){
        double wx=worldX(focusX,width),wy=worldY(focusY,height);
        scale=clampScale(scale*factor);
        x=wx-(focusX-width/2)/scale;y=wy-(focusY-height/2)/scale;
    }

    private static double clampScale(double scale) {
        return Math.max(.0001, Math.min(10000, scale));
    }

    static final class Pinch {
        private static final double SNAP_RANGE = .06;
        private static final double RELEASE_RANGE = .08;
        private static final double SNAP_SPEED = .80;
        private static final double RELEASE_SPEED = .95;
        private static final double FAST_SAMPLE_SPEED = 1.4;
        private static final double SPEED_SMOOTHING_SECONDS = .045;
        private double freeScale;
        private double speed;
        private boolean snapped;

        void start(double scale) {
            freeScale = scale;
            speed = Double.NaN;
            snapped = false;
        }

        double update(double factor, long elapsedMillis) {
            // Keep accumulating finger movement while the displayed scale is held at 100%.
            double previousScale = freeScale;
            freeScale = clampScale(freeScale * factor);
            // Measure fractional span change per second, independent of the starting span.
            double seconds = Math.max(1, Math.min(48, elapsedMillis)) / 1000.0;
            double sampleSpeed = Math.abs(Math.log(factor)) / seconds;
            double weight = Math.exp(-seconds / SPEED_SMOOTHING_SECONDS);
            speed = Double.isNaN(speed) ? sampleSpeed : weight * speed + (1 - weight) * sampleSpeed;
            boolean fast = sampleSpeed >= FAST_SAMPLE_SPEED;

            if (snapped) {
                snapped = !fast && speed <= RELEASE_SPEED && nearActualSize(RELEASE_RANGE);
            } else {
                boolean towardActualSize = previousScale < 1 && factor > 1 || previousScale > 1 && factor < 1;
                snapped = towardActualSize && !fast && speed <= SNAP_SPEED && nearActualSize(SNAP_RANGE);
            }
            return snapped ? 1 : freeScale;
        }

        private boolean nearActualSize(double range) {
            return freeScale >= 1 - range && freeScale <= 1 + range;
        }
    }
}
