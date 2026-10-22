package org.datamatrix.rawcapture;

public final class PreviewMappingTest {
    public static void main(String[] args) {
        int checks=0;
        for(int rotation:new int[]{0,90,180,270}) {
            for(int[] view:new int[][]{{1080,1800},{1800,1080},{600,600}}) {
                PreviewMapping mapping=new PreviewMapping(view[0],view[1],1440,1080,rotation);
                for(double[] point:new double[][]{{0,0},{1440,0},{0,1080},{1440,1080},{720,540},{134,678}}) {
                    double[] screen=mapping.toView(point[0],point[1]);
                    double[] back=mapping.toBuffer(screen[0],screen[1]);
                    if(back==null||Math.abs(back[0]-point[0])>1e-8||Math.abs(back[1]-point[1])>1e-8)throw new AssertionError("Mapping round trip");
                    checks++;
                }
                if(mapping.toBuffer(-100,-100)!=null)throw new AssertionError("Letterbox rejected");
            }
        }
        for(int rotation:new int[]{0,90,180,270}) {
            for(double zoom:new double[]{1,2,4,8}) {
                for(double[] anchor:new double[][]{{720,540},{134,678},{0,0},{1440,1080}}) {
                    PreviewMapping mapping=new PreviewMapping(1080,1800,1440,1080,rotation,zoom,anchor[0],anchor[1]);
                    for(double[] point:new double[][]{{0,0},{1440,1080},{720,540},{134,678}}) {
                        double[] screen=mapping.toView(point[0],point[1]);
                        double[] back=mapping.toBuffer(screen[0],screen[1]);
                        if(back==null||Math.abs(back[0]-point[0])>1e-8||Math.abs(back[1]-point[1])>1e-8)throw new AssertionError("Zoom focus mapping round trip");
                        checks++;
                    }
                    // Display centre must map to a valid source location at every zoom.
                    if(mapping.toBuffer(540,900)==null)throw new AssertionError("Invalid zoom centre");
                }
            }
        }
        PreviewMapping base=new PreviewMapping(1080,1800,1440,1080,90);
        PreviewMapping magnified=new PreviewMapping(1080,1800,1440,1080,90,4,720,540);
        double[] p=base.toView(800,550),q=magnified.toView(800,550);
        if(Math.abs(q[0]-540-4*(p[0]-540))>1e-8||Math.abs(q[1]-900-4*(p[1]-900))>1e-8)throw new AssertionError("Expected fourfold display magnification");
        double[] tapped=magnified.toBuffer(q[0],q[1]);
        if(Math.abs(tapped[0]-800)>1e-8||Math.abs(tapped[1]-550)>1e-8)throw new AssertionError("Magnified tap must retain camera coordinates");
        System.out.println("Preview mapping: "+checks+" round-trip checks passed; outside-image touches rejected.");
    }
}
