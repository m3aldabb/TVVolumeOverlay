# Keep the accessibility service class and all its members so the system can
# bind to it by name from the manifest. Without this, R8 renames the class
# and the framework cannot find com.volumeoverlay.VolumeAccessibilityService.
-keep class com.volumeoverlay.VolumeAccessibilityService { *; }

# Keep any subclass of AccessibilityService (covers this app and future services).
-keep public class * extends android.accessibilityservice.AccessibilityService { *; }
