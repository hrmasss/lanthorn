package app.player.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.player.core.data.library.toFolders
import app.player.core.designsystem.theme.LanthornTheme
import app.player.core.testing.Ears
import app.player.core.testing.evidenceShot
import app.player.feature.library.settings.GeneralSettings
import app.player.feature.library.settings.LibrarySettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * US-69.3: at 200% font scale no text in the library or settings is cut off. Text the design
 * shortens with an ellipsis (long names) is allowed; any other text that does not fit fails.
 * Each screen is drawn once on the phone's screen for the screenshot, and once in a very tall box
 * so every row is composed and checked.
 */
@RunWith(AndroidJUnit4::class)
class FontScaleTest {
    @get:Rule val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Composable
    private fun Large(tall: Boolean, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
            LanthornTheme {
                if (tall) {
                    Box(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).requiredHeight(6000.dp)) { content() }
                } else {
                    content()
                }
            }
        }
    }

    private fun assertNoClippedText(screen: String) {
        val nodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue("$screen: no text found", nodes.isNotEmpty())
        val clipped = nodes.mapNotNull { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            val r = results.firstOrNull() ?: return@mapNotNull null
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: r.layoutInput.text.text
            if (r.layoutInput.overflow == TextOverflow.Ellipsis) return@mapNotNull null
            // Cut off: wider or taller than the room it was given, by more than rounding.
            val wide = r.multiParagraph.width - r.size.width
            val tall = r.multiParagraph.height - r.size.height
            val lines = r.multiParagraph.didExceedMaxLines
            "$text (over by ${"%.1f".format(wide)} x ${"%.1f".format(tall)} px, lines cut $lines)"
                .takeIf { tall > 2f || lines || (r.didOverflowWidth && wide > 2f) }
        }
        android.util.Log.i("US-69.3", "$screen: ${nodes.size} texts checked")
        assertTrue("$screen at 200% font: clipped text $clipped", clipped.isEmpty())
    }

    private val videos = listOf(
        testVideo(1, "external_primary/Movies/", "A rather long holiday video name from the summer trip to the coast.mp4"),
        testVideo(2, "external_primary/Movies/Trips/", "Hike.mkv"),
        testVideo(3, "external_primary/DCIM/Camera/", "VID_20260101_120000.mp4"),
    )

    private fun ctx() = RowContext(ThumbnailLoader(context), { 25f }, Field.entries.toSet(), { null }, {}, {})

    @Ears("US-69.3")
    @Test fun us_69_3_library_list_at_200_percent() {
        rule.setContent {
            Large(tall = true) {
                LibraryItems(videos.toFolders(), videos, Layout.List, ctx(), rememberLazyListState(), rememberLazyGridState())
            }
        }
        rule.waitForIdle()
        evidenceShot("library-list-200")
        assertNoClippedText("library list")
    }

    @Ears("US-69.3")
    @Test fun us_69_3_library_home_at_200_percent() {
        val dao = MemoryDao().apply { rows.value = videos.associateBy { it.id } }
        val (vm, index) = testViewModel(dao) { videos }
        vm.onAccess(MediaAccess.Full, false)
        val loaders = testLoaders(context, index)
        rule.setContent {
            val state by vm.state.collectAsState()
            Large(tall = false) { LibraryHome(state, vm, loaders, {}, { _, _ -> }, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}) }
        }
        rule.waitUntil(5_000) { rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "folder-row")).fetchSemanticsNodes().isNotEmpty() }
        evidenceShot("library-200")
        assertNoClippedText("library home")
    }

    @Ears("US-69.3")
    @Test fun us_69_3_general_settings_at_200_percent() {
        runBlocking { GeneralSettings.reset(context) }
        rule.setContent { Large(tall = true) { GeneralSettings.Content(null) } }
        rule.waitForIdle()
        evidenceShot("general-settings-200")
        assertNoClippedText("general settings")
    }

    @Ears("US-69.3")
    @Test fun us_69_3_library_settings_at_200_percent() {
        rule.setContent { Large(tall = true) { LibrarySettings.Content(null) } }
        rule.waitForIdle()
        evidenceShot("library-settings-200")
        assertNoClippedText("library settings")
    }

    @Ears("US-69.3")
    @Test fun us_69_3_folder_settings_at_200_percent() {
        val dao = MemoryDao().apply { rows.value = videos.associateBy { it.id } }
        val (vm, _) = testViewModel(dao) { videos }
        vm.onAccess(MediaAccess.Full, false)
        rule.setContent {
            val state by vm.state.collectAsState()
            Large(tall = true) { FolderSettingsScreen(state as? LibraryUiState.Content, vm, onBack = {}) }
        }
        rule.waitUntil(5_000) { rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "folder-settings")).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        evidenceShot("folder-settings-200")
        assertNoClippedText("folder settings")
    }
}
