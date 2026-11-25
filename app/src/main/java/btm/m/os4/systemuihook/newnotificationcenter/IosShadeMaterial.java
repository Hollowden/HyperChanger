package btm.m.os4.systemuihook.newnotificationcenter;

import btm.m.os4.systemuihook.hypermusiccover.Xp;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.content.res.Resources;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Redirect native glass to the wallpaper in this window without replacing its glass recipe. */
public final class IosShadeMaterial {
    private static final String[] SETTERS = {
            "setMiBackgroundBlurMode", "setMiBackgroundBlurType", "setMiBackgroundBlurRadius",
            "setPassWindowBlurEnabled", "disableMiBackgroundContainBelow"
    };
    private static final String[] GETTERS = {
            "getMiBackgroundBlurMode", "getMiBackgroundBlurType", "getMiBackgroundBlurRadius",
            "getPassWindowBlurEnabled", "getMiBackgroundContainBelow"
    };
    private static final Method[] METHODS = new Method[SETTERS.length];
    private static final Method[] READERS = new Method[SETTERS.length];
    // Enabling pass-window blur adds a visible texture entry in ViewRootImpl even if
    // the View's background mode is zero. Restore it BEFORE the local mode changes
    // back to zero, so that mode's setter also retires the texture entry. Reversing
    // these writes leaves a window-wide sampler alive after the sheet has closed.
    private static final int[] RESTORE_ORDER = {3, 0, 1, 2, 4};
    private static final WeakHashMap<View, Object[]> SAVED = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> CONTAIN_REQUESTS = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> MEDIA = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> GROUP_CONTROLS = new WeakHashMap<>();
    // Only class names are immutable. Parentage and explicit group/media registration
    // must still be checked live when notification views are rebound or reparented.
    private static final class GroupType {
        final boolean control, owner;
        GroupType(Class<?> type) {
            String name = type.getName().toLowerCase(java.util.Locale.ROOT);
            String simple = name.substring(name.lastIndexOf('.') + 1);
            control = simple.contains("expandbutton") || simple.contains("collapsebutton");
            owner = name.contains("expandablenotificationrow")
                    || name.contains("notificationchildrencontainer");
        }
    }
    private static final ClassValue<GroupType> GROUP_TYPES = new ClassValue<GroupType>() {
        @Override protected GroupType computeValue(Class<?> type) { return new GroupType(type); }
    };
    private static final WeakHashMap<Resources, HashMap<Integer, Boolean>> GROUP_RESOURCE_IDS =
            new WeakHashMap<>();
    /** 记录已扫描过通知组控制字段的对象，避免每次 onGlobalLayout 重复递归反射扫描。 */
    private static final WeakHashMap<Object, Boolean> SCANNED_OWNERS = new WeakHashMap<>();
    private static final WeakHashMap<View, int[]> GLASS_REQUESTS = new WeakHashMap<>();
    private static final WeakHashMap<View, int[]> SAVED_GLASS = new WeakHashMap<>();
    private static final WeakHashMap<View, android.graphics.drawable.Drawable> SAVED_SHORTCUT_BG =
            new WeakHashMap<>();
    private static Method glassRadius;
    private static Method setScaleRatio;
    private static int glassSmallRadius;
    private static int glassBigRadius;
    private static final ThreadLocal<Boolean> WRITING = new ThreadLocal<>();
    private static boolean active;
    private static ViewGroup root;
    private static View clockRoot;
    private static boolean clockRegistered;
    private static View shortcutRoot;
    private static boolean shortcutRegistered;
    private static final String SHORTCUT_GLASS_TAG =
            btm.m.os4.systemuihook.LockscreenShortcutGlassOwnership.TAG;
    private static ViewTreeObserver observer;
    private static boolean loggedFailure;
    private static long lastLayoutScanUptime;
    private static final ViewTreeObserver.OnGlobalLayoutListener LAYOUT = () -> {
        if (!active || root == null) return;
        // 仅保留 60ms 轻量防抖：既避免单帧内多次布局触发全树扫描，又不在下拉过程屏蔽通知材质捕获
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastLayoutScanUptime < 60L) return;
        lastLayoutScanUptime = now;
        scan(root);
    };

    public static void install() {
        try {
            glassRadius = View.class.getDeclaredMethod("setMiGlassBlurRadius", int.class, int.class);
            glassRadius.setAccessible(true);
            Xp.api().hook(glassRadius)
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        View view = (View) chain.getThisObject();
                        if (Boolean.TRUE.equals(WRITING.get()) || isPanel(view) || !target(view)) {
                            return chain.proceed();
                        }
                        int[] requested = {(Integer) chain.getArgs().get(0), (Integer) chain.getArgs().get(1)};
                        GLASS_REQUESTS.put(view, requested);
                        if (!active || view.getRootView() != root) return chain.proceed();
                        SAVED_GLASS.put(view, requested);
                        return chain.proceed(new Object[]{
                                requested[0] > 0 ? requested[0] : glassSmallRadius,
                                requested[1] > 0 ? requested[1] : glassBigRadius});
                    });
        } catch (Throwable error) {
            Xp.log("[IOSShade] glass sampling radius unavailable: " + error);
        }
        for (int index = 0; index < SETTERS.length; index++) {
            final int slot = index;
            try {
                Class<?> argument = slot >= 3 ? boolean.class : int.class;
                Method setter = View.class.getDeclaredMethod(SETTERS[slot], argument);
                setter.setAccessible(true);
                METHODS[slot] = setter;
                try {
                    READERS[slot] = View.class.getDeclaredMethod(GETTERS[slot]);
                    READERS[slot].setAccessible(true);
                } catch (Throwable ignored) {
                    // Only override a property after its native value is known. A missing
                    // getter must not leave an unrestorable material on the shared window.
                }
                Xp.api().hook(setter)
                        .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            View view = (View) chain.getThisObject();
                            if (Boolean.TRUE.equals(WRITING.get())) return chain.proceed();
                            // This full-screen provider is also used by heads-up and theme
                            // transitions. Keep its mode/type/radius/pass recipe native; a
                            // row-local fallback (notably radius 0 -> 80) can cover the whole
                            // window. Only its wallpaper containment belongs to this layer.
                            if (isPanel(view) && slot != 4) return chain.proceed();
                            Object requested = chain.getArgs().get(0);
                            if (slot == 4 && !isContainmentSurface(view)) return chain.proceed();
                            if (slot == 4 && isContainmentSurface(view)) {
                                CONTAIN_REQUESTS.put(view, (Boolean) requested);
                            }
                            if (!active || (!target(view) && !(slot == 4 && (isClock(view) || isInShortcutRoot(view))))
                                    || view.getRootView() != root) return chain.proceed();
                            Object[] saved = remember(view);
                            saved[slot] = requested;
                            return chain.proceed(new Object[]{wanted(view, slot, saved)});
                        });
            } catch (Throwable error) {
                Xp.log("[IOSShade] material API unavailable: " + SETTERS[slot] + ": " + error);
            }
        }
        try {
            setScaleRatio = View.class.getDeclaredMethod("setMiBackgroundBlurScaleRatio", float.class);
            setScaleRatio.setAccessible(true);
        } catch (Throwable ignored) {}
        // New or rebound rows need their local blur even after the opening edge has passed.
        try {
            Method setViewBlur = View.class.getDeclaredMethod("setMiViewBlurMode", int.class);
            Xp.api().hook(setViewBlur)
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        View view = (View) chain.getThisObject();
                        if (active && !Boolean.TRUE.equals(WRITING.get()) && !SAVED.containsKey(view) && target(view)
                                && view.getRootView() == root) apply(view);
                        return result;
                    });
        } catch (Throwable error) {
            Xp.log("[IOSShade] row material refresh hook unavailable: " + error);
        }
    }

    public static void setActive(boolean enabled, ViewGroup window) {
        if (enabled == active && window == root) return;
        restore();
        root = window;
        active = enabled && window != null;
        if (!active) return;
        glassSmallRadius = dimension(window, "notification_glass_small_blur_max_radius", 36);
        glassBigRadius = dimension(window, "notification_glass_big_blur_max_radius", 500);
        observer = window.getViewTreeObserver();
        observer.addOnGlobalLayoutListener(LAYOUT);
        scan(window);
    }

    public static void registerClock(View view) {
        if (view == clockRoot && clockRegistered) return;
        clockRoot = view;
        if (active && view != null) {
            scanClock(view);
            clockRegistered = true;
        }
    }

    /** 将借用到通知中心的快捷按钮子树纳入本地壁纸采样，清除锁屏态的 setMiBlurWinType(1) 跨窗穿透。 */
    public static void registerShortcutRoot(View view) {
        if (view == null) return;
        if (view == shortcutRoot && shortcutRegistered && active) return;
        shortcutRoot = view;
        if (active) {
            scanShortcuts(view);
            shortcutRegistered = true;
        }
    }

    /** 快捷按钮归还给锁屏前，还原其原有模糊属性，避免影响锁屏壁纸渲染。 */
    public static void unregisterShortcutRoot(View view) {
        if (view == null || (shortcutRoot != null && shortcutRoot != view)) return;
        View targetRoot = shortcutRoot != null ? shortcutRoot : view;
        shortcutRoot = null;
        shortcutRegistered = false;
        WRITING.set(true);
        try {
            for (Map.Entry<View, int[]> entry : new ArrayList<>(SAVED_GLASS.entrySet())) {
                View candidate = entry.getKey();
                if (candidate == null || !isDescendantOf(candidate, targetRoot)) continue;
                SAVED_GLASS.remove(candidate);
                if (glassRadius == null) continue;
                try {
                    int[] requested = entry.getValue();
                    glassRadius.invoke(candidate, requested[0], requested[1]);
                } catch (Throwable ignored) {}
            }
            for (Map.Entry<View, Object[]> entry : new ArrayList<>(SAVED.entrySet())) {
                View candidate = entry.getKey();
                if (candidate == null || !isDescendantOf(candidate, targetRoot)) continue;
                Object[] values = entry.getValue();
                SAVED.remove(candidate);
                for (int slot : RESTORE_ORDER) {
                    if (METHODS[slot] == null || values[slot] == null) continue;
                    try {
                        METHODS[slot].invoke(candidate, values[slot]);
                    } catch (Throwable ignored) {}
                }
            }
            for (Map.Entry<View, android.graphics.drawable.Drawable> entry : new ArrayList<>(SAVED_SHORTCUT_BG.entrySet())) {
                View candidate = entry.getKey();
                if (candidate == null || !isDescendantOf(candidate, targetRoot)) continue;
                SAVED_SHORTCUT_BG.remove(candidate);
                try {
                    candidate.setBackground(entry.getValue());
                } catch (Throwable ignored) {}
            }
        } finally {
            WRITING.remove();
        }
    }

    private static boolean isDescendantOf(View view, View ancestor) {
        if (ancestor == null) return false;
        for (View cursor = view; cursor != null; ) {
            if (cursor == ancestor) return true;
            cursor = cursor.getParent() instanceof View ? (View) cursor.getParent() : null;
        }
        return false;
    }

    private static boolean isInShortcutRoot(View view) {
        return isDescendantOf(view, shortcutRoot);
    }

    /** 快捷按钮的真正玻璃渲染层（带 SHORTCUT_GLASS_TAG 的子 View）需要自带窗口内本地采样器。 */
    private static boolean isShortcutGlassLayer(View view) {
        return view != null && isInShortcutRoot(view) && SHORTCUT_GLASS_TAG.equals(view.getTag());
    }

    private static boolean hasShortcutGlassChild(View view) {
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (SHORTCUT_GLASS_TAG.equals(group.getChildAt(i).getTag())) return true;
        }
        return false;
    }

    private static boolean isShortcutProvider(View view) {
        if (view == null || !isInShortcutRoot(view)) return false;
        if (SHORTCUT_GLASS_TAG.equals(view.getTag())) return true;
        if (hasShortcutGlassChild(view)) return true;
        try {
            if (READERS[0] != null) {
                Object mode = READERS[0].invoke(view);
                if (mode instanceof Number && ((Number) mode).intValue() != 0) return true;
            }
            if (READERS[3] != null && Boolean.TRUE.equals(READERS[3].invoke(view))) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void scanShortcuts(View view) {
        if (view == null) return;
        if (isShortcutProvider(view)) {
            if (!SAVED.containsKey(view)) apply(view);
        } else if (METHODS[4] != null && !SAVED.containsKey(view)) {
            Object[] saved = new Object[SETTERS.length];
            try {
                if (READERS[4] != null) saved[4] = !(Boolean) READERS[4].invoke(view);
                if (saved[4] != null) SAVED.put(view, saved);
                WRITING.set(true);
                METHODS[4].invoke(view, false);
            } catch (Throwable ignored) {
            } finally {
                WRITING.remove();
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) scanShortcuts(group.getChildAt(i));
        }
    }

    private static int dimension(View view, String name, int fallback) {
        try {
            int id = view.getResources().getIdentifier(name, "dimen", "com.android.systemui");
            return id == 0 ? fallback : view.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static boolean isClock(View view) {
        View clock = clockRoot;
        for (View cursor = view; cursor != null; ) {
            if (cursor == clock) return clock != null;
            cursor = cursor.getParent() instanceof View ? (View) cursor.getParent() : null;
        }
        return false;
    }

    private static void scanClock(View view) {
        if (METHODS[4] != null && !SAVED.containsKey(view)) {
            Object[] saved = new Object[SETTERS.length];
            try {
                if (READERS[4] != null) saved[4] = !(Boolean) READERS[4].invoke(view);
                if (saved[4] != null) {
                    SAVED.put(view, saved);
                }
                // The hosted clock is an explicitly owned sampling surface. Some HyperOS
                // builds expose the setter but no readable containment getter; still reapply
                // the wallpaper boundary on a rapid reopen, where the existing clock is reused
                // and create() does not get a chance to call allowBackdropSampling().
                WRITING.set(true);
                METHODS[4].invoke(view, false);
            } catch (Throwable ignored) {
            } finally {
                WRITING.remove();
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) scanClock(group.getChildAt(i));
        }
    }

    private static boolean isPanel(View view) {
        return view.getClass().getName().equals("com.android.systemui.shade.NotificationPanelView");
    }

    private static boolean isDismissView(View view) {
        // The clear-all control is a CircleAndTickAnimView. Its native material keeps the
        // notification surface as the sampling boundary, which is wrong over the iOS sheet.
        return view.getClass().getName().equals("com.miui.systemui.widget.CircleAndTickAnimView");
    }

    /**
     * The OEM's grouped-notification control is a separate NotificationExpandButton. It is
     * created/rebound when a group opens, so it cannot be treated as part of the row's native
     * material. Its containment boundary must follow the same wallpaper sibling as the sheet.
     */
    private static boolean isGroupCollapseView(View view) {
        if (GROUP_CONTROLS.containsKey(view)) return true;
        if (GROUP_TYPES.get(view.getClass()).control) return true;
        for (Object parent = view.getParent(); parent instanceof View;
                parent = ((View) parent).getParent()) {
            if (GROUP_TYPES.get(parent.getClass()).control) return true;
        }
        try {
            int id = view.getId();
            if (id > 0) {
                Resources resources = view.getResources();
                HashMap<Integer, Boolean> ids = GROUP_RESOURCE_IDS.get(resources);
                Boolean cached = ids == null ? null : ids.get(id);
                if (cached != null) return cached;
                String entry = resources.getResourceEntryName(id).toLowerCase(java.util.Locale.ROOT);
                boolean control = entry.contains("collapse_button") || entry.contains("group_collapse")
                        || ((entry.contains("expand") || entry.contains("collapse"))
                        && (entry.contains("notification") || entry.contains("group")));
                if (ids == null) {
                    ids = new HashMap<>();
                    GROUP_RESOURCE_IDS.put(resources, ids);
                }
                ids.put(id, control);
                return control;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isWallpaperSurface(View view) {
        return isPanel(view) || isDismissView(view) || isGroupCollapseView(view)
                || isClock(view) || isInShortcutRoot(view);
    }

    private static boolean isContainmentSurface(View view) {
        return isWallpaperSurface(view);
    }

    private static boolean target(View view) {
        String name = view.getClass().getName();
        return isPanel(view) || name.endsWith(".NotificationBackgroundView")
                || name.equals("com.miui.systemui.widget.CircleAndTickAnimView")
                || isGroupCollapseView(view) || MEDIA.containsKey(view)
                || isShortcutProvider(view);
    }

    public static void registerMedia(View header) {
        try {
            Object holder = Xp.callMethod(header, "getMediaViewHolder");
            if (holder == null) return;
            Object background = Xp.getObjectField(holder, "mediaBg");
            if (!(background instanceof View)) return;
            View view = (View) background;
            MEDIA.put(view, true);
            if (active && view.getRootView() == root && !SAVED.containsKey(view)) apply(view);
        } catch (Throwable ignored) {
        }
    }

    private static void registerGroupControl(View view) {
        if (view == null) return;
        GROUP_CONTROLS.put(view, true);
        if (active && view.getRootView() == root && !SAVED.containsKey(view)) apply(view);
    }

    private static final String[] GROUP_CONTROL_FIELDS = {
            "mExpandButton", "mGroupExpandButton", "mCollapseButton", "mCollapsedButton",
            "mChildrenContainer", "mNotificationChildrenContainer", "mGroupHeader",
            "mMiuiGroupHeader", "mGroupHeaderWrapper", "expandButton", "collapseButton",
            "mInjector"
    };

    private static void captureGroupFields(Object owner, int depth) {
        if (owner == null || depth < 0) return;
        // 已扫描过的对象直接跳过，并用零异常 tryGetObjectField 彻底消除 12 字段枚举产生的海量异常栈
        if (SCANNED_OWNERS.containsKey(owner)) return;
        SCANNED_OWNERS.put(owner, Boolean.TRUE);
        for (String field : GROUP_CONTROL_FIELDS) {
            Object candidate = Xp.tryGetObjectField(owner, field);
            if (candidate instanceof View) {
                registerGroupControl((View) candidate);
            } else if (candidate != null && depth > 0) {
                captureGroupFields(candidate, depth - 1);
            }
        }
    }

    /** 供下拉起拉首帧与拖动期间局部扫描通知栈，确保通知卡片第一时间采样通知壁纸而非底层桌面。 */
    static void scanSubtree(View view) {
        if (active && view != null) {
            scan(view);
        }
    }

    private static void captureGroupControls(View owner) {
        if (!GROUP_TYPES.get(owner.getClass()).owner) return;
        // The MIUI group header keeps the actual collapse ImageView inside its injector.
        // Walk only the named notification-group fields, with a bounded depth, so ordinary
        // child notification rows are never reclassified as wallpaper surfaces.
        captureGroupFields(owner, 2);
    }

    private static void scan(View view) {
        captureGroupControls(view);
        if (view == clockRoot) {
            scanClock(view);
            return;
        }
        if (view == shortcutRoot) {
            scanShortcuts(view);
            return;
        }
        if (view.getClass().getName().equals(
                "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaHeaderView")) registerMedia(view);
        if (target(view) && view.isShown() && !SAVED.containsKey(view)) apply(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) scan(group.getChildAt(i));
        }
    }

    private static Object[] remember(View view) {
        Object[] saved = SAVED.get(view);
        if (saved != null) return saved;
        saved = new Object[SETTERS.length];
        for (int slot = 0; slot < READERS.length; slot++) {
            // Do not snapshot/replay full-screen properties we do not own. The OEM can
            // change them while the sheet is active without repeating every setter.
            if (isPanel(view) && slot != 4) continue;
            try {
                if (READERS[slot] != null) saved[slot] = READERS[slot].invoke(view);
            } catch (Throwable ignored) {
            }
        }
        // The getter reports containment; the setter is the inverse (disable containment).
        if (saved[4] instanceof Boolean) saved[4] = !(Boolean) saved[4];
        else saved[4] = null;
        if (CONTAIN_REQUESTS.containsKey(view)) saved[4] = CONTAIN_REQUESTS.get(view);
        SAVED.put(view, saved);
        return saved;
    }

    private static Object wanted(View view, int slot, Object[] saved) {
        switch (slot) {
            case 0: return (!isShortcutGlassLayer(view) && hasShortcutGlassChild(view)) ? 0 : 1;
            // 快捷按钮玻璃层与「× 清除」按钮一致：使用 type=0 + pass=true + mode=1 采样同窗通知壁纸；
            // 避免 type=1 + pass=false 导致采样为空（纯灰）。
            case 1: return isShortcutGlassLayer(view) ? 0 : 1;
            case 2:
                int radius = saved[2] instanceof Number ? ((Number) saved[2]).intValue() : 0;
                // The panel reports no native radius of its own, so honouring that value verbatim
                // left the iOS shade with refraction only: a transparent glass with no blur at all.
                // Fall back to the row radius whenever the native value is missing.
                return radius > 0 ? radius : 80;
            case 3: return isShortcutGlassLayer(view); // 玻璃层与「× 清除」按钮保持一致，父容器关 pass
            case 4: return false; // Include the wallpaper sibling below NotificationPanelView.
            default: throw new IllegalArgumentException();
        }
    }

    private static void apply(View view) {
        Object[] saved = remember(view);
        boolean shortcutProvider = isShortcutProvider(view);
        WRITING.set(true);
        try {
            if (shortcutProvider) {
                boolean isGlassChild = isShortcutGlassLayer(view);
                boolean isGlassParent = !isGlassChild && hasShortcutGlassChild(view);
                if (isGlassParent) {
                    if (METHODS[3] != null) METHODS[3].invoke(view, false);
                    if (METHODS[0] != null) METHODS[0].invoke(view, 0);
                    if (METHODS[4] != null) METHODS[4].invoke(view, false);
                } else {
                    // 1:1 复刻通知中心右上角「× 清除」按钮已验证可采样通知中心壁纸的材质管线：
                    // 1) 确保 View 拥有真实 background Drawable（1-alpha 透明底）而非仅 imageDrawable；
                    // 2) setPassWindowBlurEnabled(true) + setMiBackgroundBlurMode(1) + setMiBackgroundBlurType(0)
                    //    + setMiBackgroundBlurScaleRatio(0f) + disableMiBackgroundContainBelow(false)。
                    if (isGlassChild && !SAVED_SHORTCUT_BG.containsKey(view)) {
                        SAVED_SHORTCUT_BG.put(view, view.getBackground());
                        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                        bg.setColor(android.graphics.Color.argb(1, 255, 255, 255));
                        view.setBackground(bg);
                    }
                    if (METHODS[1] != null) METHODS[1].invoke(view, 0);
                    if (METHODS[3] != null) METHODS[3].invoke(view, true);
                    if (METHODS[0] != null) METHODS[0].invoke(view, 1);
                    if (METHODS[2] != null) METHODS[2].invoke(view, 80);
                    if (METHODS[4] != null) METHODS[4].invoke(view, false);
                    if (setScaleRatio != null) setScaleRatio.invoke(view, 0f);
                }
                view.invalidate();
            } else {
                for (int slot = 0; slot < METHODS.length; slot++) {
                    // The panel, clear-all, group controls and hosted shortcuts sample the wallpaper
                    // sibling below the notification surface. Notification rows keep native containment.
                    if (slot == 4) {
                        if (!(isPanel(view) || isDismissView(view) || isGroupCollapseView(view) || isInShortcutRoot(view))) {
                            continue;
                        }
                        if (saved[4] == null && !isInShortcutRoot(view)) continue;
                    } else if (saved[slot] == null) {
                        continue;
                    }
                    if (METHODS[slot] != null) METHODS[slot].invoke(view, wanted(view, slot, saved));
                }
            }
            if (!isPanel(view) && glassRadius != null) {
                // Native consumers inherit these radii from NotificationPanelView. Once a
                // consumer owns a local provider it also needs both glass sampling textures;
                // background radius alone only supplies the ordinary blur layer.
                int[] requested = GLASS_REQUESTS.get(view);
                if (requested == null) requested = new int[]{0, 0};
                SAVED_GLASS.put(view, requested);
                glassRadius.invoke(view, requested[0] > 0 ? requested[0] : glassSmallRadius,
                        requested[1] > 0 ? requested[1] : glassBigRadius);
            }
        } catch (Throwable error) {
            if (!loggedFailure) {
                loggedFailure = true;
                Xp.log("[IOSShade] local material failed: " + error);
            }
        } finally {
            WRITING.remove();
        }
    }

    private static void restore() {
        active = false;
        clockRoot = null;
        clockRegistered = false;
        shortcutRoot = null;
        shortcutRegistered = false;
        if (observer != null && observer.isAlive()) observer.removeOnGlobalLayoutListener(LAYOUT);
        observer = null;
        WRITING.set(true);
        try {
            for (Map.Entry<View, int[]> entry : new ArrayList<>(SAVED_GLASS.entrySet())) {
                if (entry.getKey() == null || glassRadius == null) continue;
                try {
                    int[] requested = entry.getValue();
                    glassRadius.invoke(entry.getKey(), requested[0], requested[1]);
                } catch (Throwable ignored) {}
            }
            for (Map.Entry<View, Object[]> entry : new ArrayList<>(SAVED.entrySet())) {
                View view = entry.getKey();
                Object[] values = entry.getValue();
                if (view == null) continue;
                for (int slot : RESTORE_ORDER) {
                    if (METHODS[slot] == null || values[slot] == null) continue;
                    try {
                        METHODS[slot].invoke(view, values[slot]);
                    } catch (Throwable ignored) {
                    }
                }
            }
            for (Map.Entry<View, android.graphics.drawable.Drawable> entry : new ArrayList<>(SAVED_SHORTCUT_BG.entrySet())) {
                View view = entry.getKey();
                if (view == null) continue;
                try {
                    view.setBackground(entry.getValue());
                } catch (Throwable ignored) {}
            }
        } finally {
            SAVED.clear();
            SAVED_GLASS.clear();
            SAVED_SHORTCUT_BG.clear();
            GROUP_RESOURCE_IDS.clear();
            WRITING.remove();
        }
    }
}
