package app.player.feature.library

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Folders first, or every video in one flat list (US-03.1). */
enum class ViewMode { Folders, AllVideos }

enum class Layout { List, Grid }

/** Optional details under each video, chosen from the fields menu (US-02.2). */
enum class Field { Size, Resolution, FrameRate, Date, Extension }

data class LibraryPrefs(
    val viewMode: ViewMode = ViewMode.Folders,
    val layout: Layout = Layout.List,
    val fields: Set<Field> = emptySet(),
    /** US-02.4, default on. */
    val cacheThumbnails: Boolean = true,
)

private val Context.libraryStore: DataStore<Preferences> by preferencesDataStore("library")

/** Library view choices, kept across restarts. */
class LibraryPrefsStore(context: Context) {
    private val store = context.applicationContext.libraryStore

    val prefs: Flow<LibraryPrefs> = store.data.map { it.toPrefs() }

    suspend fun setViewMode(mode: ViewMode) = store.edit { it[VIEW_MODE] = mode.name }

    suspend fun setLayout(layout: Layout) = store.edit { it[LAYOUT] = layout.name }

    suspend fun toggleField(field: Field) = store.edit {
        val now = it[FIELDS].orEmpty()
        it[FIELDS] = if (field.name in now) now - field.name else now + field.name
    }

    suspend fun setCacheThumbnails(on: Boolean) = store.edit { it[CACHE_THUMBNAILS] = on }

    private companion object {
        val VIEW_MODE = stringPreferencesKey("view_mode")
        val LAYOUT = stringPreferencesKey("layout")
        val FIELDS = stringSetPreferencesKey("fields")
        val CACHE_THUMBNAILS = booleanPreferencesKey("cache_thumbnails")

        fun Preferences.toPrefs() = LibraryPrefs(
            viewMode = enumOr(this[VIEW_MODE], ViewMode.Folders),
            layout = enumOr(this[LAYOUT], Layout.List),
            fields = this[FIELDS].orEmpty().mapNotNullTo(mutableSetOf()) { name ->
                Field.entries.firstOrNull { it.name == name }
            },
            cacheThumbnails = this[CACHE_THUMBNAILS] ?: true,
        )

        inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: default
    }
}
