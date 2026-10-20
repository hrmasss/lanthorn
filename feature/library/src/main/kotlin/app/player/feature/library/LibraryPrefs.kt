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
    /** US-04, one order for every list. */
    val sort: Sort = Sort(),
    val filter: LibraryFilter = LibraryFilter(),
    /** US-06.2, newest first. */
    val recentSearches: List<String> = emptyList(),
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

    suspend fun setSort(sort: Sort) = store.edit {
        it[SORT_KEY] = sort.key.name
        it[SORT_DESCENDING] = sort.descending
    }

    /** Adds [value] to the set under [key], or removes it when [add] is false. */
    private suspend fun editSet(key: Preferences.Key<Set<String>>, value: String, add: Boolean) = store.edit {
        val now = it[key].orEmpty()
        it[key] = if (add) now + value else now - value
    }

    suspend fun setExcluded(folderKey: String, excluded: Boolean) = editSet(EXCLUDED, folderKey, excluded)

    /** [id] is a folder key or a video uri. */
    suspend fun setHidden(id: String, hidden: Boolean) = editSet(HIDDEN, id, hidden)

    suspend fun setExtension(extension: String, on: Boolean) = editSet(DISABLED_EXTENSIONS, extension, !on)

    suspend fun addSearch(query: String) = store.edit {
        it[SEARCHES] = recent(it).withSearch(query).joinToString("\n")
    }

    suspend fun removeSearch(query: String) = store.edit {
        it[SEARCHES] = (recent(it) - query).joinToString("\n")
    }

    private companion object {
        val VIEW_MODE = stringPreferencesKey("view_mode")
        val LAYOUT = stringPreferencesKey("layout")
        val FIELDS = stringSetPreferencesKey("fields")
        val CACHE_THUMBNAILS = booleanPreferencesKey("cache_thumbnails")
        val SORT_KEY = stringPreferencesKey("sort_key")
        val SORT_DESCENDING = booleanPreferencesKey("sort_descending")
        val EXCLUDED = stringSetPreferencesKey("excluded_folders")
        val HIDDEN = stringSetPreferencesKey("hidden")
        val DISABLED_EXTENSIONS = stringSetPreferencesKey("disabled_extensions")
        val SEARCHES = stringPreferencesKey("recent_searches")

        fun recent(p: Preferences) = p[SEARCHES]?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()

        fun Preferences.toPrefs() = LibraryPrefs(
            viewMode = enumOr(this[VIEW_MODE], ViewMode.Folders),
            layout = enumOr(this[LAYOUT], Layout.List),
            fields = this[FIELDS].orEmpty().mapNotNullTo(mutableSetOf()) { name ->
                Field.entries.firstOrNull { it.name == name }
            },
            cacheThumbnails = this[CACHE_THUMBNAILS] ?: true,
            sort = Sort(enumOr(this[SORT_KEY], SortKey.Name), this[SORT_DESCENDING] ?: false),
            filter = LibraryFilter(this[EXCLUDED].orEmpty(), this[HIDDEN].orEmpty(), this[DISABLED_EXTENSIONS].orEmpty()),
            recentSearches = recent(this),
        )

        inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: default
    }
}
