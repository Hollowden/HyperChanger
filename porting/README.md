# Source Porting Notes

This branch is based on Hollowden/HyperChanger `main` at `97a4bd2`.
The directories here contain source and patch tools from the tested personal APK. They are reference material, not yet integrated with the current Gradle project. This branch is not build ready.

NetEase: `NeteaseKanadeModule.java` implements the tested UI behavior but depends on `LiquidGlassHostLayout`, `LiquidGlassPanel`, and `GlassConfig` from the relocated SuperLiquid DEX. The Java files with those names in the original staging directory were compile only stubs and are intentionally omitted. Current `main` also has an older CloudMusic hook; disable that installer when integrating to prevent duplicate navigation.

Heybox: The SuperLiquid port was built from a relocated smali dependency closure, not original source. The scripts document that process. The extracted APK, smali payload, personal key, user settings, and screenshots are intentionally absent. A source level port still needs to be made against current `main`.

The user requested no APK build in this handoff. No Gradle build or device test has been done for this branch.
