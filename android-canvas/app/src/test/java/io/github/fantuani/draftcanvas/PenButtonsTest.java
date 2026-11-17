package io.github.fantuani.draftcanvas;
import org.junit.Test;
import static org.junit.Assert.*;
public class PenButtonsTest {
    private static class State implements PenButtons.Listener {int changes,toggles;boolean eraser;public void eraser(boolean held){eraser=held;changes++;}public void toggleColor(){toggles++;}}
    @Test public void lowerHoldsAndReleasesWithoutRepeatToggles(){State s=new State();PenButtons b=new PenButtons(s);b.lower(true);b.lower(true);assertTrue(s.eraser);assertEquals(1,s.changes);b.lower(false);assertFalse(s.eraser);assertEquals(2,s.changes);}
    @Test public void upperOnlyShortCompletedPressToggles(){State s=new State();PenButtons b=new PenButtons(s);b.upper(true,100,false);b.upper(true,200,false);b.upper(false,250,false);assertEquals(1,s.toggles);b.upper(true,1000,false);b.upper(false,1800,false);assertEquals(1,s.toggles);b.upper(true,2000,false);b.upper(false,2100,true);assertEquals(1,s.toggles);}
    @Test public void losingFocusCannotLeaveEraserOrTriggerColor(){State s=new State();PenButtons b=new PenButtons(s);b.lower(true);b.upper(true,1,false);b.reset();assertFalse(s.eraser);b.upper(false,100,false);assertEquals(0,s.toggles);}
}
