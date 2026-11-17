package io.github.fantuani.draftcanvas;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class EraserTest {
    private float[] line(){return new float[]{-100,0,1,100,0,1};}
    @Test public void circleSplitsOneStrokeAndPreservesBothSides(){List<float[]> p=Eraser.cut(line(),0,0,0,0,10);assertEquals(2,p.size());assertEquals(-10,p.get(0)[3],.001);assertEquals(10,p.get(1)[0],.001);}
    @Test public void fastSweepCannotSkipInkBetweenSamples(){List<float[]> p=Eraser.cut(line(),0,-100,0,100,12);assertEquals(2,p.size());assertEquals(-12,p.get(0)[3],.001);assertEquals(12,p.get(1)[0],.001);}
    @Test public void diagonalSweepAndEndpointCapsAreClipped(){List<float[]> p=Eraser.cut(line(),-10,-10,10,10,10);assertEquals(2,p.size());assertEquals(-Math.sqrt(200),p.get(0)[3],.01);assertEquals(Math.sqrt(200),p.get(1)[0],.01);assertNotNull(Eraser.cut(line(),0,9,0,20,10));}
    @Test public void missedAndTangentInkStayUntouched(){assertNull(Eraser.cut(line(),0,20,0,30,10));assertNull(Eraser.cut(line(),0,10,0,10,10));}
    @Test public void handlesDotsAndFullDeletion(){assertTrue(Eraser.cut(new float[]{0,0,1},0,-20,0,20,2).isEmpty());assertNull(Eraser.cut(new float[]{30,0,1},0,-20,0,20,2));assertTrue(Eraser.cut(line(),0,0,0,0,101).isEmpty());}
    @Test public void pressureEnlargesEraserAndClampsRange(){assertEquals(.5,Eraser.pressureScale(-1),0);assertEquals(2,Eraser.pressureScale(2),0);assertTrue(Eraser.pressureScale(.8f)>Eraser.pressureScale(.2f));}
    @Test public void eraseGestureUndoesAndCancellationRestoresOriginalCurve(){Ink d=new Ink();Ink.Stroke original=new Ink.Stroke(d.nextId(),0xff000000,2,1000,2000,line());d.add(original);d.begin();d.erase(1000,1900,1000,2100,10);assertEquals(2,d.strokes.size());assertTrue(d.strokes.get(0).right<1000);assertTrue(d.strokes.get(1).left>1000);d.commit();d.undo();assertSame(original,d.strokes.get(0));d.redo();assertEquals(2,d.strokes.size());List<Ink.Stroke> before=new ArrayList<>(d.strokes);d.begin();d.erase(900,2000,1100,2000,10);assertTrue(d.strokes.isEmpty());d.cancel();assertEquals(before,d.strokes);}
    @Test public void fragmentedCurveIsNotSmoothedAgainOnMoveOrCopy(){Ink d=new Ink();d.add(new Ink.Stroke(d.nextId(),0,2,0,0,line()));d.begin();d.erase(0,-100,0,100,10);d.commit();for(Ink.Stroke s:d.strokes){assertSame(s.points,s.pathPoints());assertSame(s.points,s.offset(55,4,8).pathPoints());}}
    @Test public void smallEraserCutsThickInkEdgesWithoutDeletingItsCenterline(){Ink d=new Ink();Ink.Stroke s=new Ink.Stroke(d.nextId(),0,20,0,0,line());d.add(s);d.begin();d.erase(0,9,0,9,3);d.commit();assertEquals(1,d.strokes.size());Ink.Stroke cut=d.strokes.get(0);assertNotNull(cut.cuts);assertSame(s.points,cut.points);assertSame(cut.cuts,cut.offset(77,100,200).cuts);assertEquals(3,cut.cuts.radius,0);d.undo();assertSame(s,d.strokes.get(0));}
}
