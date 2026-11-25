package btm.m.os4.systemuihook.newnotificationcenter;

import android.content.Context;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

import java.util.ArrayList;

import btm.m.os4.systemuihook.hypermusiccover.Xp;

/** Temporarily lends the actual plugin subtree to the unlocked notification center. */
public final class IosNotificationShortcutHost extends FrameLayout {
    private static final long LONG_PRESS_ARM_MS = 380L;
    private static final float PRESS_SCALE = 0.94f;
    private static final float ARMED_SCALE = 1.18f;
    private static volatile int gestureMode = 1;
    private static Object plugin;
    private static IosNotificationShortcutLaunch launch;
    private static View content;
    private static View left;
    private static View right;
    private static IosNotificationShortcutHost overlay;
    private static ViewGroup originalParent;
    private static ViewGroup.LayoutParams originalParams;
    private static int originalIndex;
    private static final ArrayList<VisualState> visibilityOverrides = new ArrayList<>();
    private static final int[] POINT = new int[2];
    private static final Rect BOUNDS = new Rect();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final OvershootInterpolator ARM_INTERPOLATOR = new OvershootInterpolator(1.6f);
    private static final DecelerateInterpolator RESTORE_INTERPOLATOR = new DecelerateInterpolator();

    private boolean touching;
    private boolean childrenCancelled;
    private boolean discardingTouch;
    private boolean pressedIsLeft;
    private boolean longPressArmed;
    private boolean longPressSuppressed;
    private int activeGestureMode;
    private View pressedView;
    private View pressedScaleTarget;
    private float downX, downY;
    private MotionEvent lastTouch;
    private final int touchSlop;
    private int lastBlur = -1;
    private final Runnable armLongPress = this::onLongPressArmed;

    public static boolean isHosting() {
        return overlay != null;
    }

    /** 返回当前可见快捷按钮（完整圆形玻璃容器）在窗口坐标系中的静止顶边 Y 与直径，未托管时返回 NaN。 */
    static float restingTopInWindow() {
        IosNotificationShortcutHost current = overlay;
        if (current == null || !current.isShown()) return Float.NaN;
        float top = Float.POSITIVE_INFINITY;
        top = Math.min(top, targetRestingTop(findScaleTarget(left)));
        top = Math.min(top, targetRestingTop(findScaleTarget(right)));
        if (top == Float.POSITIVE_INFINITY) return Float.NaN;
        return top - current.getTranslationY();
    }

    /** 返回单个快捷按钮玻璃圆盘的直径（像素），供果冻弹簧按比例计算位移与扁平量。 */
    static float shortcutDiameterPx() {
        View target = findScaleTarget(left);
        if (target == null || target.getHeight() <= 0) target = findScaleTarget(right);
        if (target != null && target.getHeight() > 0) return target.getHeight();
        IosNotificationShortcutHost current = overlay;
        return current != null ? 58f * current.getResources().getDisplayMetrics().density : 160f;
    }

    private static float targetRestingTop(View target) {
        if (target == null || !target.isShown() || target.getHeight() <= 0) return Float.POSITIVE_INFINITY;
        int[] loc = new int[2];
        target.getLocationInWindow(loc);
        // 还原自身水滴位移，确保接触判定的基准顶边稳定不抖动
        return loc[1] - target.getTranslationY();
    }

    /**
     * 通知弹性下拉靠近快捷按钮时的纯等比弹性缩放交互：
     * - 按钮原地不动（零位移、零压扁畸变、零重绘卡顿）；
     * - 被挤压时以圆形中心等比缩小（最低 0.84x），松手随果冻弹簧轻盈过冲放大（~1.06x）后柔和落回 1.0x。
     */
    static void applyElasticScale(float scale) {
        IosNotificationShortcutHost current = overlay;
        if (current == null || current.touching) return;
        applySideScale(left, scale);
        applySideScale(right, scale);
    }

