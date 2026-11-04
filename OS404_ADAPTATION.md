# OS4.0.4.0 Dock reveal adaptation (experimental)

Upstream: https://github.com/ColdP/HyperChanger
Branch: codex/os404-dock-reveal

Target: OS4.0.4.0.XPBCNXM, launcher 8.01.02.8583.
Engine build ID: c014a14bb9ddab4431a887c98c6d56ce305b0573.

Recovered RVAs from the engine GNU mini symbol table. Verified OpacityLayer's save/translate/opacity/restore protocol and Engine::ScheduleFrame(bool) receiver (+0x680). Existing exact function-entry checks remain enabled. Added a complete 13-instruction ScheduleFrame check for this engine.

Validation: native hyperchanger_folder library builds with NDK 28.2.13676358 / CMake 3.22.1; APK zip alignment and signature verification pass. Runtime unlock behavior has NOT been tested. No successful-fix claim is made.

Local experimental APK packaging replaces only lib/arm64-v8a/libhyperchanger_folder.so in installed HyperChanger 1.2.2 RC2, removes old JAR signatures, zipaligns with 16 KiB page alignment, and signs with a separate personal key. It is not a complete Gradle rebuild. Device system libraries, captures, logs and signing keys are excluded from Git.

Personal signature differs from upstream. Do not uninstall an existing installation without backing up module settings and planning a rollback.
