# The lockdown/klinker keep rules for :feature_findmyphone were removed here
# because that module is currently excluded from the build (see the comment
# in settings.gradle.kts). Restore them alongside re-enabling the module:
#   -keep class com.hereliesaz.quicloc.lockdown.** { *; }
#   -keep class com.klinker.android.send_message.** { *; }
-keep class com.google.crypto.tink.** { *; }
-keep class com.hereliesaz.quicloc.** extends android.content.BroadcastReceiver { *; }
-keep class com.hereliesaz.quicloc.** extends android.app.Service { *; }
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