    private static void applySideScale(View iconView, float scale) {
        View container = findScaleTarget(iconView);
        if (container == null) return;
        if (container.getTranslationY() != 0f) container.setTranslationY(0f);
        if (container.getWidth() > 0 && container.getHeight() > 0) {
            container.setPivotX(container.getWidth() * 0.5f);
            container.setPivotY(container.getHeight() * 0.5f);
        }
        if (container.getScaleX() != scale) container.setScaleX(scale);
        if (container.getScaleY() != scale) container.setScaleY(scale);

        // 保证子玻璃层与图标始终保持 1:1，不再叠加额外非等比缩放
        View glassChild = findGlassChild(container);
        if (glassChild != null) {
            if (glassChild.getScaleX() != 1f) glassChild.setScaleX(1f);
            if (glassChild.getScaleY() != 1f) glassChild.setScaleY(1f);
        }
        if (iconView != null && iconView != container && iconView != glassChild) {
            if (iconView.getScaleX() != 1f) iconView.setScaleX(1f);
            if (iconView.getScaleY() != 1f) iconView.setScaleY(1f);
        }
    }

    private static View findGlassChild(View container) {
        if (!(container instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) container;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (btm.m.os4.systemuihook.LockscreenShortcutGlassOwnership.TAG.equals(child.getTag())) {
                return child;
            }
        }
        return null;
    }

    public static void setGestureMode(int mode) {
        gestureMode = Math.max(0, Math.min(2, mode));
    }

    private IosNotificationShortcutHost(Context context) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    static void capture(Object controller) {
        try {
            Object currentPlugin = Xp.getObjectField(controller, "miuiShortcutPlugin");
            Object root = currentPlugin == null ? null : Xp.callMethod(currentPlugin, "getShortcutContentView");
            View currentContent = root instanceof View ? (View) root : null;
            if (plugin != currentPlugin || content != currentContent) clear();
            plugin = currentPlugin;
            content = currentContent;
            left = shortcut(controller, true);
            right = shortcut(controller, false);
            // A plugin can recreate its controllers without replacing its content root.
            if (launch != null) launch.cancel();
            launch = null;
            if (currentPlugin != null && currentContent != null) {
                launch = IosNotificationShortcutLaunch.create(controller, currentPlugin);
            }
            IosNotificationCenterPresentation.syncShortcuts();
        } catch (Throwable error) {
            clear();
            Xp.log("[IOSShade] native shortcut capture unavailable: " + error);
        }
    }

    private static View shortcut(Object controller, boolean isLeft) {
        Bundle args = new Bundle();
        args.putBoolean("isLeftShortcutView", isLeft);
        Object result = Xp.callMethod(controller, "onSystemUIAction$1", args, "getShortcutView");
        return result instanceof View ? (View) result : null;
    }

    static void clear() {
        restore();
        plugin = null;
        launch = null;
        content = null;
        left = null;
        right = null;
    }

    static void update(FrameLayout panel, View sheet, float progress, boolean enabled) {
        if (!enabled || panel == null || plugin == null || content == null || launch == null
                || (left == null && right == null)) {
            restore();
            return;
        }
        if (overlay != null && overlay.getParent() != panel) restore();
        if (overlay == null) {
            // Do not defeat the OEM's theme, provisioning, user or power-save restrictions.
            if (content.getVisibility() != View.VISIBLE
                    || !(content.getParent() instanceof ViewGroup)) return;
            originalParent = (ViewGroup) content.getParent();
            originalParams = content.getLayoutParams();
            originalIndex = originalParent.indexOfChild(content);
            IosNotificationShortcutHost created = new IosNotificationShortcutHost(panel.getContext());
            overlay = created;
            try {
                originalParent.removeView(content);
                created.addView(content, new FrameLayout.LayoutParams(-1, -1));
                panel.addView(created, new FrameLayout.LayoutParams(-1, -1));
                // The normal shortcut branch can be INVISIBLE after unlocking. Restore only
                // this presentation state, leaving absent/disabled icon visibility untouched.
                revealBranch(left);
                revealBranch(right);
                IosShadeMaterial.registerShortcutRoot(created);
            } catch (Throwable error) {
                restore();
                Xp.log("[IOSShade] native shortcut hosting unavailable: " + error);
                return;
            }
        }
        IosNotificationShortcutHost current = overlay;
        if (current == null) return;
        IosShadeMaterial.registerShortcutRoot(current);
        // 底部快捷按钮与前景组件同步透明度曲线，并移除每帧动态 RenderEffect 高斯模糊以稳定 120 FPS
        float baseAlpha = IosNotificationCenterPresentation.visualAlpha();
        float visual = baseAlpha * baseAlpha * (3f - 2f * baseAlpha);
        if (current.getAlpha() != visual) current.setAlpha(visual);
        if (current.lastBlur != 0) {
            current.setRenderEffect(null);
            current.lastBlur = 0;
        }
        float x = sheet == null ? 0f : sheet.getTranslationX();
        float y = sheet == null ? 0f : sheet.getTranslationY();
        if (current.getTranslationX() != x) current.setTranslationX(x);
        if (current.getTranslationY() != y) current.setTranslationY(y);
    }

