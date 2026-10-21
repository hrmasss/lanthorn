package app.player.core.testing

/**
 * Stand-in until core/testing (P1.35) lands: the EARS criteria a test proves.
 * Same name and shape as the shared one, so only the dependency changes then.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class Ears(vararg val ids: String)
