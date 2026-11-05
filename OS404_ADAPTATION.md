# OS4.0.4.0 Dock reveal adaptation (experimental)

Upstream: https://github.com/ColdP/HyperChanger
Branch: codex/os404-dock-reveal

Target: OS4.0.4.0.XPBCNXM, launcher 8.01.02.8583.
Engine build ID: c014a14bb9ddab4431a887c98c6d56ce305b0573.

Recovered RVAs from the engine GNU mini symbol table. Verified OpacityLayer's save/translate/opacity/restore protocol and Engine::ScheduleFrame(bool) receiver (+0x680). Existing exact function-entry checks remain enabled. Added a complete 13-instruction ScheduleFrame check for this engine.

Validation: native hyperchanger_folder library builds with NDK 28.2.13676358 / CMake 3.22.1; APK zip alignment and signature verification pass. Runtime unlock behavior has NOT been tested. No successful-fix claim is made.

Local experimental APK packaging replaces only lib/arm64-v8a/libhyperchanger_folder.so in installed HyperChanger 1.2.2 RC2, removes old JAR signatures, zipaligns with 16 KiB page alignment, and signs with a separate personal key. It is not a complete Gradle rebuild. Device system libraries, captures, logs and signing keys are excluded from Git.

Personal signature differs from upstream. Do not uninstall an existing installation without backing up module settings and planning a rollback.

Static device-binary validation: all six existing entry guards pass for paint, raster, render, keyguard (24 instructions), Dart capture and Dart icon. This confirms hook entry addresses, not runtime behavior. Official in-app ZIP export completed before installation changes; no personal backup is tracked.

Runtime correction: engine object vptrs use the Itanium address point (symbol RVA + 16), not the start of _ZTV. Updated the three new-engine vtable RVAs. After replacing the APK and restarting only the launcher, reveal hooks report ready and bind an existing Dock glass layer (nonzero ID). Awaiting manual lock/unlock visual verification; phone reboot not performed.

Final validation (2026-11-04): user confirmed successful unlock entrance animation on Xiaomi 17 Pro Max / OS4.0.4.0.XPBCNXM / launcher 8583. Logs show a nonzero Dock glass ID and fresh icon samples through unlock and normal restoration. Original settings were restored and nine preference XML files matched the pre-uninstall backup byte-for-byte. No phone reboot was performed. Earlier notes above document development stages; runtime behavior is now verified on this device only.

## Face-authentication pre-entry gate (2026-11-05)

A preloaded settled icon pose could previously display a normal Dock before the first non-identity unlock sample. FollowSession now keeps the glass hidden until real motion is observed, then follows fresh samples from that unlock epoch. Session completion still restores a normal tree. The existing three-second cancellation limit remains a fail-open recovery for absent animation samples; scheduling is limited to the active unlock session, with no added permanent polling. Keyguard transition logging distinguishes lockscreen display from the start of desktop entry.

Regression coverage: native session tests on the connected Android device pass stale samples, fresh settled preload, moving samples, settlement, epoch reset, no-animation timeout, and immediate fingerprint-style entry. The APK preserves all DEX and resources from the previously installed build. Only the native folder/reveal library was replaced. After the requested face-authentication/delayed-swipe and fingerprint checks, the user reported the flash appears gone. Treat this as preliminary visual confirmation, not exhaustive verification across all unlock modes. Logs show normal session completion and return to keyguard without repeated unlock sessions during lockscreen dwell.
