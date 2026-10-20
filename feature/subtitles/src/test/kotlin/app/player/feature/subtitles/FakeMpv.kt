package app.player.feature.subtitles

import app.player.core.engine.PropertyType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Records every call in order; observed properties are plain flows the test drives. */
internal class FakeMpv : Mpv {
    val calls = mutableListOf<String>()
    private val flows = mutableMapOf<String, MutableStateFlow<Any?>>()

    override fun set(name: String, value: String) {
        calls += "set $name=$value"
    }

    override fun command(vararg args: String) {
        calls += args.joinToString(" ")
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> observe(name: String, type: PropertyType<T>): StateFlow<T?> =
        flows.getOrPut(name) { MutableStateFlow(null) } as StateFlow<T?>

    fun emit(name: String, value: Any?) {
        flows.getOrPut(name) { MutableStateFlow(null) }.value = value
    }
}
