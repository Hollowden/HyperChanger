# iOS shade clear-button repair (2026-11-05)

All changes belong to HyperChanger. No system APK, system file or system setting is modified.

The hosted clear button could be visibly under a touch coordinate while its own click callback was never reached: the OEM shade ancestors intercepted the sequence. The module now hooks the shade root dispatch and captures only sequences whose DOWN is on the visible, enabled hosted clear control. Movement beyond platform touch slop, cancellation and multi-touch cancel the click. Other touch sequences proceed unchanged; accessibility still uses the normal button listener.

Clear dispatch resolves the current attached notification stack and invokes its native clearNotifications(0, true, true) once. It no longer invokes both the hidden stock button animator and the stack. The stock eligibility predicate includeChildInClearAll(row, 0) decides whether anything can be cleared, preserving ongoing, locked and other protected notification rules. The fold/history view retains its original native listener path.

Feedback:
- Accepted clearing: platform CONFIRM haptic and a short 94% scale rebound on the label and glass surface.
- No eligible notifications: platform REJECT haptic and a short horizontal shake. No Toast, banner or persistent text is shown.
- Haptics respect the user's touch-feedback settings. Feedback animations are finite; no timers or background polling were added.

Small hot-path improvements are retained: the existing clear-view scan is limited to once per 250 ms per window, and clock-part lookup uses one traversal with short-lived weak references instead of repeated name-by-name full-tree scans. Subjective animation smoothness was reported as similar; a frame-rate improvement is NOT claimed. Further animation/performance tuning was stopped at the user's request.

Validation on the connected device:
- Test notification exists before a drag starting on the clear control; remains after the cancelled drag.
- A subsequent normal tap removes that disposable notification.
- Runtime logs record one stack dispatch for accepted clicks and the empty-state path after clearing.
- User confirmed feedback was visible/felt; the subsequently requested Toast removal was verified in the packaged DEX.
- Existing native Dock/face-unlock fixes, dialer helper DEX, manifest and resources are byte-identical to the pre-change APK.
- Same personal signing key; APK signature and ZIP integrity verified. Only SystemUI was restarted to load the module. No phone reboot.

Packaging used the installed APK as the base, patched the existing presentation call sites and compiled the new Java helpers with D8/API 102. This is not a complete Gradle rebuild. Equivalent source changes are included for future full builds. Device screenshots, extracted system APKs, raw logs and signing material are kept outside Git.
