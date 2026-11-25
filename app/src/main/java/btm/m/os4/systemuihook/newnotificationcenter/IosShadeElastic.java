// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook.newnotificationcenter;

import android.graphics.Rect;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/**
 * 通知中心列表顶部弹性拉伸与底部快捷按钮等比缩放回弹交互。
 * - 全程仅更新 GPU 硬件变换属性（translationY / scaleX / scaleY），绝不触发重绘卡顿；
 * - 通知下拉逼近快捷按钮时，按钮原地优雅等比缩小（最低 0.84x）；
 *   松手瞬间由欠阻尼果冻弹簧驱动按钮轻微过冲放大（~1.06x）后柔和回落至 1.0x。
 */
public final class IosShadeElastic implements Choreographer.FrameCallback {
    private static final IosShadeElastic INSTANCE = new IosShadeElastic();

    private static final float LIST_RESPONSE = 0.34f;
    private static final float LIST_DAMPING = 0.80f;
    private static final float SCALE_RESPONSE = 0.34f;
    private static final float SCALE_DAMPING = 0.56f;
    private static final float MAX_SHRINK = 0.16f;
    private static final float MAX_OVERSHOOT = 0.048f;

    /** 解析二阶欠阻尼果冻弹簧。 */
    private static final class Jelly {
        final float response;
        final float damping;
        float value;
        float velocity;
        float target;

        Jelly(float response, float damping) {
            this.response = response;
            this.damping = damping;
        }

        void step(float dt) {
            if (dt <= 0f) return;
            double freq = 2.0 * Math.PI / Math.max(0.12f, response);
            double damped = freq * Math.sqrt(Math.max(1e-6, 1.0 - damping * damping));
            double x = value - target;
            double decay = Math.exp(-damping * freq * dt);
            double a = x;
            double b = (velocity + damping * freq * x) / damped;
            double phase = damped * dt;
            double wave = a * Math.cos(phase) + b * Math.sin(phase);
            value = (float) (target + decay * wave);
            velocity = (float) (decay * (-damping * freq * wave
                    + damped * (-a * Math.sin(phase) + b * Math.cos(phase))));
            if (atRest()) {
                value = target;
                velocity = 0f;
            }
        }

        boolean atRest() {
            return Math.abs(value - target) < 0.0008f && Math.abs(velocity) < 0.01f;
        }

        void snap(float v) {
            value = v;
            target = v;
            velocity = 0f;
        }
    }

    private static final Jelly listSpring = new Jelly(LIST_RESPONSE, LIST_DAMPING);
    // scaleSpring 表示缩小量：>0 缩小，<0 过冲放大，0 为正常 1.0x
    private static final Jelly scaleSpring = new Jelly(SCALE_RESPONSE, SCALE_DAMPING);

    private static WeakReference<View> target = new WeakReference<>(null);
    private static final Rect bounds = new Rect();
    private static final int[] LOC = new int[2];
    private static Method ownScroll;
    private static Class<?> stackType;
    private static boolean open, candidate, dragging, posted;
    private static long lastFrameNanos;
    private static float downX, downY, anchorY, offset, base, applied, maxLimitPx;
    private static float cachedRestBottom = Float.NaN;
    private static float cachedShortcutTop = Float.NaN;

    private IosShadeElastic() {}

    /** 当前弹性拉伸下拉像素偏移（>= 0）。 */
    static float currentOffset() {
        return Math.max(0f, offset);
    }

    public static void beforeSync() {
        View v = target.get();
        if (v != null && applied != 0f && Math.abs(v.getTranslationY() - base - applied) < .5f) {
            v.setTranslationY(base);
        }
        applied = 0f;
    }

    public static void afterSync(View stack, float progress) {
        if (target.get() != stack) {
            reset();
            target = new WeakReference<>(stack);
            stackType = null;
            ownScroll = null;
        }
        open = stack != null && stack.isAttachedToWindow() && progress >= .999f
                && stack.getRootView().getWidth() < stack.getRootView().getHeight();
        if (!open) {
            reset();
            return;
        }
        maxLimitPx = 34f * stack.getResources().getDisplayMetrics().density;
        base = stack.getTranslationY();
        applyFrame();
    }

    private static void kick() {
        if (posted) return;
        posted = true;
        lastFrameNanos = 0L;
        Choreographer.getInstance().postFrameCallback(INSTANCE);
    }

    private static void stopChoreographer() {
        if (posted) {
            Choreographer.getInstance().removeFrameCallback(INSTANCE);
            posted = false;
        }
        lastFrameNanos = 0L;
    }

