package btm.m.os4.systemuihook.hypermusiccover.next.island;

import android.view.View;
import android.view.ViewOutlineProvider;
import java.util.WeakHashMap;

/** State owned by the media card morph on the SystemUI main thread. */
public final class MediaCardMorphState {
    private static final ClassValue<Boolean> MEDIA_HEADER = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                if (current.getName().equals(
                        "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaHeaderView")) {
                    return true;
                }
            }
            return false;
        }
    };
    private static final WeakHashMap<View, MediaCardMorphState> ACTIVE = new WeakHashMap<>();

    private final ViewOutlineProvider morphOutline;
    private final boolean clipToOutline;
    private final float transitionAlpha;
    private final int visibility;
    private ViewOutlineProvider nativeOutline;
    private float screenTop = Float.NaN;

    private MediaCardMorphState(View header, ViewOutlineProvider morphOutline) {
        this.morphOutline = morphOutline;
        nativeOutline = header.getOutlineProvider();
        clipToOutline = header.getClipToOutline();
        transitionAlpha = header.getTransitionAlpha();
        visibility = header.getVisibility();
    }

    public static boolean isMediaHeader(View view) {
        return MEDIA_HEADER.get(view.getClass());
    }

    public static boolean begin(View header, ViewOutlineProvider outline) {
        if (!isMediaHeader(header)) return true;
        // A second animation must not save the first animation's shortened outline as native.
        if (ACTIVE.containsKey(header)) return false;
        ACTIVE.put(header, new MediaCardMorphState(header, outline));
        return true;
    }

    public static void applyOutline(View header, ViewOutlineProvider outline, float screenTop) {
        ViewOutlineProvider current = header.getOutlineProvider();
        MediaCardMorphState state = ACTIVE.get(header);
        if (state != null && state.morphOutline == outline) {
            state.screenTop = screenTop;
            if (current != outline) state.nativeOutline = current;
        }
        if (current != outline) header.setOutlineProvider(outline);
        if (!header.getClipToOutline()) header.setClipToOutline(true);
        header.invalidateOutline();
    }

    public static boolean restore(View header, ViewOutlineProvider outline) {
        MediaCardMorphState state = ACTIVE.get(header);
        if (state == null) return false;
        if (state.morphOutline != outline) return true;
        ACTIVE.remove(header);
        header.setAnimationMatrix(null);
        if (header.getOutlineProvider() == outline) header.setOutlineProvider(state.nativeOutline);
        header.setClipToOutline(state.clipToOutline);
        header.invalidateOutline();
        header.setTransitionAlpha(state.transitionAlpha);
        header.setVisibility(state.visibility);
        return true;
    }

    public static float screenTop(View root) {
        float top = Float.NaN;
        for (var entry : ACTIVE.entrySet()) {
            View header = entry.getKey();
            if (header == null || !header.isAttachedToWindow() || header.getRootView() != root) continue;
            float candidate = entry.getValue().screenTop;
            if (Float.isFinite(candidate) && (!Float.isFinite(top) || candidate < top)) top = candidate;
        }
        return top;
    }
}
