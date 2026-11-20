package io.github.fantuani.draftcanvas;
import org.junit.Test;
import static org.junit.Assert.*;
public class InkTest {
    @Test public void importedQuestionsAvoidExistingInkAndUseNormalHistory() {
        Ink ink = new Ink();
        ink.add(new Ink.Stroke(ink.nextId(), 0xff000000, 4, -475, -245, new float[]{0,0,1,160,0,1}));
        QuestionImage first = ink.importQuestion("a", 600, 400, 1000, 600, 30, 0);
        Ink.Stroke existing = ink.strokes.get(0);
        assertFalse(first.intersects(existing.left, existing.top, existing.right, existing.bottom));
        QuestionImage next = ink.importQuestion("b", 600, 400, 1000, 600, 30, first.id);
        assertEquals(first.x, next.x, 0);
        assertTrue(next.y > first.bottom());
        ink.select(next.x - 1, next.y - 1, next.right() + 1, next.bottom() + 1);
        ink.move(5, 9);
        assertEquals(next.x + 5, ink.question(next.id).x, 0);
        ink.undo();
        assertSame(next, ink.question(next.id));
        ink.undo();
        assertNull(ink.question(next.id));
        ink.redo();
        assertSame(next, ink.question(next.id));
    }
    private Ink.Stroke stroke(Ink d){return new Ink.Stroke(d.nextId(),0xff000000,2,100,200,new float[]{0,0,.5f,20,30,.7f});}
    @Test public void zoomKeepsFocusAndWorksAtDistantCoordinates(){Viewport v=new Viewport();v.x=1e11;v.y=-1e11;double x=v.worldX(231,1000),y=v.worldY(178,700);v.zoom(9.3,231,178,1000,700);assertEquals(x,v.worldX(231,1000),.001);assertEquals(y,v.worldY(178,700),.001);}
    @Test public void selectionMoveCopyPasteUndoIsIndependent(){Ink d=new Ink();d.add(stroke(d));d.select(90,190,140,240);d.copy();d.move(80,-20);assertEquals(180,d.strokes.get(0).x,0);d.paste(1000,700);assertEquals(2,d.strokes.size());assertEquals(124,d.strokes.get(1).x,0);d.undo();assertEquals(1,d.strokes.size());d.undo();assertEquals(100,d.strokes.get(0).x,0);d.redo();assertEquals(180,d.strokes.get(0).x,0);}
    @Test public void canceledEraseRestoresStrokeAndCommittedEraseUndoes(){Ink d=new Ink();d.add(stroke(d));d.begin();d.strokes.clear();d.cancel();assertEquals(1,d.strokes.size());d.begin();d.strokes.clear();d.commit();d.undo();assertEquals(1,d.strokes.size());}
    @Test public void rectangleRejectsEmptyPartOfStrokeBounds(){Ink d=new Ink();d.add(stroke(d));d.select(99,227,103,231);assertTrue(d.selected.isEmpty());d.select(108,211,113,219);assertEquals(1,d.selected.size());}
    @Test public void hitTestDoesNotEraseWholeBoundingBox(){Ink d=new Ink();Ink.Stroke s=stroke(d);assertTrue(s.hit(110,215,2));assertFalse(s.hit(100,230,2));}

    @Test public void concaveLassoSelectsInkAlongItsActualBoundary() {
        Ink ink = new Ink();
        Ink.Stroke inside = new Ink.Stroke(ink.nextId(), 0xff000000, 2, 15, 80, new float[]{0,0,1});
        Ink.Stroke notch = new Ink.Stroke(ink.nextId(), 0xff000000, 2, 80, 80, new float[]{0,0,1});
        Ink.Stroke crossing = new Ink.Stroke(ink.nextId(), 0xff000000, 2, -15, 50,
                new float[]{0,0,1,55,0,1}, true);
        ink.add(inside);
        ink.add(notch);
        ink.add(crossing);
        Lasso.Trace trace = new Lasso.Trace();
        double[] path = {0,0, 100,0, 100,25, 25,25, 25,100, 0,100};
        for (int i = 0; i < path.length; i += 2) trace.add(path[i], path[i + 1], 1);
        Lasso lasso = trace.finish(4);
        assertNotNull(lasso);
        ink.select(lasso);
        assertTrue(ink.selected.contains(inside.id));
        assertTrue(ink.selected.contains(crossing.id));
        assertFalse(ink.selected.contains(notch.id));
        Lasso resized = lasso.transform(2, 30, 40);
        assertTrue(resized.contains(60, 200));
        assertFalse(resized.contains(190, 200));
    }
}
