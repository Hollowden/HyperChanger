package btm.m.os4.systemuihook;

import android.view.View;
import java.lang.reflect.Method;

/** MIUI notification rows expose their stack-composited opacity separately from View.alpha. */
public final class LockscreenNotificationDrawVisibility {
    private static final ClassValue<Method> DRAW_ALPHA = new ClassValue<>() {
        @Override protected Method computeValue(Class<?> type) {
            try {
                return type.getMethod("getSuperAlpha");
            } catch (NoSuchMethodException | SecurityException ignored) {
                return null;
            }
        }
    };

    private LockscreenNotificationDrawVisibility() {}

    public static float alpha(View view) {
        float alpha = view.getAlpha();
        Method drawAlpha = DRAW_ALPHA.get(view.getClass());
        if (drawAlpha != null) {
            try {
                alpha = ((Number) drawAlpha.invoke(view)).floatValue();
            } catch (ReflectiveOperationException ignored) {
                // Other ROM versions still expose the ordinary View opacity.
            }
        }
        return alpha * view.getTransitionAlpha();
    }
}
