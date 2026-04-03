with open('app/build.gradle.kts', 'r') as f:
    content = f.read()

deps_to_add = """
    // MMS Sending
    implementation("com.klinkerapps:android-smsmms:5.2.6")

    // CameraX
    val camerax_version = "1.3.1"
    implementation("androidx.camera:camera-core:${camerax_version}")
    implementation("androidx.camera:camera-camera2:${camerax_version}")
    implementation("androidx.camera:camera-lifecycle:${camerax_version}")
    implementation("androidx.camera:camera-view:${camerax_version}")
"""

if "com.klinkerapps:android-smsmms" not in content:
    content = content.replace(
        'implementation("com.google.android.gms:play-services-location:21.3.0")',
        'implementation("com.google.android.gms:play-services-location:21.3.0")\n' + deps_to_add
    )

with open('app/build.gradle.kts', 'w') as f:
    f.write(content)
