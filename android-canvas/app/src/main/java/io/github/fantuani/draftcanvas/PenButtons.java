package io.github.fantuani.draftcanvas;

/** Key-repeat safe press/hold state, independent of Android event dispatch. */
public final class PenButtons {
    public static final long HOLD_MS=200;
    public interface Listener { void eraser(boolean held);void undo();void toggleColor(); }
    private final Listener listener;private boolean lower,held,upper;private long lowerDown,upperDown;
    public PenButtons(Listener listener){this.listener=listener;}
    public long lowerDeadline(){return lowerDown+HOLD_MS;}
    public void lower(boolean down,long time,boolean canceled){
        if(down){if(!lower&&!canceled){lower=true;lowerDown=time;}return;}
        if(!lower)return;
        lower=false;
        if(held){held=false;listener.eraser(false);}
        else if(!canceled&&time>=lowerDown&&time-lowerDown<HOLD_MS)listener.undo();
    }
    public void hold(long time){
        if(lower&&!held&&time>=lowerDeadline()){held=true;listener.eraser(true);}
    }
    public void upper(boolean down,long time,boolean canceled){
        if(down){if(!upper){upper=true;upperDown=time;}return;}
        if(upper){upper=false;if(!canceled&&time>=upperDown&&time-upperDown<=500)listener.toggleColor();}
    }
    public void reset(){upper=false;lower=false;if(held){held=false;listener.eraser(false);}}
}