    private static boolean allAtRest() {
        return !dragging && listSpring.atRest() && scaleSpring.atRest();
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        posted = false;
        View v = target.get();
        if (!open || v == null || !v.isAttachedToWindow()) {
            reset();
            return;
        }
        float dt = lastFrameNanos == 0L ? (1f / 120f)
                : Math.min(0.05f, Math.max(0f, (frameTimeNanos - lastFrameNanos) / 1e9f));
        lastFrameNanos = frameTimeNanos;

        if (!dragging) {
            listSpring.target = 0f;
            listSpring.step(dt);
            offset = Math.max(0f, listSpring.value);
        }
        scaleSpring.step(dt);
        applyFrame();

        if (!allAtRest() && !posted) {
            posted = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    private static void applyFrame() {
        View v = target.get();
        if (v == null) return;
        v.setTranslationY(base + offset);
        applied = offset;
        updateShortcutScale(v);
    }

    private static void updateShortcutScale(View stack) {
        float density = stack.getResources().getDisplayMetrics().density;
        float limit = maxLimitPx > 0f ? maxLimitPx : 36f * density;
        // 仅当存在可见通知且通知底端处于快捷按钮相邻区（静止间距 <= 96dp）时才联动缩放
        boolean nearShortcuts = !Float.isNaN(cachedRestBottom) && !Float.isNaN(cachedShortcutTop)
                && (cachedShortcutTop - cachedRestBottom) <= 96f * density;

        if (dragging) {
            float t = nearShortcuts ? Math.max(0f, Math.min(1f, offset / limit)) : 0f;
            // 拖动阶段严格跟随下拉幅度一点点线性平滑缩小（拉多少缩多少，往回推同步放大回去，杜绝瞬缩）
            float shrink = MAX_SHRINK * t;
            scaleSpring.snap(shrink);
            IosNotificationShortcutHost.applyElasticScale(1f - shrink);
            return;
        }

        // 松手回弹阶段：由舒缓的欠阻尼果冻弹簧自然弹回 1.0x，带一丝轻微过冲放大
        scaleSpring.target = 0f;
        float clampedShrink = Math.max(-MAX_OVERSHOOT, Math.min(MAX_SHRINK, scaleSpring.value));
        IosNotificationShortcutHost.applyElasticScale(1f - clampedShrink);

        if (!allAtRest()) kick();
    }

    private static float ramp(float into, float soft) {
        if (into <= 0f) return 0f;
        if (soft <= 0f) return into;
        if (into < 2f * soft) return into * into / (4f * soft);
        return into - soft;
    }

    private static void captureRestGeometry(View stack) {
        cachedShortcutTop = IosNotificationShortcutHost.restingTopInWindow();
        if (!(stack instanceof ViewGroup)) {
            cachedRestBottom = Float.NaN;
            return;
        }
        ViewGroup group = (ViewGroup) stack;
        float maxBottom = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == null || child.getVisibility() != View.VISIBLE
                    || child.getAlpha() <= 0.05f || child.getHeight() <= 0) {
                continue;
            }
            String name = child.getClass().getName();
            if (!name.contains("Notification") && !name.contains("Media")) continue;
            child.getLocationInWindow(LOC);
            float childBottom = LOC[1] + child.getHeight() * child.getScaleY() - offset;
            if (childBottom > maxBottom) maxBottom = childBottom;
        }
        cachedRestBottom = maxBottom == Float.NEGATIVE_INFINITY ? Float.NaN : maxBottom;
    }

    private static boolean atTop(View stack) {
        try {
            if (stackType != stack.getClass()) {
                stackType = stack.getClass();
                ownScroll = stackType.getMethod("getOwnScrollY");
            }
            return ((Number) ownScroll.invoke(stack)).intValue() <= 1;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    public static void touch(MotionEvent event) {
        View v = target.get();
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            candidate = false;
            dragging = false;
            if (!open || v == null || !v.isShown() || !v.getGlobalVisibleRect(bounds)
                    || !bounds.contains((int) event.getRawX(), (int) event.getRawY())) return;
            downX = event.getRawX();
            downY = anchorY = event.getRawY();
            candidate = true;
            return;
        }
        if (!candidate) return;
        if (v == null || !open || event.getPointerCount() != 1 || action == MotionEvent.ACTION_CANCEL) {
            candidate = false;
            dragging = false;
            release();
            return;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
            int slop = ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
            if (!dragging) {
                if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                    candidate = false;
                    return;
                }
                if (dy <= slop || Math.abs(dy) <= Math.abs(dx)) return;
                if (!atTop(v)) {
                    anchorY = event.getRawY();
                    return;
                }
                dragging = true;
                anchorY = Math.max(anchorY, downY + slop);
                captureRestGeometry(v);
            }
            float distance = Math.max(0f, event.getRawY() - anchorY);
            float limit = maxLimitPx > 0f ? maxLimitPx : 36f * v.getResources().getDisplayMetrics().density;
            offset = limit * (1f - 1f / (1f + distance / (limit * 2.6f)));
            listSpring.snap(offset);
            applyFrame();
            return;
        }
        if (action == MotionEvent.ACTION_UP) {
            candidate = false;
            dragging = false;
            release();
        }
    }

    private static void release() {
        listSpring.target = 0f;
        if (offset < 0.5f) {
            offset = 0f;
            listSpring.snap(0f);
        }
        applyFrame();
        if (!allAtRest()) kick();
    }

    private static void reset() {
        stopChoreographer();
        candidate = false;
        dragging = false;
        beforeSync();
        offset = 0f;
        open = false;
        cachedRestBottom = Float.NaN;
        cachedShortcutTop = Float.NaN;
        listSpring.snap(0f);
        scaleSpring.snap(0f);
        IosNotificationShortcutHost.applyElasticScale(1f);
    }
}
