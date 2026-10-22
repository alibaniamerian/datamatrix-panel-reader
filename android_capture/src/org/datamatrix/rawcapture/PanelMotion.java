package org.datamatrix.rawcapture;

import java.nio.ByteBuffer;

/** Regional averages and normalized correlation; does not change analysis pixels. */
final class PanelMotion {
    static final double LIVE_LIMIT=20;
    static final double OCCLUSION_LIMIT=80;
    static int[] signature(ByteBuffer source,int width,int height,int rowStride,int pixelStride){
        ByteBuffer data=source.duplicate();int base=data.position();int[] result=new int[32*24];
        for(int row=0;row<24;row++)for(int col=0;col<32;col++){
            int x0=col*width/32,x1=(col+1)*width/32,y0=row*height/24,y1=(row+1)*height/24;
            int sum=0;
            for(int sy=0;sy<8;sy++)for(int sx=0;sx<8;sx++){
                int x=Math.min(width-1,x0+(2*sx+1)*(x1-x0)/16),y=Math.min(height-1,y0+(2*sy+1)*(y1-y0)/16);
                sum+=data.get(base+y*rowStride+x*pixelStride)&255;
            }
            result[row*32+col]=(sum+32)/64;
        }return result;
    }
    static double difference(int[] a,int[] b){
        if(a==null||b==null||a.length!=b.length||a.length==0)return Double.MAX_VALUE;
        if(a.length!=768)return correlationDifference(a,b);
        double[] scores=new double[48];int count=0;
        // Independent local normalization tolerates nonuniform illumination/shadows.
        for(int row=0;row<24;row+=4)for(int col=0;col<32;col+=4){
            int[] x=new int[16],y=new int[16];int at=0;
            for(int dy=0;dy<4;dy++)for(int dx=0;dx<4;dx++){x[at]=a[(row+dy)*32+col+dx];y[at++]=b[(row+dy)*32+col+dx];}
            scores[count++]=correlationDifference(x,y);
        }
        java.util.Arrays.sort(scores);return (scores[23]+scores[24])/2;
    }
    static double correlationDifference(int[] a,int[] b){
        double sa=0,sb=0,saa=0,sbb=0,sab=0,absolute=0;
        for(int i=0;i<a.length;i++){sa+=a[i];sb+=b[i];saa+=(double)a[i]*a[i];sbb+=(double)b[i]*b[i];sab+=(double)a[i]*b[i];absolute+=Math.abs(a[i]-b[i]);}
        double va=Math.max(0,saa-sa*sa/a.length),vb=Math.max(0,sbb-sb*sb/a.length);
        if(va<1e-8||vb<1e-8)return va<1e-8&&vb<1e-8?absolute/a.length:100;
        double correlation=(sab-sa*sb/a.length)/Math.sqrt(va*vb);
        return 100*(1-Math.max(-1,Math.min(1,correlation)));
    }
}
