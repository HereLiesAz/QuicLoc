package com.hereliesaz.quicloc

/**
 * Application subclass extending [com.google.android.play.core.splitcompat.SplitCompatApplication]
 * so code and resources from on-demand dynamic feature modules (currently
 * `:feature_camera`) can be loaded in-process after they're installed via
 * `SplitInstall`, without requiring a fresh process start.
 *
 * SplitCompatApplication already calls `SplitCompat.install(...)` in its
 * `attachBaseContext`, so no override is needed here.
 */
class QuicLocApp : com.google.android.play.core.splitcompat.SplitCompatApplication()
