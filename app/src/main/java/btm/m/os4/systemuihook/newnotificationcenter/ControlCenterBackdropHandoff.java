package btm.m.os4.systemuihook.newnotificationcenter;

import android.util.Log;
import android.view.View;
import btm.m.os4.systemuihook.hypermusiccover.Xp;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/** Re-applies the OEM window blur once the notification presentation releases the shade. */
public final class ControlCenterBackdropHandoff {
    private static final String TAG = "HyperChangerShade";
    private static WeakReference<Object> controller = new WeakReference<>(null);
    private static boolean reportedFailure;

    private ControlCenterBackdropHandoff() {}

    public static void install(ClassLoader loader) {
        try {
            Class<?> type = Class.forName(
                    "com.miui.systemui.shade.blur.ShadeBlendBlurControllerImpl", false, loader);
            Xp.api().hook(type.getDeclaredMethod("start"))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        controller = new WeakReference<>(chain.getThisObject());
                        return result;
                    });
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.d(TAG, "Combined shade blur provider unavailable", error);
        }
    }

    public static void restore(View shadeRoot, boolean switching) {
        Object owner = controller.get();
        if (owner == null || shadeRoot == null || !shadeRoot.isAttachedToWindow()) return;
        try {
            if (Boolean.TRUE.equals(Xp.getObjectField(owner, "forcePassBlurEnabled"))) return;
            Object provider = Xp.getObjectField(owner, "combinedBlurProvider");
            if (provider == null) return;
            View backdrop = (View) Xp.getObjectField(provider, "view");
            if (backdrop == null || backdrop.getRootView() != shadeRoot.getRootView()
                    || !Boolean.TRUE.equals(Xp.getObjectField(provider, "enabled"))
                    || ((Number) Xp.getObjectField(provider, "blurRatio")).floatValue() <= 0f) return;

            // The provider caches these values and will skip the setters when the
            // two panels share the same expansion. Reapply them after shade cleanup.
            Method mode = View.class.getMethod("setMiBackgroundBlurMode", int.class);
            Method pass = View.class.getMethod("setPassWindowBlurEnabled", boolean.class);
            mode.invoke(backdrop, 1);
            pass.invoke(backdrop, Xp.getObjectField(provider, "passBlur"));
            Xp.callMethod(provider, "updateMaxRadius",
                    Xp.getObjectField(provider, "maxRadius"),
                    Xp.getObjectField(provider, "maxGlassSmallBlurRadius"),
                    Xp.getObjectField(provider, "maxGlassBigBlurRadius"));
            Log.d(TAG, switching ? "Restored native background at shade switch start"
                    : "Restored native background after notification-to-control-center switch");
        } catch (Throwable error) {
            if (!reportedFailure) {
                reportedFailure = true;
                Log.d(TAG, "Combined shade blur handoff unavailable", error);
            }
        }
    }
}
