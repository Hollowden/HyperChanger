// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public final class ShadeMaterialHotPath {
    static final int VOLUME = 1;
    static final int SHADE = 2;

    private static final String VOLUME_CLASS = "com.android.systemui.miui.volume.";
    // Cache the accessors per runtime class, without keeping old plugin class loaders alive.
    private static final ClassValue<Map<String, Method>> NOTIFICATION_METHODS = new ClassValue<>() {
        @Override
        protected Map<String, Method> computeValue(Class<?> type) {
            Map<String, Method> rowMethods = new HashMap<>();
            for (Method method : type.getMethods()) {
                if (method.getParameterCount() != 0) continue;
                String name = method.getName();
                switch (name) {
                    case "getEntry", "getSbn", "isFocusNotification", "getNotification",
                            "isMediaNotification" -> rowMethods.putIfAbsent(name, method);
                    default -> {}
                }
            }
            return rowMethods;
        }
    };

    private ShadeMaterialHotPath() {}

    public static Method notificationMethod(Class<?> type, String name) {
        return NOTIFICATION_METHODS.get(type).get(name);
    }

    public static int glassCaller() {
        boolean shade = false;
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String name = frame.getClassName();
            if (containsVolumeClass(name)) return VOLUME;
            if (!shade) {
                shade = name.startsWith("miui.systemui.controlcenter.")
                        || name.startsWith("com.android.systemui.statusbar.notification.")
                        || name.startsWith("com.android.systemui.shade.")
                        || name.startsWith("com.miui.systemui.shade.");
            }
        }
        return shade ? SHADE : 0;
    }

    private static boolean containsVolumeClass(String name) {
        int lastStart = name.length() - VOLUME_CLASS.length();
        for (int start = 0; start <= lastStart; start++) {
            char first = name.charAt(start);
            if ((first == 'c' || first == 'C')
                    && name.regionMatches(true, start, VOLUME_CLASS, 0, VOLUME_CLASS.length())) {
                return true;
            }
        }
        return false;
    }
}
