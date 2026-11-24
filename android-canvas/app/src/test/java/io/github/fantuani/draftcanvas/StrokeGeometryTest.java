package io.github.fantuani.draftcanvas;

import org.junit.Test;
import static org.junit.Assert.*;

public class StrokeGeometryTest {
    @Test public void preservesTipEndpointsAndPressureRange(){
        float[] p=StrokeGeometry.smooth(new float[]{0,0,.2f,10,0,.5f,10,10,1},4);
        assertEquals(0,p[0],0);assertEquals(0,p[1],0);
        assertEquals(10,p[p.length-3],0);assertEquals(10,p[p.length-2],0);
        assertEquals(1,p[p.length-1],.0001);
        for(int i=0;i<p.length;i+=3){assertTrue(Float.isFinite(p[i]));assertTrue(p[i]>=0&&p[i]<=10);assertTrue(p[i+1]>=0&&p[i+1]<=10);assertTrue(p[i+2]>=.2f&&p[i+2]<=1);}
        boolean rounded=false;for(int i=0;i<p.length;i+=3)if(p[i]>5&&p[i]<10&&p[i+1]>0&&p[i+1]<5)rounded=true;
        assertTrue("corner should be a curve instead of two straight edges",rounded);
    }
    @Test public void removesStationaryNoiseWithoutLosingDot(){
        float[] p=StrokeGeometry.smooth(new float[]{0,0,.3f,0,0,.5f,0,0,.7f},4);
        assertEquals(3,p.length);assertEquals(.7f,p[2],0);
    }
    @Test public void keepsStraightLinesStraight(){
        float[] p=StrokeGeometry.smooth(new float[]{0,0,.5f,1,0,.5f,5,0,.5f,20,0,.5f},4);
        float previous=-1;for(int i=0;i<p.length;i+=3){assertEquals(0,p[i+1],0);assertTrue(p[i]>=previous);previous=p[i];}
        assertEquals(20,previous,0);
    }
    @Test public void attenuatesTinyAlternatingSampleJitter(){
        float[] input=new float[60];for(int i=0;i<20;i++){input[i*3]=i*.2f;input[i*3+1]=(i%2==0?.1f:-.1f);input[i*3+2]=.5f;}
        float[] p=StrokeGeometry.smooth(input,6);
        double variation=0;for(int i=3;i<p.length;i+=3)variation+=Math.abs(p[i+1]-p[i-2]);
        assertTrue("subpixel oscillations should not become jagged normals",variation<1);
        assertEquals(input[57],p[p.length-3],.0001);
    }
    @Test public void simplificationBoundsCurveErrorAndKeepsLoop(){
        float[] dense=new float[3000];for(int i=0;i<1000;i++){double t=i*Math.PI*2/999;dense[i*3]=(float)(100*Math.cos(t));dense[i*3+1]=(float)(100*Math.sin(t));dense[i*3+2]=1;}
        float[] small=StrokeGeometry.simplify(dense,.15f);assertTrue(small.length<dense.length/5);assertTrue(small.length>12);assertEquals(dense[0],small[0],0);assertEquals(dense[dense.length-3],small[small.length-3],0);
        for(int i=0;i<dense.length;i+=3){double best=Double.POSITIVE_INFINITY;for(int j=3;j<small.length;j+=3)best=Math.min(best,Eraser.distanceSquared(dense[i],dense[i+1],small[j-3],small[j-2],small[j],small[j+1]));assertTrue("simplification exceeded subpixel error budget",best<=.15*.15+.00001);}
    }
}
