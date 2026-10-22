package org.datamatrix.rawcapture;

/** Fit-center preview mapping; touch coordinates outside the displayed image are rejected. */
public final class PreviewMapping {
    final double vw,vh,bw,bh,scale,ox,oy;
    final int rotation;
    public PreviewMapping(double vw,double vh,double bw,double bh,int rotation) {
        this(vw,vh,bw,bh,rotation,1,bw/2,bh/2);
    }
    /** Display-only magnification centred on a buffer point, with inverse touch mapping. */
    public PreviewMapping(double vw,double vh,double bw,double bh,int rotation,double zoom,double cx,double cy) {
        if(!Double.isFinite(zoom)||zoom<1||zoom>8)throw new IllegalArgumentException("Display zoom must be 1..8");
        this.vw=vw; this.vh=vh; this.bw=bw; this.bh=bh; this.rotation=rotation;
        double rw=rotation%180==0?bw:bh, rh=rotation%180==0?bh:bw;
        double u=cx,v=cy;
        if(rotation==90){u=bh-cy;v=cx;}
        if(rotation==180){u=bw-cx;v=bh-cy;}
        if(rotation==270){u=cy;v=bw-cx;}
        scale=Math.min(vw/rw,vh/rh)*zoom;
        ox=zoom==1?(vw-rw*scale)/2:Math.max(vw-rw*scale,Math.min(0,vw/2-u*scale));
        oy=zoom==1?(vh-rh*scale)/2:Math.max(vh-rh*scale,Math.min(0,vh/2-v*scale));
        // Keep a dimension centred if its enlarged content still fits the view.
        // (Offsets above are final fields, so use the expression in toView/toBuffer.)
    }
    public double[] toView(double x,double y) {
        double u=x,v=y;
        if(rotation==90){u=bh-y;v=x;}
        if(rotation==180){u=bw-x;v=bh-y;}
        if(rotation==270){u=y;v=bw-x;}
        return new double[]{offsetX()+u*scale,offsetY()+v*scale};
    }
    double offsetX(){double rw=rotation%180==0?bw:bh;return rw*scale<vw?(vw-rw*scale)/2:ox;}
    double offsetY(){double rh=rotation%180==0?bh:bw;return rh*scale<vh?(vh-rh*scale)/2:oy;}
    public double[] toBuffer(double x,double y) {
        double u=(x-offsetX())/scale,v=(y-offsetY())/scale,bx=u,by=v;
        if(rotation==90){bx=v;by=bh-u;}
        if(rotation==180){bx=bw-u;by=bh-v;}
        if(rotation==270){bx=bw-v;by=u;}
        if(bx< -1e-8||by< -1e-8||bx>bw+1e-8||by>bh+1e-8)return null;
        return new double[]{Math.max(0,Math.min(bw,bx)),Math.max(0,Math.min(bh,by))};
    }
}
