package io.github.fantuani.draftcanvas;
import org.junit.Test;
import java.util.Random;
import static org.junit.Assert.*;
public class StrokeDataTest {
    @Test public void variableWidthCutUsesTheLocalRadius() {
        StrokeData line = new StrokeData(new float[]{0,0,.5f,100,0,1.5f});
        for (double[] eraser : new double[][]{{8,9,12,9}, {75,11,95,11}, {35,-12,55,17}, {50,9,50,9}}) {
            double[] ranges = line.erase(null, eraser[0], eraser[1], eraser[2], eraser[3], 2, 10, true, 15);
            for (int i = 0; i < 1000; i++) {
                double t = (i + .37) / 1000;
                double r = 2 + 10 * (.5 + t);
                double distance = Eraser.distanceSquared(t * 100, 0, eraser[0], eraser[1], eraser[2], eraser[3]);
                assertEquals(distance > r * r, StrokeData.visible(ranges, t, t));
            }
        }
    }
    @Test public void circleAndFastSweepTrimVisibleParameterRanges(){StrokeData d=new StrokeData(new float[]{-100,0,1,100,0,1});double[] r=d.erase(null,0,0,0,0,10);assertArrayEquals(new double[]{0,.45,.55,1},r,.000001);assertSame(r,d.erase(r,0,0,0,0,10));r=d.erase(r,0,-100,0,100,20);assertArrayEquals(new double[]{0,.4,.6,1},r,.000001);assertEquals(-20,d.coordinate(r[1],0),.00001);}
    @Test public void overlappingCutsMergeAndCanRemoveEverything(){StrokeData d=new StrokeData(new float[]{0,0,1,100,0,1});double[] r=d.erase(null,30,0,50,0,10);assertArrayEquals(new double[]{0,.2,.6,1},r,.000001);r=d.erase(r,55,0,70,0,10);assertArrayEquals(new double[]{0,.2,.8,1},r,.000001);assertEquals(0,d.erase(r,-10,0,110,0,10).length);}
    @Test public void bvhCutAgreesWithPointCoverageAcrossDenseCurve(){float[] p=new float[1500];for(int i=0;i<500;i++){p[i*3]=i;p[i*3+1]=(float)(40*Math.sin(i*.1));p[i*3+2]=1;}StrokeData d=new StrokeData(p);double[] ranges=null;Random random=new Random(42);double[][] cuts=new double[20][5];for(int j=0;j<20;j++){cuts[j]=new double[]{random.nextDouble()*500,random.nextDouble()*80-40,random.nextDouble()*500,random.nextDouble()*80-40,2+random.nextDouble()*10};double[] c=cuts[j];ranges=d.erase(ranges,c[0],c[1],c[2],c[3],c[4]);}for(int i=0;i<498;i++){double t=i+.37,x=d.coordinate(t,0),y=d.coordinate(t,1);boolean expected=true;for(double[] c:cuts)if(Eraser.distanceSquared(x,y,c[0],c[1],c[2],c[3])<c[4]*c[4]-1e-5)expected=false;assertEquals(expected,StrokeData.visible(ranges,t,t));}}
}
