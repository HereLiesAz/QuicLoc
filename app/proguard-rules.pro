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

# ------------------------------------------------------------------
# Additional safety: keep Parcelable implementations and their CREATOR
# fields project-wide. Parcel.readParcelable() (and cross-process AIDL/Parcel
# boundaries) use the runtime class name; if R8 renames/removes these classes
# and their CREATORs, the platform can throw ClassNotFoundException/NoClassDef
# (the stacktrace you reported referenced short obfuscated names like "wf2").
# Keeping parcelable classes prevents R8 from obfuscating or removing them.
# This is intentionally broad but safe for correctness of dynamic feature
# delivery and any Parcelable crossing process/AIDL/Parcel boundaries.

-keep class * implements android.os.Parcelable { public static final android.os.Parcelable$Creator CREATOR; }
-keepclassmembers class * implements android.os.Parcelable { public static final android.os.Parcelable$Creator CREATOR; }
-keepnames class * implements android.os.Parcelable { *; }

# Also be explicit about Play Core's internal splitinstall model classes
# (some past R8 failures required keeping the model packages by name).
-keep class com.google.android.play.core.splitinstall.** { *; }
-keep class com.google.android.play.core.splitinstall.model.** { *; }
-keep class com.google.android.play.core.internal.** { *; }
-keepnames class com.google.android.play.core.** { *; }

# Additional rules for Play Core 2.x and GMS Tasks to prevent NoClassDefFoundError
# in R8 Full Mode (standard).
-keep class com.google.android.play.core.common.** { *; }
-keep class com.google.android.gms.tasks.** { *; }
-keep class com.google.android.play.core.splitinstall.internal.** { *; }

# Keep common Play Core dialog activities
-keep class com.google.android.play.core.common.PlayCoreDialogWrapperActivity { *; }
