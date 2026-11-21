package btm.m.os4.systemuihook;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.WeakHashMap;

/** Geometry of the cards as the lockscreen notification list opens and folds. */
public final class LockscreenSpreadGeometry {
    private static final WeakHashMap<View, LockscreenSpreadGeometry> ACTIVE = new WeakHashMap<>();
    private final HashSet<View> morphed = new HashSet<>();
    private final HashSet<View> visited = new HashSet<>();
    private final WeakHashMap<View, Matrix> following = new WeakHashMap<>();
    private final ArrayList<RectF> boxes = new ArrayList<>();
    private final RectF end = new RectF();
    private final int[] origin = new int[2];
    private final float[] corner = new float[2];
    private final Rect clip = new Rect();
    private final Matrix inverse = new Matrix();
    private float progress;
    private float top;

    private LockscreenSpreadGeometry() {}

    public static LockscreenSpreadGeometry begin(View host, float x, float y, float w, float h, float progress) {
        LockscreenSpreadGeometry frame = ACTIVE.computeIfAbsent(host, ignored -> new LockscreenSpreadGeometry());
        frame.end.set(x, y, x + w, y + h);
        frame.progress = Math.max(0f, Math.min(1f, progress));
        frame.top = y;
        frame.morphed.clear();
        frame.visited.clear();
        frame.boxes.clear();
        return frame;
    }

    public void addMorph(View row, float x, float y, float w, float h) {
        morphed.add(row);
        addBox(x, y, w, h);
    }

    public void apply(ViewGroup stack) {
        if (stack != null) {
            stack.getLocationOnScreen(origin);
            for (int i = 0; i < stack.getChildCount(); i++) {
                View row = stack.getChildAt(i);
                if (morphed.contains(row) || row.getVisibility() != View.VISIBLE
                        || row.getWidth() <= 0 || row.getHeight() <= 0
                        || !isNotification(row)) continue;
                follow(row, stack);
            }
        }
        following.entrySet().removeIf(entry -> {
            View row = entry.getKey();
            if (visited.contains(row)) return false;
            if (row != null && !morphed.contains(row)) row.setAnimationMatrix(null);
            return true;
        });
    }

    private void follow(View row, ViewGroup stack) {
        float slotX = origin[0] + row.getLeft() - stack.getScrollX();
        float slotY = origin[1] + row.getTop() - stack.getScrollY();
        Matrix nativeMatrix = row.getMatrix();
        corner[0] = corner[1] = 0f;
        nativeMatrix.mapPoints(corner);
        float nativeX = slotX + corner[0];
        float nativeY = slotY + corner[1];
        float height = row.getClipBounds(clip) ? Math.min(clip.bottom, row.getHeight()) : row.getHeight();
        if (height <= 0f) return;
        float width = row.getWidth();
        float drawnWidth = lerp(Math.min(width, end.width()), width);
        float drawnHeight = lerp(Math.min(height, end.height()), height);
        float x = lerp(end.centerX() - drawnWidth / 2f, nativeX);
        float y = lerp(end.top, nativeY);

        Matrix matrix = following.computeIfAbsent(row, ignored -> new Matrix());
        visited.add(row);
        matrix.setScale(drawnWidth / width, drawnHeight / height);
        matrix.postTranslate(x - slotX, y - slotY);
        if (!nativeMatrix.isIdentity() && nativeMatrix.invert(inverse)) matrix.preConcat(inverse);
        row.setAnimationMatrix(matrix);
        addBox(x, y, drawnWidth, drawnHeight);
    }

    private float lerp(float closed, float open) {
        return closed + (open - closed) * progress;
    }

    private void addBox(float x, float y, float width, float height) {
        if (width <= 0f || height <= 0f) return;
        boxes.add(new RectF(x, y, x + width, y + height));
        top = Math.min(top, y);
    }

    private static boolean isNotification(View row) {
        for (Class<?> type = row.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getSimpleName().equals("ExpandableNotificationRow")) return true;
        }
        return false;
    }

    public static List<RectF> boxes(View host) {
        LockscreenSpreadGeometry frame = current(host);
        return frame == null ? List.of() : frame.boxes;
    }

    public static LockscreenSpreadGeometry current(View host) {
        return ACTIVE.get(host);
    }

    public static float screenTop(View root) {
        for (var entry : ACTIVE.entrySet()) {
            View host = entry.getKey();
            if (host != null && host.getRootView() == root) return entry.getValue().top;
        }
        return Float.NaN;
    }

    public static void clear(View host) {
        LockscreenSpreadGeometry frame = ACTIVE.remove(host);
        if (frame == null) return;
        for (View row : frame.following.keySet()) if (row != null) row.setAnimationMatrix(null);
    }
}
