// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook.newnotificationcenter;

import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;

/** Fade hosted time glyphs and their date together before clipping; never move the native notification rows. */
public final class IosShadeMotion {
    private IosShadeMotion() {}

    private static WeakReference<View> owner=new WeakReference<>(null);
    private static final ArrayList<Part> parts=new ArrayList<>();
    private static long scanned;
    private static Part companion;
    private static Part clockLayer;
    private static final Matrix matrix=new Matrix();
    private static final RectF box=new RectF();
    private static final int[] point=new int[2];
    private static final Method transitionAlpha=transitionMethod();
    private static Method transitionMethod() {
        try { Method m=View.class.getDeclaredMethod("setTransitionAlpha",float.class);m.setAccessible(true);return m; }
        catch(ReflectiveOperationException ignored) { return null; }
    }
    private static final class Part {
        final WeakReference<View> view;
        final Method glyphs;
        float applied=Float.NaN,baseAlpha=1f;
        Part(View value,Method method) {view=new WeakReference<>(value);glyphs=method;}
        void alpha(float value) {
            View v=view.get();if(v==null) return;
            if(transitionAlpha!=null) try {transitionAlpha.invoke(v,value);applied=value;return;}
                catch(ReflectiveOperationException ignored) {}
            float current=v.getAlpha();
            if(!Float.isFinite(applied) || Math.abs(current-baseAlpha*applied)>.001f) baseAlpha=current;
            v.setAlpha(baseAlpha*value);applied=value;
        }
    }
    private static void restore() {if(clockLayer!=null)clockLayer.alpha(1f);if(companion!=null)companion.alpha(1f);}
    public static void apply(View clock,View stack,View panel,float progress,View info) {
        IosShadeElastic.afterSync(stack,progress);
        if(companion==null || companion.view.get()!=info) {
            if(companion!=null)companion.alpha(1f);
            companion=info==null?null:new Part(info,null);
        }
        if(clock==null || panel==null) {restore();return;}
        if(owner.get()!=clock) {
            restore();parts.clear();owner=new WeakReference<>(clock);
            clockLayer=new Part(clock,null);scanned=0;
        }
        if(progress<.95f || panel.getWidth()>panel.getHeight()) {restore();return;}
        long now=SystemClock.uptimeMillis();
        if(scanned==0 || now-scanned>=750L) {
            ArrayList<Part> found=new ArrayList<>();collect(clock,found);
            for(Part old:parts) {
                for(int i=0;i<found.size();i++) if(found.get(i).view.get()==old.view.get()) {found.set(i,old);break;}

            }
            parts.clear();parts.addAll(found);scanned=now;
        }
        float top=Float.POSITIVE_INFINITY;
        for(Part p:parts) {
            View v=p.view.get();if(v==null || !v.isShown() || !drawnInside(v,clock))continue;
            boolean measured=false;
            if(p.glyphs!=null) try {
                Object r=p.glyphs.invoke(v);
                if(r instanceof RectF && ((RectF)r).height()>0) {box.set((RectF)r);measured=true;}
            }catch(ReflectiveOperationException ignored){}
            if(!measured && v instanceof TextView) {
                TextView text=(TextView)v;
                android.graphics.Paint.FontMetrics fm=text.getPaint().getFontMetrics();
                box.set(0,text.getBaseline()+fm.ascent,v.getWidth(),text.getBaseline()+fm.descent);measured=box.height()>0;
            }
            if(!measured)continue;
            matrix.reset();v.transformMatrixToGlobal(matrix);matrix.mapRect(box);
            top=Math.min(top,box.top);
        }
        if(!Float.isFinite(top)) {restore();return;}
        float density=clock.getResources().getDisplayMetrics().density;
        int id=clock.getResources().getIdentifier("status_bar_height","dimen","android");
        int status=id==0?Math.round(28*density):clock.getResources().getDimensionPixelSize(id);
        panel.getRootView().getLocationOnScreen(point);
        float alpha=IosShadeMotionMath.clockFade(top,point[1]+status+12*density,64*density);
        // Keep the OEM's per-glyph alpha untouched, including its alternate faces.
        clockLayer.alpha(alpha);
        if(companion!=null)companion.alpha(inside(info,clock)?1f:alpha);
    }
    private static boolean drawnInside(View view,View root) {
        for(View v=view;v!=null && v!=root;) {
            if(v.getVisibility()!=View.VISIBLE || v.getAlpha()<=.01f)return false;
            ViewParent p=v.getParent();v=p instanceof View?(View)p:null;
        }
        return inside(view,root);
    }
    private static boolean inside(View view,View root) {
        for(View v=view;v!=null;) {if(v==root)return true;ViewParent p=v.getParent();v=p instanceof View?(View)p:null;}return false;
    }
    private static void collect(View v,ArrayList<Part> result) {
        Method glyphs=null;
        try {glyphs=v.getClass().getMethod("getTextBoundsWithPosition");glyphs.setAccessible(true);}catch(NoSuchMethodException ignored){}
        if(glyphs!=null || (v instanceof TextView && isTime(((TextView)v).getText()))) {result.add(new Part(v,glyphs));return;}
        if(v instanceof ViewGroup) {ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)collect(g.getChildAt(i),result);}
    }
    private static boolean isTime(CharSequence value) {
        if(value==null || value.length()==0 || value.length()>5)return false;
        boolean digit=false;
        for(int i=0;i<value.length();i++) {char c=value.charAt(i);if(c>='0'&&c<='9')digit=true;else if(c!=':'&&c!='：')return false;}
        return digit;
    }
}
