package app.player.core.testing

/**
 * The EARS criteria ([ids], like "US-17.2" or "NF-6") a test proves. On a test class it covers
 * every test in it. CI's EARS report reads these tags from the test sources and pairs them with
 * the JUnit results; keep the annotation literal (string constants are not resolved).
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class Ears(vararg val ids: String)
