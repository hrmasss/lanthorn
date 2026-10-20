package app.player.feature.audio

import app.player.core.engine.PlayerEngine
import app.player.core.engine.PropertyType
import kotlinx.coroutines.flow.StateFlow

/**
 * The slice of the engine's generic property API this module uses. An interface so the
 * controllers run in JVM unit tests against a fake.
 */
internal interface Mpv {
    fun set(name: String, value: String)
    fun command(vararg args: String)
    fun <T : Any> observe(name: String, type: PropertyType<T>): StateFlow<T?>
}

internal fun PlayerEngine.asMpv(): Mpv = object : Mpv {
    override fun set(name: String, value: String) = this@asMpv.set(name, value)
    override fun command(vararg args: String) = this@asMpv.command(*args)
    override fun <T : Any> observe(name: String, type: PropertyType<T>) = this@asMpv.observe(name, type)
}
