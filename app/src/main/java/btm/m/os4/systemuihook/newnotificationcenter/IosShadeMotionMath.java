// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook.newnotificationcenter;

/** Pure, bounded motion curves shared by rendering and regression tests. */
public final class IosShadeMotionMath {
    private IosShadeMotionMath() {}
    public static float clockFade(float glyphTop,float safeTop,float band) {
        if(!Float.isFinite(glyphTop) || !Float.isFinite(safeTop) || !Float.isFinite(band) || band<=0) return 1f;
        float p=Math.max(0f,Math.min(1f,(glyphTop-safeTop)/band));
        return p*p*(3f-2f*p);
    }
}
