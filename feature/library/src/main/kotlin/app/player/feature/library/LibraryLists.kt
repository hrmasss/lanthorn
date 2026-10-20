package app.player.feature.library

import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.player.core.data.library.LibraryFolder
import app.player.core.data.library.LibraryVideo
import app.player.core.designsystem.component.VideoThumbnail
import app.player.core.designsystem.icon.LanthornIcons
import app.player.core.designsystem.theme.PlayerColors
import app.player.core.designsystem.theme.tabular

/** One entry in a row's menu. */
internal class RowAction<T>(@param:StringRes val label: Int, val onClick: (T) -> Unit)

/** Everything a row needs besides its item. One instance per screen. */
internal class RowContext(
    val loader: ThumbnailLoader,
    val fps: suspend (LibraryVideo) -> Float,
    val fields: Set<Field>,
    val volumeLabel: (String) -> String?,
    val onFolder: (LibraryFolder) -> Unit,
    val onVideo: (LibraryVideo) -> Unit,
    val folderActions: List<RowAction<LibraryFolder>> = emptyList(),
    val videoActions: List<RowAction<LibraryVideo>> = emptyList(),
)

/** The per-row menu button. */
@Composable
private fun <T> RowMenu(item: T, actions: List<RowAction<T>>, name: String) {
    if (actions.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("row-menu")) {
            Icon(LanthornIcons.More, contentDescription = stringResource(R.string.row_menu, name))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (action in actions) {
                DropdownMenuItem(
                    text = { Text(stringResource(action.label)) },
                    onClick = {
                        open = false
                        action.onClick(item)
                    },
                )
            }
        }
    }
}

/**
 * Folders first, then videos, as a list or a grid. Rows have fixed heights and thumbnails fixed
 * frames, so nothing moves when a thumbnail arrives (US-02.3).
 */
@Composable
internal fun LibraryItems(
    folders: List<LibraryFolder>,
    videos: List<LibraryVideo>,
    layout: Layout,
    ctx: RowContext,
    listState: LazyListState,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
) {
    val padding = PaddingValues(top = 8.dp, bottom = 96.dp)
    when (layout) {
        Layout.List -> LazyColumn(modifier.fillMaxSize().testTag("library-list"), listState, padding) {
            items(folders, key = { "f:" + it.key }, contentType = { "folder" }) { FolderRow(it, ctx) }
            items(videos, key = { "v:" + it.id }, contentType = { "video" }) { VideoRow(it, ctx) }
        }
        Layout.Grid -> LazyVerticalGrid(
            GridCells.Adaptive(160.dp),
            modifier.fillMaxSize().testTag("library-grid"),
            gridState,
            PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(folders, key = { "f:" + it.key }, contentType = { "folder" }) { FolderCell(it, ctx) }
            items(videos, key = { "v:" + it.id }, contentType = { "video" }) { VideoCell(it, ctx) }
        }
    }
}

@Composable
private fun FolderRow(folder: LibraryFolder, ctx: RowContext) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { ctx.onFolder(folder) }
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("folder-row"),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FolderThumb(folder, ctx, Modifier.width(128.dp))
        Column(Modifier.weight(1f)) {
            Text(
                folder.name.ifEmpty { stringResource(R.string.library_storage_root) },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                folderSubtitle(folder, ctx),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        RowMenu(folder, ctx.folderActions, folder.name)
    }
}

@Composable
private fun FolderCell(folder: LibraryFolder, ctx: RowContext) {
    Column(
        Modifier.clip(MaterialTheme.shapes.medium).clickable { ctx.onFolder(folder) }.testTag("folder-cell"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FolderThumb(folder, ctx, Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    folder.name.ifEmpty { stringResource(R.string.library_storage_root) },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    folderSubtitle(folder, ctx),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            RowMenu(folder, ctx.folderActions, folder.name)
        }
    }
}

@Composable
private fun folderSubtitle(folder: LibraryFolder, ctx: RowContext): String {
    val count = pluralStringResource(R.plurals.library_video_count, folder.videoCount, folder.videoCount)
    return listOfNotNull(ctx.volumeLabel(folder.volume), count).joinToString(" · ")
}

@Composable
private fun VideoRow(video: LibraryVideo, ctx: RowContext) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = video.available) { ctx.onVideo(video) }
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .unavailable(video)
            .testTag("video-row"),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VideoThumb(video, ctx, Modifier.width(128.dp))
        Column(Modifier.weight(1f)) {
            Text(
                displayName(video.name),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            VideoDetails(video, ctx)
        }
        RowMenu(video, ctx.videoActions, video.name)
    }
}

@Composable
private fun VideoCell(video: LibraryVideo, ctx: RowContext) {
    Column(
        Modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = video.available) { ctx.onVideo(video) }
            .unavailable(video)
            .testTag("video-cell"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        VideoThumb(video, ctx, Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    displayName(video.name),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    minLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                VideoDetails(video, ctx)
            }
            RowMenu(video, ctx.videoActions, video.name)
        }
    }
}

private fun Modifier.unavailable(video: LibraryVideo) = if (video.available) this else alpha(0.38f)

/** The fields line (US-02.2), up to two lines so five fields fit at any font scale. Shows "unavailable" for a video on removed storage (US-80.3). */
@Composable
private fun VideoDetails(video: LibraryVideo, ctx: RowContext) {
    val context = LocalContext.current
    val fps by produceState(0f, video.id, video.modifiedSec, Field.FrameRate in ctx.fields) {
        if (Field.FrameRate in ctx.fields && video.available) value = ctx.fps(video)
    }
    val parts = buildList {
        if (!video.available) add(stringResource(R.string.library_unavailable))
        ctx.volumeLabel(video.volume)?.let(::add)
        for (field in Field.entries) {
            if (field !in ctx.fields) continue
            when (field) {
                Field.Size -> add(formatSize(video.sizeBytes))
                Field.Resolution -> formatResolution(video.width, video.height)?.let(::add)
                Field.FrameRate -> formatFps(fps)?.let(::add)
                Field.Date -> add(DateUtils.formatDateTime(context, video.modifiedSec * 1000, DateUtils.FORMAT_SHOW_DATE))
                Field.Extension -> extensionOf(video.name)?.let(::add)
            }
        }
    }
    if (parts.isNotEmpty()) {
        Text(
            parts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall.tabular(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("video-fields"),
        )
    }
}

@Composable
private fun VideoThumb(video: LibraryVideo, ctx: RowContext, modifier: Modifier) {
    VideoThumbnail(duration = formatDuration(video.durationMs), modifier = modifier) {
        ThumbImage(video, ctx.loader)
    }
}

@Composable
private fun FolderThumb(folder: LibraryFolder, ctx: RowContext, modifier: Modifier) {
    Box(
        modifier
            .aspectRatio(16f / 9f)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        ThumbImage(folder.cover, ctx.loader)
        Row(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .background(PlayerColors.Scrim, CircleShape)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(LibraryIcons.Folder, contentDescription = null, tint = PlayerColors.Content, modifier = Modifier.size(14.dp))
            Text(
                folder.videoCount.toString(),
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = PlayerColors.Content,
            )
        }
    }
}

/** Draws the cached bitmap at once if memory has it, otherwise loads it while the row is on screen. */
@Composable
private fun ThumbImage(video: LibraryVideo, loader: ThumbnailLoader) {
    var bitmap by remember(video.id, video.modifiedSec) { mutableStateOf<Bitmap?>(loader.cached(video)) }
    if (bitmap == null) {
        LaunchedEffect(video.id, video.modifiedSec) { bitmap = loader.load(video) }
    }
    bitmap?.let {
        Image(
            it.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
