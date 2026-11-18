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
        private static final double SNAP_RANGE = .07;
        private static final double RELEASE_RANGE = .08;
        private double freeScale;
        private boolean snapped;

        void start(double scale) {
            freeScale = scale;
            snapped = false;
        }

        double update(double factor) {
            // Keep accumulating finger movement while the displayed scale is held at 100%.
            double previousScale = freeScale;
            freeScale = clampScale(freeScale * factor);
            if (snapped) {
                snapped = nearActualSize(RELEASE_RANGE);
            } else {
                boolean towardActualSize = previousScale < 1 && factor > 1 || previousScale > 1 && factor < 1;
                snapped = towardActualSize && nearActualSize(SNAP_RANGE);
            }
            return snapped ? 1 : freeScale;
        }

        private boolean nearActualSize(double range) {
            return freeScale >= 1 - range && freeScale <= 1 + range;
        }
    }
}
