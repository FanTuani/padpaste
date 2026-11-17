package io.github.fantuani.draftcanvas;
import org.junit.Test;
import static org.junit.Assert.*;
public class PenButtonsTest {
    private static class State implements PenButtons.Listener {int changes,toggles,undos;boolean eraser;public void eraser(boolean held){eraser=held;changes++;}public void undo(){undos++;}public void toggleColor(){toggles++;}}
    @Test public void shortLowerPressUndoesOnReleaseOnly(){State s=new State();PenButtons b=new PenButtons(s);b.lower(true,100,false);b.hold(299);assertFalse(s.eraser);assertEquals(0,s.undos);b.lower(false,299,false);b.hold(300);assertEquals(1,s.undos);assertEquals(0,s.changes);}
    @Test public void lowerHoldStartsAtThresholdAndNeverUndoes(){State s=new State();PenButtons b=new PenButtons(s);b.lower(true,100,false);b.hold(299);assertFalse(s.eraser);b.hold(300);assertTrue(s.eraser);b.hold(500);assertEquals(1,s.changes);b.lower(false,800,false);assertFalse(s.eraser);assertEquals(2,s.changes);assertEquals(0,s.undos);}
    @Test public void repeatsDoNotMoveDeadlineOrRepeatUndo(){State s=new State();PenButtons b=new PenButtons(s);b.lower(true,100,false);b.lower(true,150,false);assertEquals(300,b.lowerDeadline());b.lower(false,180,false);b.lower(false,181,false);assertEquals(1,s.undos);}
    @Test public void canceledAndUnpairedReleasesDoNotUndo(){State s=new State();PenButtons b=new PenButtons(s);b.lower(false,10,false);b.lower(true,100,false);b.lower(false,150,true);assertEquals(0,s.undos);b.hold(400);assertFalse(s.eraser);}
    @Test public void delayedTimerCannotTurnLongReleaseIntoUndo(){State s=new State();PenButtons b=new PenButtons(s);b.lower(true,100,false);b.lower(false,300,false);b.hold(350);assertEquals(0,s.undos);assertFalse(s.eraser);}
    @Test public void losingFocusClearsPendingAndActiveHold(){State s=new State();PenButtons b=new PenButtons(s);b.lower(true,100,false);b.reset();b.hold(300);b.lower(false,310,false);assertEquals(0,s.undos);assertFalse(s.eraser);b.lower(true,400,false);b.hold(600);assertTrue(s.eraser);b.reset();assertFalse(s.eraser);b.lower(false,650,false);assertEquals(0,s.undos);}
    @Test public void upperOnlyShortCompletedPressToggles(){State s=new State();PenButtons b=new PenButtons(s);b.upper(true,100,false);b.upper(true,200,false);b.upper(false,250,false);assertEquals(1,s.toggles);b.upper(true,1000,false);b.upper(false,1800,false);assertEquals(1,s.toggles);b.upper(true,2000,false);b.upper(false,2100,true);assertEquals(1,s.toggles);b.upper(true,3000,false);b.reset();b.upper(false,3100,false);assertEquals(1,s.toggles);}
}
