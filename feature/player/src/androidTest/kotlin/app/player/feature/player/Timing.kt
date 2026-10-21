package app.player.feature.player

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue

/**
 * A timing bound. Hosted CI runs pass `-e timing lenient` (the shared emulator is slower, its
 * numbers make no claim either way): there the time is logged and never fails the test.
 */
fun assertWithin(what: String, tookMs: Long, limitMs: Long) {
    val lenient = InstrumentationRegistry.getArguments().getString("timing") == "lenient"
    android.util.Log.i("Timing", "$what: $tookMs ms (limit $limitMs)" + if (lenient && tookMs > limitMs) ", over, lenient run" else "")
    if (!lenient) assertTrue("$what took $tookMs ms (limit $limitMs)", tookMs <= limitMs)
}
