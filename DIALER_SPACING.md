# Dialer circle spacing

On Contacts 18.30.00.60 the active theme replaces dial-number assets with square bitmaps that contain both the number and a circular gray background. Insetting the View background does not affect those themed images.

The module trims the outside 7% radius on each edge of these square circular icons using an antialiased bitmap shader. Number and letter pixels retain their original scale and coordinates; the key View bounds and listeners are untouched. The green call button is excluded. Narrow stock number-only assets are left untouched. The separate DialerItemView background path is also supported.

The operation runs only on the main thread, is idempotent per Drawable, and uses weak View keys. It is attached to the existing enabled Contacts background lifecycle hook; no phone APK or system resource is changed. This is currently a fixed 86% diameter adjustment, not a settings slider.

Validated on device with the active theme: 12 number-key backgrounds have clear vertical gaps, labels and call button unchanged. Only the Contacts app was restarted. APK signed with the same personal certificate; the previously validated Dock native library is byte-identical.

Local APK was assembled from the installed module using Apktool for the existing lifecycle call site and D8 for the helper class, retaining existing resources and replacing no system APK. Equivalent maintainable source is included in ContactsDialerHooks.kt and ContactsDialerKeySpacing.java. This was not a full Gradle build. Personal screenshots and extracted themed resources remain local.
