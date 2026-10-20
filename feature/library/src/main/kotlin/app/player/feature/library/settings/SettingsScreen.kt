package app.player.feature.library.settings

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.player.core.settingsapi.SettingsGroup
import app.player.core.settingsapi.settingsGroups
import app.player.feature.library.BackButton
import app.player.feature.library.LibraryIcons
import app.player.feature.library.R
import app.player.feature.library.fold

/** A setting as search sees it: resolved text, ready to match. */
data class SettingHit(val groupId: String, val group: String, val key: String, val title: String, val summary: String?)

/** Every setting of every group, with titles in the current language. */
internal fun settingIndex(resources: Resources, groups: List<SettingsGroup>): List<SettingHit> =
    groups.flatMap { g ->
        val groupTitle = resources.getString(g.title)
        g.entries.map { e -> SettingHit(g.id, groupTitle, e.key, resources.getString(e.title), e.summary?.let(resources::getString)) }
    }

/** US-65.1: every setting whose title or summary holds all typed words, ignoring case and accents. */
fun searchSettings(query: String, index: List<SettingHit>): List<SettingHit> {
    val words = fold(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    return index.filter { hit ->
        val text = fold(hit.title + " " + hit.summary.orEmpty())
        words.all { it in text }
    }
}

/** The settings screen: a search field, then one row per group. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsHome(onGroup: (groupId: String, highlight: String?) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val groups = remember { context.settingsGroups }
    val index = remember(groups, resources) { settingIndex(resources, groups) }
    var query by rememberSaveable { mutableStateOf("") }
    val hits = remember(query, index) { searchSettings(query, index) }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.settings_title)) }, navigationIcon = { BackButton(onBack) })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.settings_search_hint)) },
                leadingIcon = { Icon(LibraryIcons.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(LibraryIcons.Close, contentDescription = stringResource(R.string.search_clear))
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("settings-search"),
            )
            LazyColumn(Modifier.fillMaxSize().testTag("settings-list")) {
                if (query.isBlank()) {
                    items(groups, key = { it.id }) { g ->
                        ListItem(
                            headlineContent = { Text(stringResource(g.title)) },
                            modifier = Modifier.clickable { onGroup(g.id, null) },
                        )
                    }
                } else if (hits.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.search_none),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                } else {
                    items(hits, key = { it.groupId + "/" + it.key }) { hit ->
                        ListItem(
                            overlineContent = { Text(hit.group) },
                            headlineContent = { Text(hit.title) },
                            supportingContent = hit.summary?.let { { Text(it) } },
                            modifier = Modifier.clickable { onGroup(hit.groupId, hit.key) }.testTag("settings-hit"),
                        )
                    }
                }
            }
        }
    }
}

/** One group's page, scrolled to [highlight] when search opened it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsGroupScreen(groupId: String, highlight: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val group = remember(groupId) { context.settingsGroups.firstOrNull { it.id == groupId } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(group?.let { stringResource(it.title) }.orEmpty()) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) { group?.Content(highlight) }
    }
}
