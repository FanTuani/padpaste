package io.github.fantuani.draftcanvas;

/** Double precision world coordinates, independent of Android rendering. */
public final class Viewport {
    public double x,y,scale=1;
    public double worldX(double screen,double width){return x+(screen-width/2)/scale;}
    public double worldY(double screen,double height){return y+(screen-height/2)/scale;}
    public void pan(double dx,double dy){x-=dx/scale;y-=dy/scale;}
    public void zoom(double factor,double focusX,double focusY,double width,double height){
        double wx=worldX(focusX,width),wy=worldY(focusY,height);
        scale=Math.max(.0001,Math.min(10000,scale*factor));
        x=wx-(focusX-width/2)/scale;y=wy-(focusY-height/2)/scale;
    }
}
