package btm.m.ported.superliquid.io.github.liuran001.mmliquidglass;

import android.content.SharedPreferences;

/** Connect the transplanted renderer to HyperChanger's existing per-app controls. */
public final class NavigationPreferences {
    public static void apply(SharedPreferences p) {
        String prefix = GlassConfig.key();
        String pkg = "heybox".equals(prefix) ? "com.max.xiaoheihe" : "com.tencent.mm";
        String scope = "scope." + pkg + ".";
        GlassConfig.enabled = p.getBoolean(scope+"enabled", true);
        GlassConfig.blurRadius = p.getInt(scope+"liquid_blur_radius", p.getInt(prefix+"_blur",3));
        float baseWidth = p.getFloat(prefix+"_width_scale", "heybox".equals(prefix)?0.5f:1f);
        float baseHeight = p.getFloat(prefix+"_pill_scale",1f);
        GlassConfig.widthScale = baseWidth * factor(p,scope,"width");
        GlassConfig.pillScale = baseHeight * factor(p,scope,"height");
        int offset = p.getInt(prefix+"_offset_dp",12);
        if(p.getBoolean(scope+"navigation_vertical_offset_enabled",false)) {
            offset += p.getInt(scope+"navigation_vertical_offset_dp",0);
        } else if(p.getBoolean("app_navigation_vertical_offset_enabled",false)) {
            offset += p.getInt("app_navigation_vertical_offset_dp",0);
        }
        GlassConfig.barOffsetDp = offset;
        LiquidGlassModule.log(4,"HyperChanger SuperLiquid port: " + pkg + " enabled="+GlassConfig.enabled);
    }
    private static float factor(SharedPreferences p,String scope,String dimension) {
        if(!p.getBoolean(scope+"navigation_"+dimension+"_scale_enabled",false)) return 1f;
        int value=p.getInt(scope+"navigation_"+dimension+"_scale_hundredths",0);
        return Math.max(0.1f, 1f+Math.max(-10000,Math.min(10000,value))/10000f);
    }
}
