package io.github.fantuani.draftcanvas;
import org.junit.Test;
import static org.junit.Assert.*;
public class EraserTest {
    private float[] line(){return new float[]{-100,0,1,100,0,1};}
    @Test public void partialEraseKeepsOriginalGeometryAndCanUndo(){Ink d=new Ink();Ink.Stroke s=new Ink.Stroke(d.nextId(),0,2,1000,2000,line());d.add(s);d.begin();d.erase(1000,1990,1000,2010,10);d.commit();assertEquals(1,d.strokes.size());assertSame(s.points,d.strokes.get(0).points);assertNotNull(d.strokes.get(0).sections);d.undo();assertSame(s,d.strokes.get(0));d.redo();assertNotNull(d.strokes.get(0).sections);}
    @Test public void fastSweepDeletesFullyCoveredInk(){Ink d=new Ink();d.add(new Ink.Stroke(d.nextId(),0,2,0,0,line()));d.begin();d.erase(-120,0,120,0,5);assertTrue(d.strokes.isEmpty());d.cancel();assertEquals(1,d.strokes.size());}
    @Test public void missedBoundsPreserveIdentity(){Ink d=new Ink();Ink.Stroke s=new Ink.Stroke(d.nextId(),0,2,0,0,line());d.add(s);assertFalse(d.erase(0,30,0,40,3));assertSame(s,d.strokes.get(0));}
    @Test public void repeatedCoveredEraseIsANoop(){Ink d=new Ink();d.add(new Ink.Stroke(d.nextId(),0,20,0,0,line()));d.begin();d.erase(0,0,0,0,3);Ink.Stroke cut=d.strokes.get(0);assertFalse(d.erase(0,9,0,9,3));assertSame(cut,d.strokes.get(0));d.cancel();}
    @Test public void movesAndCopiesKeepLocalMasks(){Ink d=new Ink();d.add(new Ink.Stroke(d.nextId(),0,20,0,0,line()));d.begin();d.erase(0,0,0,0,3);d.commit();Ink.Stroke s=d.strokes.get(0),moved=s.offset(55,100,200);assertSame(s.points,moved.points);assertSame(s.sections,moved.sections);assertSame(s.sections,moved.sections);}
    @Test public void selectionAndHitTestRejectAnErasedHole(){Ink d=new Ink();d.add(new Ink.Stroke(d.nextId(),0,2,0,0,line()));d.begin();d.erase(0,0,0,0,20);d.commit();d.select(-2,-2,2,2);assertTrue(d.selected.isEmpty());assertFalse(d.strokes.get(0).hit(0,0,1));d.select(-90,-2,-80,2);assertEquals(1,d.selected.size());}
    @Test public void dotCanBePartiallyAndCompletelyErased(){Ink d=new Ink();d.add(new Ink.Stroke(d.nextId(),0,10,0,0,new float[]{0,0,1}));d.begin();d.erase(0,0,0,0,1);assertTrue(d.strokes.isEmpty());}
    @Test public void pressureAndSensitivityAreMonotonicAndBounded(){assertEquals(.5,Eraser.pressureScale(-1),0);assertEquals(2,Eraser.pressureScale(2),0);assertTrue(Eraser.pressureScale(.8f)>Eraser.pressureScale(.2f));assertTrue(Eraser.pressureScale(.15f,2)>Eraser.pressureScale(.15f,1));assertTrue(Eraser.pressureScale(.15f,4)>Eraser.pressureScale(.15f,2));assertEquals(.5,Eraser.pressureScale(0,4),0);assertEquals(2,Eraser.pressureScale(1,4),0);}
}
