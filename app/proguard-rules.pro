# :feature_findmyphone's components are addressed by ComponentName string
# from the base (FindMyPhone.kt), not a compile-time class reference, so R8
# has no call site to infer keep rules from — same reasoning for klinker's
# android-smsmms, which drives MMS sending via reflection internally.
-keep class com.hereliesaz.quicloc.lockdown.** { *; }
-keep class com.klinker.android.send_message.** { *; }
-keep class com.google.crypto.tink.** { *; }
-keep class com.hereliesaz.quicloc.** extends android.content.BroadcastReceiver { *; }
-keep class com.hereliesaz.quicloc.** extends android.app.Service { *; }
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
