// SPDX-License-Identifier: GPL-3.0-only
package btm.m.ported.superliquid.io.github.liuran001.mmliquidglass;

import android.graphics.Paint;

/** Selects the Heybox glass material and suppresses its additional outline. */
final class HeyboxGlassMaterial {
    private HeyboxGlassMaterial() {}

    static HeyboxGlassMaterial create() {
        return new HeyboxGlassMaterial();
    }

    // Keep the signature used by existing personal APKs.
    void updateOutline(Paint paint, float width, float height, boolean dark) {
        if (paint.getShader() != null) paint.setShader(null);
        if (paint.getAlpha() != 0) paint.setAlpha(0);
    }
}
