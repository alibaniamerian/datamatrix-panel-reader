package org.datamatrix.rawcapture;

public class ComponentSelectionTest {
    static void expect(int actual,int wanted){if(actual!=wanted)throw new AssertionError(actual+" != "+wanted);}
    public static void main(String[] args){
        double[][] frames={{.1,.2,.3,.2,.3,.4,.1,.4},{.6,.2,.6,.4,.8,.4,.8,.2}};
        expect(ComponentSelection.pick(.2,.3,frames),0);
        expect(ComponentSelection.pick(.7,.3,frames),1);
        expect(ComponentSelection.pick(.5,.3,frames),-1);
        expect(ComponentSelection.pick(.1,.2,frames),0);
        expect(ComponentSelection.pick(.9,.9,new double[][]{null}),-1);
        double[][] skew={{.1,.1,.4,.2,.35,.4,.05,.3}};
        expect(ComponentSelection.pick(.2,.25,skew),0);
        expect(ComponentSelection.pick(.39,.39,skew),-1);
        System.out.println("ComponentSelection: 7 hit tests passed");
    }
}
