package app.player.core.testing

import android.graphics.Bitmap
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.io.File

/**
 * Saves the whole screen as `<name>.png` under the running test's evidence folder,
 * `<files>/evidence/<test class>#<method>/`, which the Instrumented job pulls and the EARS report
 * links to the test. Call it from the test thread at the moment that proves the criterion.
 */
fun evidenceShot(name: String): File? {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "evidence/${currentTest()}")
    dir.mkdirs()
    val file = File(dir, "$name.png")
    val shot = instrumentation.uiAutomation.takeScreenshot()
    if (shot == null) {
        Log.w(TAG, "no screenshot for $name")
        return null
    }
    file.outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    shot.recycle()
    Log.i(TAG, "evidence ${file.parentFile?.name}/${file.name}")
    return file
}

/** `class#method` of the @Test method on this thread's stack, or "unknown". */
internal fun currentTest(): String {
    val loader = InstrumentationRegistry.getInstrumentation().context.classLoader
    for (frame in Thread.currentThread().stackTrace) {
        val cls = runCatching { Class.forName(frame.className, false, loader) }.getOrNull() ?: continue
        val isTest = cls.declaredMethods.any { it.name == frame.methodName && it.isAnnotationPresent(Test::class.java) }
        if (isTest) return "${cls.name}#${frame.methodName}"
    }
    return "unknown"
}

private const val TAG = "Evidence"
