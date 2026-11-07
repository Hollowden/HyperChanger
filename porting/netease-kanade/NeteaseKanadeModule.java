package btm.m.ported.superliquid.io.github.liuran001.mmliquidglass;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.media.AudioManager;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import android.animation.ValueAnimator;
import io.github.libxposed.api.*;
import java.lang.ref.WeakReference;
import java.util.*;

/** Kanade-inspired navigation for CloudMusic 9.6.21; all actions remain native. */
public final class NeteaseKanadeModule extends XposedModule {
    static final String PKG="com.netease.cloudmusic", TAG="HC-KanadeNav";
    static final String SCOPE="scope."+PKG+".";
    final WeakHashMap<Activity,Session> sessions=new WeakHashMap<>();
    SharedPreferences prefs;
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam p) {
        if(!p.isFirstPackage() || !PKG.equals(p.getPackageName()))return;
        try {
            prefs=getRemotePreferences("hyper_system_ui_hook");
            if(!prefs.getBoolean(SCOPE+"enabled",true))return;
            hook(Instrumentation.class.getMethod("callActivityOnCreate",Activity.class,Bundle.class))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    Activity a=(Activity)chain.getArg(0);
                    if(RouteMorph.pending!=null&&a.getClass().getName().endsWith(".PlayerActivity"))a.getWindow().setWindowAnimations(0);
                    return chain.proceed();
                });
            hook(Instrumentation.class.getMethod("callActivityOnResume",Activity.class))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    Object result=chain.proceed();
                    try { Activity a=(Activity)chain.getArg(0); bind(a); }
                    catch(Throwable e){Log.e(TAG,"resume",e);}
                    return result;
                });
            hook(Instrumentation.class.getMethod("callActivityOnDestroy",Activity.class))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    Activity a=(Activity)chain.getArg(0);Session s=sessions.remove(a);
                    if(s!=null)s.restore();return chain.proceed();
                });
            hook(Instrumentation.class.getMethod("callActivityOnPause",Activity.class))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    Session s=sessions.get((Activity)chain.getArg(0));if(s!=null)s.pause();return chain.proceed();
                });
            Log.i(TAG,"installed native resume bridge");
        }catch(Throwable e){Log.e(TAG,"install",e);}
    }
    void bind(Activity a) {
        if(a.isFinishing())return;
        if(a.getClass().getName().endsWith(".PlayerActivity")){RouteMorph.enter(a);return;}
        Session existing=sessions.get(a);if(existing!=null){existing.resume();return;}
        Session s=new Session(a,prefs);sessions.put(a,s);s.probe(0);
    }
    static View find(View v,String name) {
        int id=v.getResources().getIdentifier(name,"id",PKG);
        return id==0?null:v.findViewById(id);
    }
    static String id(View v){try{return v.getResources().getResourceEntryName(v.getId());}catch(Exception e){return "-";}}
    static View clickable(View v) {
        if(v.isClickable())return v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){
            View c=clickable(((ViewGroup)v).getChildAt(i));if(c!=null)return c;
        }
        return null;
    }
    static boolean selected(View v) {
        if(v.isSelected() || v instanceof Checkable && ((Checkable)v).isChecked())return true;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)if(selected(((ViewGroup)v).getChildAt(i)))return true;
        return false;
    }
    static TextView text(View v){if(v instanceof TextView)return (TextView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView t=text(((ViewGroup)v).getChildAt(i));if(t!=null)return t;}return null;}
    /** Damped physical spring, using the GlassSpring bouncy 500ms / .3 preset.
     * Retargeting keeps the current velocity instead of restarting a tween. */
    static final class SpringValue implements Choreographer.FrameCallback {
        double value,velocity,target;boolean running;long frame;
        final Runnable update;
        SpringValue(double initial,Runnable update){value=target=initial;this.update=update;}
        void to(double next){target=next;if(!running){running=true;frame=0;Choreographer.getInstance().postFrameCallback(this);}}
        void snap(double next){stop();value=target=next;velocity=0;update.run();}
        void stop(){running=false;frame=0;Choreographer.getInstance().removeFrameCallback(this);}
        public void doFrame(long now){if(!running)return;double dt=frame==0?1.0/60:Math.min((now-frame)*1e-9,.04);frame=now;
            double omega=2*Math.PI/.5,zeta=.7,wd=omega*Math.sqrt(1-zeta*zeta),decay=Math.exp(-zeta*omega*dt);
            double x=value-target,b=(velocity+zeta*omega*x)/wd,co=Math.cos(wd*dt),si=Math.sin(wd*dt);
            double next=decay*(x*co+b*si);
            velocity=decay*((-x*wd*si+b*wd*co)-zeta*omega*(x*co+b*si));value=target+next;
            if(Math.abs(value-target)<.0008&&Math.abs(velocity)<.008){value=target;velocity=0;running=false;}
            update.run();if(running)Choreographer.getInstance().postFrameCallback(this);
        }
    }
    /** A bounded shared-artwork transition over the real native player route. */
    static final class RouteMorph {
        static RouteMorph pending;
        final Bitmap background,artwork;final Rect source;final long created=SystemClock.uptimeMillis();
        RouteMorph(Bitmap bg,Bitmap art,Rect src){background=bg;artwork=art;source=src;}
        static void open(Session s){
            Activity a=s.activity.get();View source=s.homePlayer!=null?s.homePlayer:s.detail;
            if(a==null||source==null||!source.isAttachedToWindow()){s.openNativePlayer();return;}
            View decor=a.getWindow().getDecorView();int w=decor.getWidth(),h=decor.getHeight();if(w==0||h==0){s.openNativePlayer();return;}
            int[] xy=new int[2];source.getLocationInWindow(xy);int size=s.dp(46);
            Rect rect=new Rect(xy[0]+s.dp(6),xy[1]+(source.getHeight()-size)/2,xy[0]+s.dp(6)+size,xy[1]+(source.getHeight()+size)/2);
            rect.intersect(0,0,w,h);if(rect.isEmpty()){s.openNativePlayer();return;}
            Bitmap bg=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
            try {PixelCopy.request(a.getWindow(),bg,result->{
                if(result!=PixelCopy.SUCCESS){bg.recycle();s.openNativePlayer();return;}
                Bitmap art=Bitmap.createBitmap(bg,rect.left,rect.top,rect.width(),rect.height());
                RouteMorph state=new RouteMorph(bg,art,rect);pending=state;
                s.openNativePlayer();a.overridePendingTransition(0,0);
                decor.postDelayed(()->{if(pending==state){pending=null;state.recycle();}},2500);
            },new Handler(Looper.getMainLooper()));}catch(Throwable error){bg.recycle();s.openNativePlayer();}
        }
        static void enter(Activity a){
            RouteMorph state=pending;if(state==null||SystemClock.uptimeMillis()-state.created>2200)return;pending=null;
            a.overridePendingTransition(0,0);a.getWindow().setWindowAnimations(0);
            ViewGroup decor=(ViewGroup)a.getWindow().getDecorView();ViewGroup content=a.findViewById(android.R.id.content);
            if(content==null){state.recycle();return;}
            RouteView view=new RouteView(a,state,content);content.setAlpha(1f);
            decor.addView(view,new ViewGroup.LayoutParams(-1,-1));
            view.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener(){
                public boolean onPreDraw(){view.getViewTreeObserver().removeOnPreDrawListener(this);view.start();return true;}
            });
            view.postDelayed(()->view.finish(),1300);
        }
        void recycle(){if(!background.isRecycled())background.recycle();if(!artwork.isRecycled())artwork.recycle();}
    }
    static final class RouteView extends View {
        final RouteMorph state;final ViewGroup content;final Paint p=new Paint(3);final Path clip=new Path();
        final SpringValue progress;boolean finished;long started;
        RouteView(Context c,RouteMorph state,ViewGroup content){super(c);this.state=state;this.content=content;setClickable(true);progress=new SpringValue(0,()->{invalidate();if(started>0&&SystemClock.uptimeMillis()-started>=350)finish();});}
        double progressValue(){return progress==null?0:progress.value;}
        boolean progressRunning(){return progress!=null&&progress.running;}
        void start(){
            started=SystemClock.uptimeMillis();
            Log.i(TAG,"player route: bounded album pulse + native page crossfade");
            progress.to(1);
        }
        protected void onDraw(Canvas c){super.onDraw(c);if(finished||state.background.isRecycled())return;
            float t=started==0?0:Math.min(1,(SystemClock.uptimeMillis()-started)/350f),ease=t*t*(3-2*t),fade=1-ease;
            p.setAlpha((int)(255*fade));c.save();clip.reset();clip.addOval(new RectF(state.source),Path.Direction.CW);c.clipOutPath(clip);c.drawBitmap(state.background,null,new RectF(0,0,getWidth(),getHeight()),p);c.restore();
            RectF rect=new RectF(state.source);float grow=1+.10f*(float)Math.sin(Math.PI*ease);rect.inset(-rect.width()*(grow-1)/2,-rect.height()*(grow-1)/2);
            clip.reset();clip.addOval(rect,Path.Direction.CW);c.save();c.clipPath(clip);p.setAlpha((int)(255*fade));c.drawBitmap(state.artwork,null,rect,p);c.restore();
        }
        void finish(){if(finished)return;finished=true;progress.stop();content.setAlpha(1f);if(getParent() instanceof ViewGroup)((ViewGroup)getParent()).removeView(this);state.recycle();Log.i(TAG,"player route crossfade complete");}
        protected void onDetachedFromWindow(){if(!finished)finish();super.onDetachedFromWindow();}
    }
    static final class Session {
        final WeakReference<Activity> activity;
        final SharedPreferences prefs;
        final float d;
        ViewGroup nativeBar,content;
        View mini,playerClick,playButton,listButton;
        FrameLayout overlay;
        LiquidGlassHostLayout host,discHost;
        NavView nav;
        DiscView disc;
        DetailView detail;
        DetailView homePlayer;
        final SpringValue morph;
        Window.Callback oldCallback,observedCallback;
        float gestureX,gestureY;boolean observeScroll;
        int accent=0xffe60026;
        View[] tabs;
        long last;
        boolean updating;
        boolean resumed=true;
        long resumedAt=SystemClock.uptimeMillis();
        boolean playing;long playbackRead;
        java.lang.reflect.Method playbackMethod;
        final Runnable tick=new Runnable(){public void run(){Activity a=activity.get();if(!resumed||a==null||a.isDestroyed()||overlay==null)return;try{sync();}catch(Throwable e){Log.e(TAG,"state refresh",e);}overlay.postDelayed(this,300);}};
        Session(Activity a,SharedPreferences p){activity=new WeakReference<>(a);prefs=p;d=a.getResources().getDisplayMetrics().density;morph=new SpringValue(0,()->layoutHome());}
        int dp(float f){return Math.round(f*d);}
        void probe(int attempt){
            Activity a=activity.get();if(a==null||a.isDestroyed()||a.isFinishing())return;
            View bar=find(a.getWindow().getDecorView(),"bottomNav");
            if(bar instanceof ViewGroup && ((ViewGroup)bar).getChildCount()>=3 && bar.getWidth()>0){
                try {install(a,(ViewGroup)bar);}catch(Throwable e){Log.e(TAG,"layout installation failed",e);restore();}
            } else {
                View m=find(a.getWindow().getDecorView(),"minPlayerBarContainer");
                if(m!=null&&m.getWidth()>0&&m.getVisibility()==View.VISIBLE){
                    try {installDetail(a,m);}catch(Throwable e){Log.e(TAG,"detail install",e);restore();}
                }else if(attempt<30) a.getWindow().getDecorView().postDelayed(()->probe(attempt+1),200);
            }
        }
        LiquidGlassHostLayout glass(Activity a,ViewGroup row){
            // Personal build: NetEase always uses the deep, translucent glass
            // from the playlist/detail player. The stock theme probe sampled
            // native text colors and flipped the home page to a white surface.
            LiquidGlassHostLayout h=new LiquidGlassHostLayout(a,content,row);
            LiquidGlassPanel panel=new LiquidGlassPanel(a,content,d,true);
            h.addView(panel,new FrameLayout.LayoutParams(-1,-1));
            h.addView(row,new FrameLayout.LayoutParams(-1,-1));
            h.setGlassTuner(new LiquidGlassHostLayout.GlassTuner(){
                public void onSize(int w,int height,float r){panel.invalidate();}
                public void onTheme(boolean dark){panel.setTheme(true);}
            });return h;
        }
        void resolveAccent(Activity a){
            if(prefs.contains(SCOPE+"accent_color")){accent=prefs.getInt(SCOPE+"accent_color",0xffe60026)|0xff000000;return;}
            for(String name:new String[]{"themeColorPrimary","themeColor","primaryColor","colorAccent","colorPrimary","colorControlActivated"}){
                int id=a.getResources().getIdentifier(name,"attr",PKG);TypedValue value=new TypedValue();
                if(id!=0&&a.getTheme().resolveAttribute(id,value,true)){
                    int color=value.data;if(value.resourceId!=0)try{color=a.getResources().getColor(value.resourceId,a.getTheme());}catch(Exception ignored){}
                    float[] hsv=new float[3];Color.colorToHSV(color,hsv);
                    Log.i(TAG,"theme "+name+"="+Integer.toHexString(color));
                    if(Color.alpha(color)>200&&hsv[1]>.18f&&hsv[2]>.2f){accent=color|0xff000000;return;}
                }
            }
            accent=0xffe60026;
        }
        void playerRefs(){playerClick=find(content,"miniPlayBarReallyRoot");if(playerClick==null)playerClick=find(content,"minibarContentContainerNew");playButton=find(content,"minPlayBtn");listButton=find(content,"minPlaylistBtn");}
        void resume(){resumed=true;resumedAt=SystemClock.uptimeMillis();refreshNativeViews();if(overlay!=null){overlay.removeCallbacks(tick);overlay.post(tick);if(homePlayer!=null)homePlayer.invalidate();if(disc!=null)disc.invalidate();if(Math.abs(morph.value-morph.target)>.001)morph.to(morph.target);}}
        void refreshNativeViews(){
            if(content==null)return;
            View newMini=find(content,"minPlayerBarContainer");if(newMini!=null&&newMini!=mini){mini=newMini;playerRefs();}
            if(nativeBar!=null){View b=find(content,"bottomNav");if(b instanceof ViewGroup&&b!=nativeBar&&((ViewGroup)b).getChildCount()==tabs.length){nativeBar=(ViewGroup)b;for(int i=0;i<tabs.length;i++)tabs[i]=clickable(nativeBar.getChildAt(i));}}
        }
        void pause(){resumed=false;morph.stop();if(nav!=null)nav.spring.stop();if(overlay!=null)overlay.removeCallbacks(tick);}
        String songTitle(){View t=find(content,"tv_music");return t instanceof TextView?((TextView)t).getText().toString():"";}
        boolean isPlaying(){
            long now=SystemClock.uptimeMillis();if(now-playbackRead<250)return playing;playbackRead=now;
            Activity activityNow=activity.get();
            if(activityNow!=null)try{
                if(playbackMethod==null)playbackMethod=Class.forName("com.netease.cloudmusic.service.MainProcessPlayService",false,activityNow.getClassLoader()).getMethod("isRealPlaying");
                boolean next=(Boolean)playbackMethod.invoke(null);if(next!=playing)Log.i(TAG,"playback="+next);playing=next;return playing;
            }catch(Exception ignored){}
            if(playButton==null)return false;
            for(Class<?> c=playButton.getClass();c!=null;c=c.getSuperclass())try{java.lang.reflect.Field f=c.getDeclaredField("mIsPlaying");f.setAccessible(true);return f.getBoolean(playButton);}catch(Exception ignored){}
            try{Class<?> service=Class.forName("com.netease.cloudmusic.service.PlayService",false,playButton.getClass().getClassLoader());return (Boolean)service.getMethod("isPlaying").invoke(null);}catch(Exception ignored){}
            return playButton.isActivated()||playButton.isSelected();
        }
        void changeTrack(int direction){
            Activity a=activity.get();if(a==null)return;
            try {Class.forName("com.netease.cloudmusic.service.MainProcessPlayService",false,a.getClassLoader()).getMethod(direction>0?"playNextMusic":"playPreMusic").invoke(null);}
            catch(Exception error){AudioManager audio=(AudioManager)a.getSystemService(Context.AUDIO_SERVICE);int code=direction>0?KeyEvent.KEYCODE_MEDIA_NEXT:KeyEvent.KEYCODE_MEDIA_PREVIOUS;audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,code));audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,code));}
            Log.i(TAG,direction>0?"media next":"media previous");
            if(disc!=null){disc.animate().cancel();disc.animate().scaleX(.88f).scaleY(.88f).setDuration(90).withEndAction(()->disc.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(new android.view.animation.OvershootInterpolator(1.2f)).start()).start();}
            if(overlay!=null)overlay.postDelayed(()->{sync();if(disc!=null)disc.invalidate();},150);
        }
        void installDetail(Activity a,View m){
            mini=m;content=a.findViewById(android.R.id.content);playerRefs();resolveAccent(a);
            overlay=new FrameLayout(a);overlay.setTag("HC_KANADE_DETAIL");
            detail=new DetailView(a,this);FrameLayout row=new FrameLayout(a);row.addView(detail,new FrameLayout.LayoutParams(-1,-1));
            host=glass(a,row);overlay.addView(host,new FrameLayout.LayoutParams(-1,dp(58),Gravity.BOTTOM));
            ((ViewGroup)a.getWindow().getDecorView()).addView(overlay,new ViewGroup.LayoutParams(-1,-1));
            overlay.getViewTreeObserver().addOnGlobalLayoutListener(()->{if(SystemClock.uptimeMillis()-last<200)return;last=SystemClock.uptimeMillis();sync();});
            sync();host.attach();resume();Log.i(TAG,"Kanade detail player installed: "+a.getClass().getName());
        }
        void install(Activity a,ViewGroup bar){
            nativeBar=bar;content=a.findViewById(android.R.id.content);
            int n=bar.getChildCount();if(n>5)throw new IllegalStateException("unexpected tab count "+n);
            tabs=new View[n];String[] labels=new String[n];
            for(int i=0;i<n;i++){
                tabs[i]=clickable(bar.getChildAt(i));if(tabs[i]==null)throw new IllegalStateException("tab without native click "+i);
                CharSequence c=tabs[i].getContentDescription();TextView t=text(bar.getChildAt(i));
                labels[i]=c!=null&&c.length()>0?c.toString():t!=null?t.getText().toString():"关注";
            }
            mini=find(content,"minPlayerBarContainer");playerClick=find(content,"minibarContentContainerNew");
            playerRefs();resolveAccent(a);
            overlay=new FrameLayout(a);overlay.setClipChildren(false);overlay.setClipToPadding(false);
            overlay.setTag("HC_KANADE_CLOUDMUSIC");
            nav=new NavView(a,this,labels);
            FrameLayout row=new FrameLayout(a);row.addView(nav,new FrameLayout.LayoutParams(-1,-1));
            GlassConfig.blurRadius=prefs.getInt(SCOPE+"liquid_blur_radius",3);
            GlassConfig.pillScale=1f;GlassConfig.widthScale=1f;
            host=glass(a,row);host.setClipChildren(false);
            homePlayer=new DetailView(a,this);disc=homePlayer.album;FrameLayout discRow=new FrameLayout(a);
            discRow.addView(homePlayer,new FrameLayout.LayoutParams(-1,-1));
            discHost=glass(a,discRow);
            FrameLayout.LayoutParams navLp=new FrameLayout.LayoutParams(1,dp(58),Gravity.BOTTOM|Gravity.LEFT);
            FrameLayout.LayoutParams discLp=new FrameLayout.LayoutParams(dp(58),dp(58),Gravity.BOTTOM|Gravity.LEFT);
            overlay.addView(host,navLp);overlay.addView(discHost,discLp);
            ((ViewGroup)a.getWindow().getDecorView()).addView(overlay,new ViewGroup.LayoutParams(-1,-1));
            overlay.getViewTreeObserver().addOnGlobalLayoutListener(()->{
                if(updating||SystemClock.uptimeMillis()-last<180)return;
                updating=true;last=SystemClock.uptimeMillis();
                try { sync(); }catch(Throwable e){Log.e(TAG,"sync",e);}finally{updating=false;}
            });
            watchScroll(a);sync();host.attach();discHost.attach();resume();
            Log.i(TAG,"Kanade glass installed: tabs="+Arrays.toString(labels)+" native="+bar.getClass().getName()+" density="+d);
            for(View v=bar;v!=null&&v!=content;v=v.getParent() instanceof View?(View)v.getParent():null)
                Log.i(TAG,"ancestor "+id(v)+" "+v.getClass().getName()+" lp="+v.getLayoutParams().getClass().getSimpleName());
        }
        void watchScroll(Activity a){
            oldCallback=a.getWindow().getCallback();
            observedCallback=(Window.Callback)java.lang.reflect.Proxy.newProxyInstance(Window.Callback.class.getClassLoader(),new Class<?>[]{Window.Callback.class},(proxy,method,args)->{
                if(method.getName().equals("dispatchTouchEvent")&&args!=null&&args[0] instanceof MotionEvent){
                    MotionEvent e=(MotionEvent)args[0];
                    if(e.getActionMasked()==MotionEvent.ACTION_DOWN){gestureX=e.getX();gestureY=e.getY();observeScroll=overlay!=null&&gestureY<overlay.getHeight()-dp(180);}
                    else if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&observeScroll){float dx=e.getX()-gestureX,dy=e.getY()-gestureY;if(Math.abs(dy)>dp(24)&&Math.abs(dy)>Math.abs(dx)*1.3f){collapse(dy<0);observeScroll=false;}}
                    else if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL)observeScroll=false;
                }
                try{return method.invoke(oldCallback,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
            });a.getWindow().setCallback(observedCallback);
        }
        void collapse(boolean compact){double target=compact?1:0;if(morph.target==target)return;morph.to(target);Log.i(TAG,compact?"player spring -> compact":"player spring -> expanded");}
        void layoutHome(){
            Activity a=activity.get();if(a==null||overlay==null||nativeBar==null||host==null||discHost==null||overlay.getWidth()==0)return;
            WindowInsets wi=a.getWindow().getDecorView().getRootWindowInsets();int inset=wi==null?0:wi.getInsets(WindowInsets.Type.navigationBars()).bottom;
            int offset=prefs.getBoolean(SCOPE+"navigation_vertical_offset_enabled",false)?prefs.getInt(SCOPE+"navigation_vertical_offset_dp",0):0;
            int bottom=inset+dp(10+offset),margin=dp(16),gap=dp(10),size=dp(58),height=Math.round(dp(58)*scale("height"));
            float t=(float)Math.max(-.08,Math.min(1.08,morph.value));int full=overlay.getWidth()-2*margin;
            int navWidth=Math.max(dp(140),Math.round((full-(size+gap)*t)*scale("width")));
            navWidth=Math.min(navWidth,full+dp(6));
            FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)host.getLayoutParams();
            if(lp.width!=navWidth||lp.height!=height||lp.leftMargin!=margin||lp.bottomMargin!=bottom){lp.width=navWidth;lp.height=height;lp.leftMargin=margin;lp.bottomMargin=bottom;host.setLayoutParams(lp);}
            FrameLayout.LayoutParams player=(FrameLayout.LayoutParams)discHost.getLayoutParams();
            int pw=Math.max(dp(52),Math.round(full+(size-full)*t)),left=Math.round(margin+(full-size)*t),pb=Math.round(bottom+(height+gap)*(1-t));
            if(player.width!=pw||player.leftMargin!=left||player.bottomMargin!=pb){player.width=pw;player.height=size;player.leftMargin=left;player.bottomMargin=pb;player.rightMargin=0;player.gravity=Gravity.BOTTOM|Gravity.LEFT;discHost.setLayoutParams(player);}
            homePlayer.invalidate();
        }
        void sync(){
            Activity a=activity.get();if(a==null||overlay==null)return;
            refreshNativeViews();
            View decor=a.getWindow().getDecorView();
            if(nativeBar==null){syncDetail(a,decor);return;}
            int[] xy=new int[2];nativeBar.getLocationOnScreen(xy);
            // Native code owns visibility in subpages; keep the replacement out of search/player/detail pages.
            boolean home=hasVisibleAncestor(nativeBar) && nativeBar.getVisibility()!=View.GONE;
            // The native row is kept laid out and invisible, so its state and listeners remain live.
            overlay.setVisibility(home?View.VISIBLE:View.GONE);
            if(!home)return;
            nativeBar.setVisibility(View.VISIBLE);nativeBar.setAlpha(0f);nativeBar.setTranslationY(dp(4000));
            nativeBar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            hideMini();
            for(String name:new String[]{"bottomNavDividerLine","v4ShadowView","v4ShadowViewMask","shadowView"}){
                View v=find(content,name);if(v!=null&&v.getAlpha()!=0f)v.setAlpha(0f);
            }
            clearBottomPadding(content);
            layoutHome();
            int active=-1;for(int i=0;i<tabs.length;i++)if(selected(tabs[i])){active=i;break;}
            if(active>=0&&active!=nav.selected)nav.select(active,true);
            homePlayer.invalidate();nav.invalidate();
        }
        void hideMini(){if(mini!=null){mini.setVisibility(View.VISIBLE);mini.setAlpha(0f);mini.setTranslationY(dp(4000));mini.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);}}
        void syncDetail(Activity a,View decor){
            if(mini==null)return;boolean shown=hasVisibleAncestor(mini)&&mini.getVisibility()!=View.GONE;
            overlay.setVisibility(shown?View.VISIBLE:View.GONE);if(!shown)return;hideMini();
            int inset=0;WindowInsets wi=decor.getRootWindowInsets();if(wi!=null)inset=wi.getInsets(WindowInsets.Type.navigationBars()).bottom;
            FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)host.getLayoutParams();int b=inset+dp(10),m=dp(16);
            if(lp.bottomMargin!=b||lp.leftMargin!=m||lp.rightMargin!=m){lp.bottomMargin=b;lp.leftMargin=m;lp.rightMargin=m;host.setLayoutParams(lp);}
            detail.invalidate();
        }
        float scale(String dimension){if(!prefs.getBoolean(SCOPE+"navigation_"+dimension+"_scale_enabled",false))return 1f;return Math.max(.65f,Math.min(1.25f,1f+prefs.getInt(SCOPE+"navigation_"+dimension+"_scale_hundredths",0)/10000f));}
        boolean hasVisibleAncestor(View v){for(View x=v;x!=null;x=x.getParent() instanceof View?(View)x.getParent():null)if(x.getVisibility()!=View.VISIBLE && !(x==v&&x.getVisibility()==View.INVISIBLE))return false;return true;}
        void clearBottomPadding(View v){
            if(v.getClass().getSimpleName().equals("TopHeaderLayout")&&v.getPaddingBottom()!=0)v.setPadding(v.getPaddingLeft(),v.getPaddingTop(),v.getPaddingRight(),0);
            if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)clearBottomPadding(((ViewGroup)v).getChildAt(i));
        }
        void click(int i){if(i<0||i>=tabs.length)return;tabs[i].performClick();nav.select(i,true);collapse(false);Log.i(TAG,"native tab click "+i);}
        void openPlayer(){RouteMorph.open(this);}
        void openNativePlayer(){
            for(String name:new String[]{"miniPlayBarReallyRoot","miniDiskContainer","minibarContentContainerNew","minPlayerBar"}){
                View target=find(content,name);if(target==null)continue;
                if(target.callOnClick()||target.performClick()){Log.i(TAG,"native player click via "+name);return;}
                long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,target.getWidth()/2f,target.getHeight()/2f,0);MotionEvent up=MotionEvent.obtain(now,now+70,MotionEvent.ACTION_UP,target.getWidth()/2f,target.getHeight()/2f,0);
                boolean handled=target.dispatchTouchEvent(down);handled=target.dispatchTouchEvent(up)||handled;down.recycle();up.recycle();if(handled){Log.i(TAG,"native player touch via "+name);return;}
            }Log.w(TAG,"native player did not accept click");
        }
        void restore(){pause();Activity a=activity.get();if(a!=null&&a.getWindow().getCallback()==observedCallback)a.getWindow().setCallback(oldCallback);if(host!=null)host.detach();if(discHost!=null)discHost.detach();if(nativeBar!=null){nativeBar.setAlpha(1);nativeBar.setTranslationY(0);nativeBar.setVisibility(View.VISIBLE);}if(mini!=null){mini.setAlpha(1);mini.setTranslationY(0);mini.setVisibility(View.VISIBLE);}if(overlay!=null&&overlay.getParent() instanceof ViewGroup)((ViewGroup)overlay.getParent()).removeView(overlay);}
    }
    static final class NavView extends View {
        final Session s;final String[] labels;final Paint p=new Paint(3);final Path path=new Path();
        int selected=0;float position;final SpringValue spring;float downX,downY;boolean dragging;
        NavView(Context c,Session session,String[] names){super(c);s=session;labels=names;spring=new SpringValue(0,()->{position=(float)springValue();invalidate();});setClickable(true);setContentDescription("网易云悬浮导航：首页、搜索、关注、我的；可左右滑动切换");setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);}
        double springValue(){return spring==null?0:spring.value;}
        void select(int i,boolean animate){if(i==selected&&Math.abs(position-i)<.001f)return;selected=i;if(!animate)spring.snap(i);else spring.to(i);}
        protected void onDraw(Canvas c){super.onDraw(c);float d=s.d,w=getWidth(),h=getHeight(),cell=w/labels.length;boolean dark=true;
            float stretch=Math.min(7*d,(float)Math.abs(spring.velocity)*d*.55f),squash=stretch*.18f;
            p.setStyle(Paint.Style.FILL);p.setColor(dark?0x22ffffff:0x22000000);c.drawRoundRect(4*d+position*cell-stretch,4*d+squash,(position+1)*cell-4*d+stretch,h-4*d-squash,(h-8*d)/2,(h-8*d)/2,p);
            for(int i=0;i<labels.length;i++){
                float x=(i+.5f)*cell,y=h*.34f;int color=i==selected?s.accent:dark?Color.WHITE:0xff252a31;
                p.setColor(color);p.setStrokeWidth(1.7f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);p.setStyle(Paint.Style.STROKE);
                c.save();c.translate(x,y);c.scale(d,d);icon(c,i);c.restore();
                p.setStyle(Paint.Style.FILL);p.setColor(color);p.setTextAlign(Paint.Align.CENTER);p.setTypeface(i==selected?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);p.setTextSize(10.5f*d);
                c.drawText(labels[i],x,h*.81f,p);
            }
        }
        void icon(Canvas c,int i){path.reset();if(i==0){path.moveTo(0,-8);path.lineTo(9,-1);path.moveTo(-9,-1);path.lineTo(0,-8);path.moveTo(-7,-2);path.lineTo(-7,8);path.lineTo(-2,8);path.lineTo(-2,2);path.lineTo(2,2);path.lineTo(2,8);path.lineTo(7,8);path.lineTo(7,-2);c.drawPath(path,p);}else if(i==1){c.drawCircle(-2,-2,6.5f,p);c.drawLine(3,3,9,9,p);}else if(i==labels.length-1){c.drawCircle(0,-5,4.1f,p);c.drawRoundRect(-8,2,8,9,5,5,p);}else{c.drawCircle(-3,-3,3.5f,p);c.drawArc(-10,2,4,13,185,170,false,p);c.drawCircle(6,-1,2.5f,p);c.drawArc(1,3,12,12,200,140,false,p);}}
        public boolean onTouchEvent(MotionEvent e){float cell=getWidth()/(float)labels.length;
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){downX=e.getX();downY=e.getY();dragging=false;spring.stop();getParent().requestDisallowInterceptTouchEvent(true);return true;}
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE){if(Math.abs(e.getX()-downX)>s.dp(6))dragging=true;if(dragging){spring.snap(Math.max(0,Math.min(labels.length-1,e.getX()/cell-.5f)));}return true;}
            if(e.getActionMasked()==MotionEvent.ACTION_UP){int i=Math.max(0,Math.min(labels.length-1,(int)(e.getX()/cell)));s.click(i);performClick();getParent().requestDisallowInterceptTouchEvent(false);return true;}
            if(e.getActionMasked()==MotionEvent.ACTION_CANCEL){select(selected,true);return true;}return true;
        }
        public boolean performClick(){super.performClick();return true;}
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);}
        public boolean performAccessibilityAction(int action,android.os.Bundle args){if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD){s.click((selected+1)%labels.length);return true;}if(action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD){s.click((selected+labels.length-1)%labels.length);return true;}return super.performAccessibilityAction(action,args);}
    }
    static final class DiscView extends View {
        final Session s;final Paint p=new Paint(3);final Path clip=new Path();
        RenderNode artwork,oldArtwork;String title="";Object drawableKey;long changedAt,lastFrame,lastCapture;float rotation;
        float downX,downY;boolean swiped;
        DiscView(Context c,Session session){super(c);s=session;setContentDescription("正在播放，点按打开播放页，长按播放或暂停");setOnClickListener(v->s.openPlayer());setOnLongClickListener(v->{if(s.playButton!=null){s.playButton.performClick();return true;}return false;});}
        protected void onDraw(Canvas c){super.onDraw(c);float r=Math.min(getWidth(),getHeight())/2f;clip.reset();clip.addCircle(getWidth()/2f,getHeight()/2f,r,Path.Direction.CW);c.save();c.clipPath(clip);
            View v=find(s.content,"miniDiskContainer");
            long now=SystemClock.uptimeMillis();String song=s.songTitle();Object key=Long.valueOf(artworkKey(v));
            boolean changed=!song.equals(title);boolean playing=s.isPlaying();
            if(v!=null&&v.getWidth()>0&&v.getHeight()>0&&c.isHardwareAccelerated()){
                boolean nativeRefresh=now-lastCapture>400&&(now-s.resumedAt<10000||v.isDirty());
                if(artwork==null||changed||!key.equals(drawableKey)||nativeRefresh){
                    if(changed){oldArtwork=artwork;changedAt=now;title=song;Log.i(TAG,"artwork transition: "+(playing?"playing":"paused"));}
                    RenderNode next=new RenderNode("HC-Album");next.setPosition(0,0,getWidth(),getHeight());RecordingCanvas rc=next.beginRecording(getWidth(),getHeight());drawArtwork(rc,v,getWidth(),getHeight());next.endRecording();artwork=next;drawableKey=key;lastCapture=now;
                }
                if(playing&&s.resumed&&lastFrame>0)rotation=(rotation+Math.min(now-lastFrame,100)*.018f)%360f;
                lastFrame=now;float progress=Math.min(1,(now-changedAt)/420f);float eased=1-(float)Math.pow(1-progress,3);
                if(oldArtwork!=null&&progress<1){c.save();c.translate(-getWidth()*.22f*eased,0);int layer=c.saveLayerAlpha(0,0,getWidth(),getHeight(),(int)(255*(1-eased)));c.rotate(rotation,r,r);c.drawRenderNode(oldArtwork);c.restoreToCount(layer);c.restore();}
                c.save();c.translate(getWidth()*.22f*(1-eased),0);c.scale(.84f+.16f*eased,.84f+.16f*eased,r,r);int layer=c.saveLayerAlpha(0,0,getWidth(),getHeight(),(int)(255*eased));c.rotate(rotation,r,r);c.drawRenderNode(artwork);c.restoreToCount(layer);c.restore();
                if(progress>=1)oldArtwork=null;
                if(s.resumed&&getWindowVisibility()==VISIBLE&&(playing||progress<1))postInvalidateDelayed(32);
            }else if(v!=null&&v.getWidth()>0){c.save();drawArtwork(c,v,getWidth(),getHeight());c.restore();}else{p.setColor(0xff37454c);c.drawCircle(r,r,r,p);p.setColor(s.accent);p.setTextSize(r);p.setTextAlign(Paint.Align.CENTER);c.drawText("♪",r,r*1.35f,p);}
            c.restore();
        }
        void drawArtwork(Canvas c,View source,float width,float height){
            // The drawable is composed by MiniDiskContainer, not by its transparent image child.
            // Crop the native vinyl rim rather than replacing its working artwork renderer.
            float inner=.72f,sw=source.getWidth()*inner,sh=source.getHeight()*inner;
            c.scale(width/sw,height/sh);c.translate(-(source.getWidth()-sw)/2,-(source.getHeight()-sh)/2);source.draw(c);
        }
        long artworkKey(View v){
            if(v==null)return 0;long key=System.identityHashCode(v);
            if(v instanceof ImageView){Drawable d=((ImageView)v).getDrawable();key=key*31+System.identityHashCode(d);if(d!=null)key=key*31+System.identityHashCode(d.getCurrent());}
            if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)key=key*31+artworkKey(((ViewGroup)v).getChildAt(i));
            return key;
        }
        public boolean onTouchEvent(MotionEvent e){
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){downX=e.getX();downY=e.getY();swiped=false;getParent().requestDisallowInterceptTouchEvent(true);}
            float dx=e.getX()-downX,dy=e.getY()-downY;
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&!swiped&&Math.abs(dx)>s.dp(20)&&Math.abs(dx)>Math.abs(dy)*1.2f){swiped=true;MotionEvent cancel=MotionEvent.obtain(e);cancel.setAction(MotionEvent.ACTION_CANCEL);super.onTouchEvent(cancel);cancel.recycle();}
            if(e.getActionMasked()==MotionEvent.ACTION_UP&&swiped){s.changeTrack(dx<0?1:-1);performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);getParent().requestDisallowInterceptTouchEvent(false);return true;}
            if(swiped)return true;return super.onTouchEvent(e);
        }
    }
    static final class DetailView extends View {
        final Session s;final Paint p=new Paint(3);final Path clip=new Path();
        final DiscView album;float downX,downY;String title="",oldTitle="";long changedAt;
        final SpringValue press;final Runnable longPress;boolean held;
        DetailView(Context c,Session session){super(c);s=session;album=new DiscView(c,session);press=new SpringValue(1,()->{float k=(float)pressValue();setScaleX(k);setScaleY(k);});longPress=()->{held=true;if(s.playButton!=null)s.playButton.performClick();performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);};setClickable(true);setContentDescription("玻璃播放器，点按进入播放页，长按播放暂停；左右滑动切歌");}
        double pressValue(){return press==null?1:press.value;}
        protected void onSizeChanged(int w,int h,int oldW,int oldH){album.layout(0,0,s.dp(46),s.dp(46));}
        protected void onDraw(Canvas c){super.onDraw(c);float d=s.d,h=getHeight(),w=getWidth();
            c.save();c.translate(6*d,(h-46*d)/2);album.draw(c);c.restore();
            boolean playing=s.isPlaying();
            if(w<=140*d){if(s.resumed&&getWindowVisibility()==VISIBLE&&playing)postInvalidateDelayed(32);return;}
            int infoLayer=c.saveLayerAlpha(0,0,w,h,(int)(255*Math.min(1,(w/d-140)/70)));
            p.setColor(Color.WHITE);p.setTextSize(13*d);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextAlign(Paint.Align.LEFT);
            String next=s.songTitle();long now=SystemClock.uptimeMillis();if(!next.equals(title)){oldTitle=title;title=next;changedAt=now;}
            float t=Math.min(1,(now-changedAt)/360f),ease=1-(float)Math.pow(1-t,3);
            c.save();c.clipRect(59*d,0,w-92*d,h);if(t<1){p.setAlpha((int)(255*(1-ease)));c.drawText(oldTitle,59*d-14*d*ease,h*.57f,p);}p.setAlpha((int)(255*ease));c.drawText(title,59*d+14*d*(1-ease),h*.57f,p);p.setAlpha(255);c.restore();
            float x=w-67*d,y=h/2;
            if(playing){c.drawRoundRect(x-6*d,y-9*d,x-2*d,y+9*d,d,d,p);c.drawRoundRect(x+2*d,y-9*d,x+6*d,y+9*d,d,d,p);}else{Path triangle=new Path();triangle.moveTo(x-5*d,y-10*d);triangle.lineTo(x+9*d,y);triangle.lineTo(x-5*d,y+10*d);triangle.close();c.drawPath(triangle,p);}
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.7f*d);
            x=w-25*d;for(int i=-1;i<=1;i++)c.drawLine(x-8*d,y+i*6*d,x+8*d,y+i*6*d,p);p.setStyle(Paint.Style.FILL);
            c.restoreToCount(infoLayer);
            if(s.resumed&&getWindowVisibility()==VISIBLE&&(playing||t<1))postInvalidateDelayed(32);
        }
        public boolean onTouchEvent(MotionEvent e){
            int action=e.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){downX=e.getX();downY=e.getY();held=false;press.to(getWidth()<s.dp(140)?.90:.98);postDelayed(longPress,ViewConfiguration.getLongPressTimeout());getParent().requestDisallowInterceptTouchEvent(true);return true;}
            float x=e.getX(),dx=x-downX,dy=e.getY()-downY;
            if(action==MotionEvent.ACTION_MOVE&&Math.hypot(dx,dy)>s.dp(10))removeCallbacks(longPress);
            if(action==MotionEvent.ACTION_CANCEL){removeCallbacks(longPress);press.to(1);return true;}
            if(action==MotionEvent.ACTION_UP){removeCallbacks(longPress);press.to(1);if(held)return true;
                if(Math.abs(dx)>s.dp(25)&&Math.abs(dx)>Math.abs(dy)*1.2f){s.changeTrack(dx<0?1:-1);performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);}
                else if(Math.abs(dy)>s.dp(30))return true;
                else if(getWidth()<s.dp(140))s.openPlayer();
                else if(x>getWidth()-46*s.d&&s.listButton!=null)s.listButton.performClick();
                else if(x>getWidth()-89*s.d&&s.playButton!=null)s.playButton.performClick();
                else s.openPlayer();performClick();invalidate();return true;
            }return true;
        }
        protected void onDetachedFromWindow(){press.stop();removeCallbacks(longPress);super.onDetachedFromWindow();}
        public boolean performClick(){super.performClick();return true;}
    }
}
