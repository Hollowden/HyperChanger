// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook.newnotificationcenter;

import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/** Visual stretch at the resting list top. Native touch dispatch remains in charge. */
public final class IosShadeElastic implements ValueAnimator.AnimatorUpdateListener {
    private static final IosShadeElastic UPDATE=new IosShadeElastic();
    private static WeakReference<View> target=new WeakReference<>(null);
    private static final Rect bounds=new Rect();
    private static Method ownScroll;
    private static Class<?> stackType;
    private static boolean open,candidate,dragging;
    private static float downX,downY,anchorY,offset,base,applied;
    private static ValueAnimator settle;
    private IosShadeElastic() {}

    public static void beforeSync() {
        View v=target.get();
        if(v!=null && applied!=0f && Math.abs(v.getTranslationY()-base-applied)<.5f)
            v.setTranslationY(base);
        applied=0f;
    }
    public static void afterSync(View stack,float progress) {
        if(target.get()!=stack) {
            reset();target=new WeakReference<>(stack);stackType=null;ownScroll=null;
        }
        open=stack!=null && stack.isAttachedToWindow() && progress>=.999f
                && stack.getRootView().getWidth()<stack.getRootView().getHeight();
        if(!open) {reset();return;}
        base=stack.getTranslationY();
        draw();
    }
    private static void draw() {
        View v=target.get();
        if(v==null)return;
        v.setTranslationY(base+offset);applied=offset;
    }
    private static boolean atTop(View stack) {
        try {
            if(stackType!=stack.getClass()) {
                stackType=stack.getClass();ownScroll=stackType.getMethod("getOwnScrollY");
            }
            return ((Number)ownScroll.invoke(stack)).intValue()<=1;
        }catch(ReflectiveOperationException ignored) {return false;}
    }
    public static void touch(MotionEvent event) {
        View v=target.get();
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN) {
            candidate=false;dragging=false;
            if(!open || v==null || !v.isShown() || !v.getGlobalVisibleRect(bounds)
                    || !bounds.contains((int)event.getRawX(),(int)event.getRawY()))return;
            stop();offset=0;draw();
            downX=event.getRawX();downY=anchorY=event.getRawY();candidate=true;
            return;
        }
        if(!candidate)return;
        if(v==null || !open || event.getPointerCount()!=1 || action==MotionEvent.ACTION_CANCEL) {
            candidate=false;dragging=false;release();return;
        }
        if(action==MotionEvent.ACTION_MOVE) {
            float dx=event.getRawX()-downX,dy=event.getRawY()-downY;
            int slop=ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
            if(!dragging) {
                if(Math.abs(dx)>slop && Math.abs(dx)>Math.abs(dy)) {candidate=false;return;}
                if(dy<=slop || Math.abs(dy)<=Math.abs(dx))return;
                // A drag scrolling toward the top can start stretching after reaching it.
                if(!atTop(v)) {anchorY=event.getRawY();return;}
                dragging=true;anchorY=Math.max(anchorY,downY+slop);
            }
            float distance=Math.max(0,event.getRawY()-anchorY);
            float limit=64f*v.getResources().getDisplayMetrics().density;
            offset=limit*(1f-1f/(1f+distance/(limit*2f)));
            draw();return;
        }
        if(action==MotionEvent.ACTION_UP) {candidate=false;dragging=false;release();}
    }
    private static void stop() {if(settle!=null) {settle.cancel();settle=null;}}
    private static void release() {
        stop();
        if(offset<.5f) {offset=0;draw();return;}
        settle=ValueAnimator.ofFloat(offset,0f);
        settle.setDuration(280L);
        settle.setInterpolator(new DecelerateInterpolator(2f));
        settle.addUpdateListener(UPDATE);settle.start();
    }
    private static void reset() {
        stop();candidate=false;dragging=false;beforeSync();offset=0;open=false;
    }
    @Override public void onAnimationUpdate(ValueAnimator animation) {
        if(animation!=settle)return;
        offset=(Float)animation.getAnimatedValue();draw();
    }
}
