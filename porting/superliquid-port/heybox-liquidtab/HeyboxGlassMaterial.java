// SPDX-License-Identifier: GPL-3.0-only
package btm.m.ported.superliquid.io.github.liuran001.mmliquidglass;

import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RuntimeShader;
import android.util.Log;

/**
 * LiquidTab v2.1.0 material adapted to HyperChanger's existing Heybox panel.
 * Dual-light bloom from LiquidTab's GlassHostLayout (GPL-3.0-only).
 * https://github.com/big-dictator/LiquidTab/tree/v2.1.0
 */
final class HeyboxGlassMaterial {
    private static final String BLOOM_SHADER = """
            uniform float2 halfView;
            uniform float highlightAlpha;
            layout(color) uniform half4 strokeColor;
            uniform float strokeAlphaMul;
            uniform float3 lightDir1;
            layout(color) uniform half4 lightColor1;
            uniform float lightIntensity1;
            uniform float3 lightDir2;
            layout(color) uniform half4 lightColor2;
            uniform float lightIntensity2;

            half4 main(float2 fragCoord) {
                float2 delta = fragCoord - halfView;
                float2 normal = delta / max(length(delta), 0.001);
                half3 rgb = strokeColor.rgb * half(strokeAlphaMul);
                float primary = dot(normal, normalize(lightDir1.xy));
                rgb += half(primary * primary * lightIntensity1) * lightColor1.rgb;
                float secondary = dot(normal, normalize(lightDir2.xy));
                rgb += half(secondary * secondary * lightIntensity2) * lightColor2.rgb;
                return half4(rgb * half(highlightAlpha), half(highlightAlpha));
            }
            """;

    private final RuntimeShader bloom;
    private float width;
    private float height;

    private HeyboxGlassMaterial() {
        bloom = new RuntimeShader(BLOOM_SHADER);
        bloom.setColorUniform("strokeColor", Color.WHITE);
        bloom.setFloatUniform("strokeAlphaMul", 0.12f);
        // Use LiquidTab's flat-device light positions. Moving the phone does not move the highlight.
        bloom.setFloatUniform("lightDir1", -0.7071f, -0.7071f, -0.05f);
        bloom.setColorUniform("lightColor1", Color.WHITE);
        bloom.setFloatUniform("lightIntensity1", 1f);
        bloom.setFloatUniform("lightDir2", 0f, 0.1f, -0.5f);
        bloom.setColorUniform("lightColor2", Color.WHITE);
        bloom.setFloatUniform("lightIntensity2", 0.4f);
        bloom.setFloatUniform("highlightAlpha", 0.58f * 0.5f);
    }

    static HeyboxGlassMaterial create() {
        try {
            return new HeyboxGlassMaterial();
        } catch (IllegalArgumentException error) {
            Log.w("HC-HeyboxGlass", "Could not create LiquidTab outline shader", error);
            return null;
        }
    }

    void updateOutline(Paint paint, float width, float height, boolean dark) {
        if (!dark) {
            // Hide just the directional rim. The panel draws its glass and press effect separately.
            if (paint.getShader() != null) paint.setShader(null);
            if (paint.getAlpha() != 0) paint.setAlpha(0);
            return;
        }
        if (this.width != width || this.height != height) {
            this.width = width;
            this.height = height;
            bloom.setFloatUniform("halfView", width / 2f, height / 2f);
        }
        if (paint.getShader() != bloom) {
            paint.setAlpha(255);
            paint.setShader(bloom);
        }
    }
}
