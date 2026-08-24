# Reading an obfuscated crash

Release builds are minified (`isMinifyEnabled = true`), so every class in a
crash report from a shipped build arrives as a short generated name:

```
java.lang.NoClassDefFoundError: Failed resolution of: Lwf2;
	at pb2.<clinit>(r8-map-id-dead479d…:1)
	at ti3.t0(r8-map-id-dead479d…:136)
	at ul.run(r8-map-id-dead479d…:16)
```

`wf2`, `pb2`, `ti3` and `ul` mean nothing on their own. **Do not diagnose one
of these from the shape of the name or from which library "feels" involved.**
That has been tried twice on this repo and shipped two fixes that did not work
(see the note at the end).

## Step 1 — get the mapping file for the exact build

`r8-map-id-<hash>` in the stack frames is R8's build fingerprint. The same id
appears at the top of the `mapping.txt` that build produced, so a report can
always be matched to its build with certainty. Sources, in order of speed:

1. **Play Console** — Quality → Android vitals → Crashes shows the
   deobfuscated trace directly, because AGP embeds the map in the `.aab`
   (`BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`) and
   Play applies it automatically. Covers only crashes Play itself received.
2. **Play Console → App bundle explorer** → pick the versionCode → Downloads →
   the deobfuscation/mapping file. Use this for a trace reported out-of-band
   (a user pasting a stack trace, an in-app logger).
3. **The workflow run's `quicloc-mapping-…` artifact** — attached to every
   release build by `release-play.yml` and `build-apk.yml`, named with the
   versionCode, kept 90 days.

## Step 2 — deobfuscate, don't guess

```bash
# $ANDROID_HOME/cmdline-tools/latest/bin/retrace, or from R8's jar
retrace mapping.txt crash.txt
```

To resolve a single name by hand, the mapping is plain text, `original -> obfuscated`:

```bash
grep ' -> wf2:' mapping.txt     # what wf2 really is
grep ' -> pb2:' mapping.txt     # what class's <clinit> failed
```

If a name is **absent from mapping.txt**, that is itself the finding: the class
was never renamed (a `-keep` covers it) and something else is wrong.

## Step 3 — only then decide what kind of failure it is

- `ClassNotFoundException` thrown at a reflective call site (`Class.forName`,
  `Parcel.readParcelable`) → a **naming** problem; a keep rule is the fix.
- `NoClassDefFoundError: Failed resolution of: L…;` while linking a direct
  reference (typically in `<clinit>`) → the class is **genuinely absent from
  the running process**, not merely renamed. In an app with an on-demand
  dynamic feature (`:feature_findmyphone`), the usual cause is code in one
  split referencing a class R8 placed in another split that is not installed.
  Adding keep rules does not fix this and will not.

Adding `-keep` for a class that was never renamed changes nothing. Confirm
which of the two you have *before* writing a rule.

## Why this file exists

`NoClassDefFoundError: Failed resolution of: Lyf2;` was diagnosed as R8
renaming Play Feature Delivery classes, and fixed with
`-keep class com.google.android.play.core.** { *; }` (versionCode 2016). The
crash returned on that build as `Lwf2;` — and could not have been a Play Core
class, because `-keep` also prevents renaming, so no class under that package
was obfuscated in the build that crashed. The premise was wrong, and nothing
in the pipeline could have shown that, because `mapping.txt` was discarded with
the runner. The fix was verified only against its own hypothesis — that the
keep rule stopped those classes being renamed — never against the crash.

Verify against the failure, not against your explanation of it.
