package btm.m.os4.systemuihook.newnotificationcenter;
public final class IosShadeMotionMathTest {
    public static void main(String[] args) {
        check(IosShadeMotionMath.clockFade(100,120,100)==0,"hide before boundary");
        check(IosShadeMotionMath.clockFade(220,120,100)==1,"full clock remains visible");
        check(Math.abs(IosShadeMotionMath.clockFade(170,120,100)-.5f)<.0001,"smooth midpoint");
        float previous=0;
        for(int i=0;i<=1000;i++) {
            float alpha=IosShadeMotionMath.clockFade(120+i/10f,120,100);
            check(alpha>=previous && alpha<=1,"bounded monotonic fade");
            previous=alpha;
        }
        check(IosShadeMotionMath.clockFade(Float.NaN,120,100)==1,"invalid geometry restores");
        System.out.println("PASS: smooth bounded clock fade and invalid geometry recovery");
    }
    static void check(boolean value,String label) {if(!value)throw new AssertionError(label);}
}
