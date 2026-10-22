package org.datamatrix.rawcapture;

import java.util.ArrayList;
import java.util.List;

/** Camera-thread scheduler: service the least recently focused visible region. */
final class CodeFocusScheduler {
    static final class Entry {
        float[] quad;
        long visit;
        Entry(float[] quad,long visit){this.quad=quad;this.visit=visit;}
    }
    private ArrayList<Entry> entries=new ArrayList<>();
    private long clock;
    void clear(){entries.clear();clock=0;}
    int next(List<float[]> quads){
        ArrayList<Entry> fresh=new ArrayList<>();
        boolean[] used=new boolean[entries.size()];
        for(float[] quad:quads){
            int match=-1;double distance=Double.MAX_VALUE;
            for(int i=0;i<entries.size();i++)if(!used[i]){
                float[] prior=entries.get(i).quad;
                double d=centerDistance(quad,prior);
                if(d<radius(prior)*.7&&d<distance){match=i;distance=d;}
            }
            long visit=0;if(match>=0){used[match]=true;visit=entries.get(match).visit;}
            fresh.add(new Entry(quad,visit));
        }
        entries=fresh;
        if(entries.isEmpty())return -1;
        int next=0;for(int i=1;i<entries.size();i++)if(entries.get(i).visit<entries.get(next).visit)next=i;
        entries.get(next).visit=++clock;
        return next;
    }
    static double centerDistance(float[] a,float[] b){double x=0,y=0;for(int i=0;i<4;i++){x+=(a[2*i]-b[2*i])/4.;y+=(a[2*i+1]-b[2*i+1])/4.;}return Math.hypot(x,y);}
    static double radius(float[] q){double x=0,y=0;for(int i=0;i<4;i++){x+=q[2*i]/4.;y+=q[2*i+1]/4.;}double r=0;for(int i=0;i<4;i++)r=Math.max(r,Math.hypot(q[2*i]-x,q[2*i+1]-y));return r;}
}
