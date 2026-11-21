package btm.m.os4.systemuihook;

import android.view.View;
import android.view.ViewGroup;
import java.util.WeakHashMap;

/** The capsule's animated disc supplies the background while it is attached to a shortcut. */
public final class LockscreenShortcutGlassOwnership {
    public static final String TAG = "hyperchanger.lockscreen.shortcut.glass";
    private static final WeakHashMap<ViewGroup, Boolean> CAPSULE_CONTAINERS = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> HIDDEN_BACKGROUNDS = new WeakHashMap<>();
    private static final WeakHashMap<ViewGroup, Boolean> BACKGROUND_ENABLED = new WeakHashMap<>();

    private LockscreenShortcutGlassOwnership() {}

    public static void setCapsuleDiscVisible(View button, boolean visible) {
        for (View view = button; view != null; ) {
            if (view instanceof ViewGroup group) {
                for (int i = 0; i < group.getChildCount(); i++) {
                    View child = group.getChildAt(i);
                    if (!TAG.equals(child.getTag())) continue;
                    if (visible) CAPSULE_CONTAINERS.put(group, true);
                    else CAPSULE_CONTAINERS.remove(group);
                    updateBackground(child, visible);
                    return;
                }
            }
            view = view.getParent() instanceof View parent ? parent : null;
        }
    }

    public static void onBackgroundAdded(ViewGroup container, View background) {
        if (CAPSULE_CONTAINERS.containsKey(container)) updateBackground(background, true);
    }

    public static void setBackgroundMode(ViewGroup container, int mode) {
        BACKGROUND_ENABLED.put(container, mode != 0);
    }

    public static void applyBackgroundVisibility(View button, View animatedBackground) {
        boolean enabled = false;
        for (View view = button; view != null; ) {
            Boolean value = BACKGROUND_ENABLED.get(view);
            if (value != null) {
                enabled = value;
                break;
            }
            view = view.getParent() instanceof View parent ? parent : null;
        }
        int visibility = enabled ? View.VISIBLE : View.INVISIBLE;
        if (animatedBackground.getVisibility() != visibility) animatedBackground.setVisibility(visibility);
    }

    private static void updateBackground(View background, boolean capsuleVisible) {
        if (capsuleVisible && background.getVisibility() == View.VISIBLE) {
            HIDDEN_BACKGROUNDS.put(background, true);
            background.setVisibility(View.INVISIBLE);
        } else if (!capsuleVisible && HIDDEN_BACKGROUNDS.remove(background) != null
                && background.getVisibility() == View.INVISIBLE) {
            background.setVisibility(View.VISIBLE);
        }
    }
}
