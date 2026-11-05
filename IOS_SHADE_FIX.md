# iOS shade clear-button repair (2026-11-05)

All changes belong to HyperChanger. No system APK, system file or system setting is modified.

The hosted clear button could be visibly under a touch coordinate while its own click callback was never reached: the OEM shade ancestors intercepted the sequence. The module now hooks the shade root dispatch and captures only sequences whose DOWN is on the visible, enabled hosted clear control. Movement beyond platform touch slop, cancellation and multi-touch cancel the click. Other touch sequences retain native dispatch; the list-top elastic effect observes them without consuming them; accessibility still uses the normal button listener.

Clear dispatch resolves the current attached notification stack and invokes its native clearNotifications(0, false, true) once. It no longer invokes both the hidden stock button animator and the stack. The stock eligibility predicate includeChildInClearAll(row, 0) decides whether anything can be cleared, preserving ongoing, locked and other protected notification rules. The fold/history view retains its original native listener path.

Feedback:
- Accepted clearing: platform CONFIRM haptic and a short 94% scale rebound on the label and glass surface.
- No eligible notifications: platform REJECT haptic and a short horizontal shake. No Toast, banner or persistent text is shown.
- Haptics respect the user's touch-feedback settings. Feedback animations are finite; no timers or background polling were added.

Small hot-path improvements are retained: the existing clear-view scan is limited to once per 250 ms per window, and clock-part lookup uses one traversal with short-lived weak references instead of repeated name-by-name full-tree scans. Subjective animation smoothness was reported as similar; a frame-rate improvement is NOT claimed. No measured frame-rate improvement is claimed for the visual changes below.

Validation on the connected device:
- Test notification exists before a drag starting on the clear control; remains after the cancelled drag.
- A subsequent normal tap removes that disposable notification.
- Runtime logs record one stack dispatch for accepted clicks and the empty-state path after clearing.
- User confirmed feedback was visible/felt; the subsequently requested Toast removal was verified in the packaged DEX.
- Existing native Dock/face-unlock fixes, dialer helper DEX, manifest and resources are byte-identical to the pre-change APK.
- Same personal signing key; APK signature and ZIP integrity verified. Only SystemUI was restarted to load the module. No phone reboot.

Packaging used the installed APK as the base, patched the existing presentation call sites and compiled the new Java helpers with D8/API 102. This is not a complete Gradle rebuild. Equivalent source changes are included for future full builds. Device screenshots, extracted system APKs, raw logs and signing material are kept outside Git.

## Clock recovery and list-top pull (2026-11-05)

The hosted clock and its date fade together before the time glyphs reach the top clipping boundary. The module measures the native glyph positions while applying fade to the hosted clock layer, preserving native per-digit alpha. Inactive clock variants are excluded; reopening and unsupported geometry restore opacity.

When notifications are removed and native scrolling returns to the top, the resting notification baseline can move downward again. This releases the previous compressed clock size while keeping the remaining cards as layout boundaries. Clearing retains the native dismissal animation and feedback, but no longer requests panel closure.

At the top of the fully expanded portrait list, a downward drag adds a resisted visual displacement. Every eligible gesture can trigger it, including after earlier pulls; horizontal gestures, cancellation and multiple pointers do not continue the pull. Native touch handling and scrolling remain in charge. The final tuning uses a 64 dp displacement limit and a 280 ms decelerating return. The visual displacement is removed before the existing layout synchronization and reapplied afterward, so clock geometry does not inherit it. The return animator runs only after a pull; there is no continuous background timer. The previous panel-endpoint rebound was removed.

Device validation: I confirmed that the clock/date disappear together, recover after notification removal, and return to the initial size after clearing without closing the panel. I also confirmed repeated list-top pulls and ordinary upward scrolling. The pull amplitude was then increased as requested; that last amplitude adjustment has been installed, with no separate confirmation of its feel yet.

Validation for this upload: the fade curve host test passes; the Java helpers compile and package through the existing APK/D8 workflow. A complete Gradle rebuild remains unverified because the required local AGP/SDK versions are unavailable. Screenshots, APK backups and signing material remain outside Git.
