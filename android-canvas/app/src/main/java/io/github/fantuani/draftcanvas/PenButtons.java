package io.github.fantuani.draftcanvas;

/** Key-repeat safe press/hold state, independent of Android event dispatch. */
public final class PenButtons {
    public interface Listener { void eraser(boolean held);void toggleColor(); }
    private final Listener listener;private boolean lower,upper;private long upperDown;
    public PenButtons(Listener listener){this.listener=listener;}
    public void lower(boolean down){if(lower!=down){lower=down;listener.eraser(down);}}
    public void upper(boolean down,long time,boolean canceled){
        if(down){if(!upper){upper=true;upperDown=time;}return;}
        if(upper){upper=false;if(!canceled&&time>=upperDown&&time-upperDown<=500)listener.toggleColor();}
    }
    public void reset(){upper=false;lower(false);}
}
