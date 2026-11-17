package io.github.fantuani.draftcanvas;

/** Constant-time swept-circle queries. Erasing never splits or copies ink point arrays. */
final class Eraser {
    private Eraser() {}
    static double pressureScale(float pressure){return pressureScale(pressure,1);}
    static double pressureScale(float pressure,float sensitivity){double p=Math.max(0,Math.min(1,pressure));return .5+1.5*Math.pow(p,1/Math.max(.25f,sensitivity));}
    static double distanceSquared(double x,double y,double ax,double ay,double bx,double by){double dx=bx-ax,dy=by-ay,den=dx*dx+dy*dy,t=den==0?0:Math.max(0,Math.min(1,((x-ax)*dx+(y-ay)*dy)/den));double rx=x-ax-t*dx,ry=y-ay-t*dy;return rx*rx+ry*ry;}
}
