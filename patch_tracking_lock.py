with open('app/src/main/java/com/hereliesaz/quicloc/TrackingLockActivity.kt', 'r') as f:
    content = f.read()

import re

# Fix panic mode silent failure
panic_logic = """
    private fun triggerPanicMode() {
        Toast.makeText(this, "Device Locked.", Toast.LENGTH_SHORT).show()
        val currentCapture = imageCapture
        if (currentCapture == null) {
            Log.e(TAG, "Image capture is null, entering panic mode without photo")
            TrackingService.enterPanicMode(this@TrackingLockActivity, null)
            return
        }

        val photoFile = File(externalMediaDirs.firstOrNull(), "${System.currentTimeMillis()}_lock.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        currentCapture.takePicture(
            outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    TrackingService.enterPanicMode(this@TrackingLockActivity, null)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "Photo capture succeeded: ${photoFile.absolutePath}")
                    TrackingService.enterPanicMode(this@TrackingLockActivity, photoFile.absolutePath)
                }
            })
    }
"""

content = re.sub(
    r'private fun triggerPanicMode\(\) \{.*?(?=override fun onDestroy\(\))',
    panic_logic,
    content,
    flags=re.DOTALL
)

# Address home bypass by hooking into onPause
on_pause_logic = """
    override fun onPause() {
        super.onPause()
        // If not finishing, bring it back to the front
        if (!isFinishing) {
            val intent = android.content.Intent(this, TrackingLockActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        }
    }
"""

content = content.replace(
    'override fun onBackPressed() {\n        // Prevent back button\n    }',
    'override fun onBackPressed() {\n        // Prevent back button\n    }\n' + on_pause_logic
)

with open('app/src/main/java/com/hereliesaz/quicloc/TrackingLockActivity.kt', 'w') as f:
    f.write(content)
