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

# Play Feature Delivery's session-state model (SplitInstallSessionState and
# its internal Parcelable counterparts) crosses the SplitInstallService AIDL
# boundary via Parcel.readParcelable(), which resolves the class by the
# fully-qualified name the (un-obfuscated) Play Store process wrote into the
# Parcel — a Class.forName() lookup R8 can't see and so can't protect by
# default. play-services-basement ships its own consumer rules to keep
# names for the equivalent GMS SafeParcelable/ReflectedParcelable classes;
# the feature-delivery AAR ships no consumer rules at all, so without this,
# R8 renames these classes and the lookup throws NoClassDefFoundError /
# "ClassNotFoundException: <short obfuscated name>" on a background
# executor thread once a split-install response comes back.
-keep class com.google.android.play.core.** { *; }
-dontwarn com.google.android.play.core.**
