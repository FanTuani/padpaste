package io.github.fantuani.draftcanvas;
import org.junit.Test;
import static org.junit.Assert.*;
public class InkTest {
    private Ink.Stroke stroke(Ink d){return new Ink.Stroke(d.nextId(),0xff000000,2,100,200,new float[]{0,0,.5f,20,30,.7f});}
    @Test public void zoomKeepsFocusAndWorksAtDistantCoordinates(){Viewport v=new Viewport();v.x=1e11;v.y=-1e11;double x=v.worldX(231,1000),y=v.worldY(178,700);v.zoom(9.3,231,178,1000,700);assertEquals(x,v.worldX(231,1000),.001);assertEquals(y,v.worldY(178,700),.001);}
    @Test public void selectionMoveCopyPasteUndoIsIndependent(){Ink d=new Ink();d.add(stroke(d));d.select(90,190,140,240);d.copy();d.move(80,-20);assertEquals(180,d.strokes.get(0).x,0);d.paste(1000,700);assertEquals(2,d.strokes.size());assertEquals(124,d.strokes.get(1).x,0);d.undo();assertEquals(1,d.strokes.size());d.undo();assertEquals(100,d.strokes.get(0).x,0);d.redo();assertEquals(180,d.strokes.get(0).x,0);}
    @Test public void canceledEraseRestoresStrokeAndCommittedEraseUndoes(){Ink d=new Ink();d.add(stroke(d));d.begin();d.strokes.clear();d.cancel();assertEquals(1,d.strokes.size());d.begin();d.strokes.clear();d.commit();d.undo();assertEquals(1,d.strokes.size());}
    @Test public void hitTestDoesNotEraseWholeBoundingBox(){Ink d=new Ink();Ink.Stroke s=stroke(d);assertTrue(s.hit(110,215,2));assertFalse(s.hit(100,230,2));}
}
