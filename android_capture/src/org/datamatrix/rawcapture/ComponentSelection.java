package org.datamatrix.rawcapture;

/** Select only a quadrilateral actually touched, in native normalized coordinates. */
final class ComponentSelection {
    static int pick(double x,double y,double[][] quads){
        int best=-1;double distance=Double.MAX_VALUE;
        for(int n=0;n<quads.length;n++){
            double[] q=quads[n];if(q==null||q.length!=8)continue;
            boolean positive=false,negative=false;double cx=0,cy=0;
            for(int i=0;i<4;i++){int j=(i+1)%4;double cross=(q[j*2]-q[i*2])*(y-q[i*2+1])-(q[j*2+1]-q[i*2+1])*(x-q[i*2]);positive|=cross>1e-9;negative|=cross< -1e-9;cx+=q[i*2]/4;cy+=q[i*2+1]/4;}
            if(positive&&negative)continue;
            double d=Math.hypot(x-cx,y-cy);if(d<distance){distance=d;best=n;}
        }return best;
    }
}