    /** Space occupied by the lowest visible native shortcut, measured in panel coordinates. */
    static float bottomSpace(View panel) {
        if (panel == null || overlay == null || overlay.getParent() != panel
                || (left == null && right == null)) return 0f;
        int[] panelPoint = new int[2];
        panel.getLocationInWindow(panelPoint);
        float panelBottom = panelPoint[1] + panel.getHeight();
        float navigationInset = 0f;
        android.view.WindowInsets insets = panel.getRootWindowInsets();
        if (insets != null) {
            navigationInset = insets.getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.navigationBars()).bottom;
        }
        float safeBottom = panelBottom - navigationInset;
        // 必须扣除容器自身挤压平移量（使用静止顶边），彻底杜绝挤压位移反馈缩小 bottomSpace 引发的通知列表塌陷雪崩！
        float restingTop = restingTopInWindow();
        if (Float.isNaN(restingTop)) return 0f;
        // viewportBottom() already removes the navigation inset; reserve only the shortcut
        // area above that boundary so the system bottom space is not subtracted twice.
        return Math.max(0f, Math.min(panel.getHeight(), safeBottom - restingTop));
    }

    private static void revealBranch(View view) {
        if (view == null) return;
        for (View current = view; current != null; ) {
            // Keep GONE branches and the icon's own visibility under native control.
            if (current.getVisibility() == View.GONE) break;
            boolean recorded = false;
            for (VisualState state : visibilityOverrides) {
                if (state.view == current) { recorded = true; break; }
            }
            if (!recorded && (current.getAlpha() == 0f
                    || (current != view && current.getVisibility() == View.INVISIBLE))) {
                visibilityOverrides.add(new VisualState(current));
                current.setAlpha(1f);
                if (current != view) current.setVisibility(View.VISIBLE);
            }
            if (current == content) break;
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
    }

    static void restore() {
        IosNotificationShortcutHost old = overlay;
        if (old == null) return;
        // Clear ownership before callbacks; restoring native gestures may synchronously hide shade.
        overlay = null;
        applySideScale(left, 1f);
        applySideScale(right, 1f);
        old.cancelTouch();
        IosShadeMaterial.unregisterShortcutRoot(old);
        for (VisualState state : visibilityOverrides) state.restore();
        visibilityOverrides.clear();
        if (content != null && content.getParent() == old) {
            old.removeView(content);
            if (originalParent != null) {
                int index = Math.max(0, Math.min(originalIndex, originalParent.getChildCount()));
                originalParent.addView(content, index, originalParams);
            }
        }
        if (old.getParent() instanceof ViewGroup) ((ViewGroup) old.getParent()).removeView(old);
        originalParent = null;
        originalParams = null;
    }

    /** Called before native window dispatch so the header/stack cannot steal icon gestures. */
    static boolean dispatchWindowTouch(MotionEvent event) {
        IosNotificationShortcutHost current = overlay;
        if (current == null) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            current.cancelTouch();
            // Buttons become interactive at the resting endpoint, matching the native shade.
            boolean hitLeft = hit(left, event, 1f);
            boolean hitRight = !hitLeft && hit(right, event, 1f);
            if (current.getAlpha() < .999f || !current.isShown() || (!hitLeft && !hitRight)) {
                return false;
            }
            launch.begin();
            current.touching = true;
            current.discardingTouch = false;
            current.downX = event.getRawX();
            current.downY = event.getRawY();
            current.childrenCancelled = false;
            current.pressedIsLeft = hitLeft;
            current.pressedView = hitLeft ? left : right;
            current.pressedScaleTarget = findScaleTarget(current.pressedView);
            current.longPressArmed = false;
            current.longPressSuppressed = false;
            current.activeGestureMode = gestureMode;
            if (current.activeGestureMode != 0) {
                current.animateTargetScale(PRESS_SCALE, 100L, false);
                MAIN_HANDLER.removeCallbacks(current.armLongPress);
                MAIN_HANDLER.postDelayed(current.armLongPress, LONG_PRESS_ARM_MS);
            }
        }
        if (current.discardingTouch) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                current.discardingTouch = false;
            }
            return true;
        }
        if (!current.touching) return false;
        current.forward(event);
        return true;
    }

    private static View findScaleTarget(View view) {
        if (view == null) return null;
        View parent = view.getParent() instanceof View ? (View) view.getParent() : null;
        if (parent instanceof ViewGroup && parent != content && parent != overlay) {
            ViewGroup group = (ViewGroup) parent;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (btm.m.os4.systemuihook.LockscreenShortcutGlassOwnership.TAG.equals(
                        group.getChildAt(i).getTag())) {
                    return group;
                }
            }
        }
        return view;
    }

    private static boolean hit(View view, MotionEvent event, float radiusScale) {
        if (view == null || !view.isShown() || !view.isEnabled() || view.getAlpha() <= 0f
                || !view.getGlobalVisibleRect(BOUNDS)) return false;
        float x = event.getRawX(), y = event.getRawY();
        view.getLocationOnScreen(POINT);
        float dx = x - POINT[0] - view.getWidth() / 2f;
        float dy = y - POINT[1] - view.getHeight() / 2f;
        float radius = (Math.min(view.getWidth(), view.getHeight()) / 2f) * radiusScale;
        return dx * dx + dy * dy <= radius * radius;
    }

    private void onLongPressArmed() {
        if (overlay != this || !touching || longPressSuppressed || pressedView == null) return;
        longPressArmed = true;
        try {
            pressedView.performHapticFeedback(
                    HapticFeedbackConstants.LONG_PRESS,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        } catch (Throwable ignored) {}
        animateTargetScale(ARMED_SCALE, 150L, true);
        // 达到长按阈值后取消原生滑动接管，等待用户松手瞬间弹出快捷页面。
        if (!childrenCancelled && lastTouch != null) {
            dispatchChildren(lastTouch, MotionEvent.ACTION_CANCEL);
            childrenCancelled = true;
        }
        if (activeGestureMode == 1 && launch != null) {
            launch.cancel();
        }
    }

    private void animateTargetScale(float scale, long durationMs, boolean overshoot) {
        View target = pressedScaleTarget != null ? pressedScaleTarget : pressedView;
        if (target == null) return;
        target.animate().cancel();
        target.animate()
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(durationMs)
                .setInterpolator(overshoot ? ARM_INTERPOLATOR : RESTORE_INTERPOLATOR)
                .start();
    }

    private void restoreTargetScale() {
        View target = pressedScaleTarget != null ? pressedScaleTarget : pressedView;
        if (target == null) return;
        target.animate().cancel();
        target.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(140L)
                .setInterpolator(RESTORE_INTERPOLATOR)
                .start();
    }

    private void forward(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
            // The OEM treats CANCEL as UP and can launch an active shortcut. Reset instead.
            cancelTouch();
            discardingTouch = action == MotionEvent.ACTION_POINTER_DOWN;
            return;
        }
        if (lastTouch != null) lastTouch.recycle();
        lastTouch = MotionEvent.obtain(event);

        // 长按状态机（模式 1 = 滑动+长按，模式 2 = 仅长按）
        if (activeGestureMode != 0) {
            if (action == MotionEvent.ACTION_MOVE) {
                if (longPressArmed) {
                    if (!hit(pressedView, event, 1.45f)) {
                        // 划出按钮范围则取消待启动状态，符合 iOS 交互
                        longPressArmed = false;
                        longPressSuppressed = true;
                        restoreTargetScale();
                    }
                } else if (!longPressSuppressed) {
                    float dx = Math.abs(event.getRawX() - downX);
                    float dy = Math.abs(event.getRawY() - downY);
                    if (dx > touchSlop || dy > touchSlop) {
                        MAIN_HANDLER.removeCallbacks(armLongPress);
                        longPressSuppressed = true;
                        restoreTargetScale();
                    }
                }
            } else if (action == MotionEvent.ACTION_UP) {
                MAIN_HANDLER.removeCallbacks(armLongPress);
                boolean shouldLaunch = longPressArmed && hit(pressedView, event, 1.45f);
                boolean isLeft = pressedIsLeft;
                restoreTargetScale();
                if (shouldLaunch) {
                    finishTouch();
                    if (launch != null) launch.launchDirect(isLeft);
                    return;
                }
            }
            // 模式 2（仅长按）或已经进入长按就绪态时，不再把事件透传给原生滑动插件
            if (activeGestureMode == 2 || longPressArmed) {
                if (action == MotionEvent.ACTION_UP) finishTouch();
                return;
            }
        }

        MotionEvent nativeEvent = MotionEvent.obtain(event);
        // The plugin compares getX/Y with getLocationOnScreen, as on the lockscreen.
        nativeEvent.offsetLocation(event.getRawX() - event.getX(), event.getRawY() - event.getY());
        try {
            Xp.callMethod(plugin, "onTouchEvent", nativeEvent);
            if (overlay != this || !touching) return;
            if (!childrenCancelled && action == MotionEvent.ACTION_MOVE
                    && (Math.abs(event.getRawX() - downX) > touchSlop
                    || Math.abs(event.getRawY() - downY) > touchSlop)) {
                dispatchChildren(event, MotionEvent.ACTION_CANCEL);
                childrenCancelled = true;
            }
            if (!childrenCancelled) dispatchChildren(event, event.getAction());
            if (action == MotionEvent.ACTION_UP) {
                launch.released();
                finishTouch();
            }
        } catch (Throwable error) {
            cancelTouch();
            Xp.log("[IOSShade] native shortcut touch unavailable: " + error);
        } finally {
            nativeEvent.recycle();
        }
    }

    private void dispatchChildren(MotionEvent event, int action) {
        MotionEvent childEvent = MotionEvent.obtain(event);
        getLocationOnScreen(POINT);
        childEvent.offsetLocation(event.getRawX() - POINT[0] - event.getX(),
                event.getRawY() - POINT[1] - event.getY());
        childEvent.setAction(action);
        try { super.dispatchTouchEvent(childEvent); }
        finally { childEvent.recycle(); }
    }

    private void cancelTouch() {
        MotionEvent cancel = touching && lastTouch != null ? MotionEvent.obtain(lastTouch) : null;
        finishTouch();
        try {
            if (cancel != null) dispatchChildren(cancel, MotionEvent.ACTION_CANCEL);
        } catch (Throwable error) {
            Xp.log("[IOSShade] native shortcut cancellation unavailable: " + error);
        } finally {
            if (cancel != null) cancel.recycle();
            if (launch != null) launch.cancel();
        }
    }

    private void finishTouch() {
        MAIN_HANDLER.removeCallbacks(armLongPress);
        restoreTargetScale();
        touching = false;
        discardingTouch = false;
        longPressArmed = false;
        longPressSuppressed = false;
        pressedView = null;
        pressedScaleTarget = null;
        if (lastTouch != null) { lastTouch.recycle(); lastTouch = null; }
    }

    // Window dispatch owns shortcut streams. The overlay passes all other touches through.
    @Override public boolean dispatchTouchEvent(MotionEvent event) { return false; }

    private static final class VisualState {
        final View view;
        final int visibility;
        final float alpha;
        VisualState(View view) {
            this.view = view;
            visibility = view.getVisibility();
            alpha = view.getAlpha();
        }
        void restore() {
            // Preserve any newer native animation state.
            if (view.getVisibility() == View.VISIBLE) view.setVisibility(visibility);
            if (view.getAlpha() == 1f) view.setAlpha(alpha);
        }
    }
}
