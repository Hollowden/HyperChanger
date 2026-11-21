# Source Porting Notes

These directories contain the app-navigation sources and patch tools for the personal APK. The regular Gradle build does not include the relocated app-navigation payload.

NetEase: `NeteaseKanadeModule.java` implements the tested UI behavior but depends on `LiquidGlassHostLayout`, `LiquidGlassPanel`, and `GlassConfig` from the relocated SuperLiquid DEX. The Java files with those names in the original staging directory were compile only stubs and are intentionally omitted. Current `main` also has an older CloudMusic hook; disable that installer when integrating to prevent duplicate navigation.

Heybox: The SuperLiquid port uses a relocated smali dependency closure. The [LiquidTab material patch](superliquid-port/heybox-liquidtab/README.md) includes the helper source and the steps for updating a matching personal APK.
