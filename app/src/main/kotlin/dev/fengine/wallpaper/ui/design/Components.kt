package dev.fengine.wallpaper.ui.design

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.Size as AndroidSize
import dev.fengine.wallpaper.smart.Images
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The component vocabulary. Every screen is composed from these and nothing else at the layout
 * level; Material 3 controls are used directly only inside them. See docs/design-system.md.
 */

/** A scrolling editorial page: page gutter, top breathing room, bottom overscroll space. */
@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) {
    val spec = currentLayoutSpec()
    CompositionLocalProvider(LocalLayout provides spec) {
        Scaffold { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                Column(
                    modifier = Modifier
                        .widthIn(max = spec.maxContentWidth)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Space.page),
                ) {
                    Spacer(Modifier.height(Space.xl))
                    content()
                    Spacer(Modifier.height(Space.xxxl))
                }
            }
        }
    }
}

/** Small caps label above a section. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.secondary,
        modifier = modifier,
    )
}

/** Section = eyebrow, fixed gap, content, section gap after. */
@Composable
fun Section(eyebrow: String, trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (trailing == null) {
            Eyebrow(eyebrow)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) { Eyebrow(eyebrow); trailing() }
        }
        Spacer(Modifier.height(Space.eyebrow))
        content()
        Spacer(Modifier.height(Space.section))
    }
}

/** The page's one serif headline, with an optional one-line caption under it. */
@Composable
fun Headline(text: String, caption: String? = null) {
    Text(text, style = MaterialTheme.typography.displaySmall)
    caption?.let {
        Spacer(Modifier.height(Space.sm))
        Caption(it)
    }
}

@Composable
fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
fun BodyMuted(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Text-only action; the page has at most one filled button, everything else is this. */
@Composable
fun TextAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label) }
}

/** A labelled switch on one line: title on the left, the control on the right. */
@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Back on the left, a destructive or secondary action on the right. */
@Composable
fun ActionBar(leading: Pair<String, () -> Unit>, trailing: Pair<String, () -> Unit>? = null) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextAction(leading.first, leading.second)
        trailing?.let { TextAction(it.first, it.second) }
    }
}

/** A row of single-choice chips; wraps on narrow screens instead of clipping. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipRow(options: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        options.forEach { (value, label) ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

/** Rounded surface with a title, a body and at most one text action. Used for status and guidance. */
@Composable
fun StatusCard(title: String, body: String, tone: Color = MaterialTheme.colorScheme.surfaceVariant, action: Pair<String, () -> Unit>? = null) {
    Surface(color = tone, shape = RoundedCornerShape(Radius.card), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Space.lg)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Space.xs))
            Text(body, style = MaterialTheme.typography.bodyMedium)
            action?.let {
                Spacer(Modifier.height(Space.md))
                TextAction(it.first, it.second)
            }
        }
    }
}

/** Tiles a page shows before "and N more": a described group can hold thousands, the page scrolls as a column. */
const val PhotoGridLimit = 60

/**
 * The photo grid: [LayoutSpec.columns] square tiles per row, every tile the same width. Rows are
 * explicit so a loaded thumbnail's intrinsic size can never change how many tiles share a row
 * (FlowRow breaks lines on intrinsic width, which made a four-photo grid render two half-width
 * tiles). The last row is padded so its tiles stay the same width as a full row.
 */
@Composable
fun PhotoGrid(
    uris: List<String>,
    badge: ((String) -> String?)? = null,
    onRemove: ((String) -> Unit)? = null,
    onClick: ((String) -> Unit)? = null,
    limit: Int = PhotoGridLimit,
) {
    val columns = LocalLayout.current.columns
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Grid.gap)) {
        uris.take(limit).chunked(columns).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Grid.gap)) {
                row.forEach { uri ->
                    PhotoTile(
                        uri = uri,
                        badge = badge?.invoke(uri),
                        onRemove = onRemove?.let { f -> { f(uri) } },
                        onClick = onClick?.let { f -> { f(uri) } },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** One square photo tile with an optional corner badge and remove button. */
@Composable
fun PhotoTile(
    uri: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    onRemove: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(Radius.tile))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        PhotoImage(uri, Modifier.fillMaxSize(), contentDescription = "Photo")
        badge?.let { Badge(it, Modifier.align(Alignment.BottomStart).padding(Space.sm)) }
        onRemove?.let {
            // A small disc the eye reads as "x", inside a tap target that still meets the 44 dp minimum.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(Size.tileAction)
                    .clickable(onClick = it),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(Size.tileActionDisc)
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Remove photo", tint = Color.White, modifier = Modifier.size(Size.tileIcon))
                }
            }
        }
    }
}

/**
 * A transient choice. COMPACT lane: a bottom sheet. WIDE lane: a centred card of at most
 * [Size.sheetCardWidth], so an unfolded phone never shows a choice stretched across the hinge.
 * Content lays itself out in a column with the page gutter; it must not add its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    when (currentLayoutSpec().lane) {
        Lane.COMPACT -> ModalBottomSheet(onDismissRequest = onDismiss) {
            Column(modifier = Modifier.padding(horizontal = Space.page)) {
                content()
                Spacer(Modifier.height(Space.xl))
            }
        }
        Lane.WIDE -> Dialog(onDismissRequest = onDismiss) {
            Surface(
                shape = RoundedCornerShape(Radius.card),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.widthIn(max = Size.sheetCardWidth).fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(horizontal = Space.page, vertical = Space.xl)) { content() }
            }
        }
    }
}

/** Pill label over imagery. Always white on a scrim so it reads on any photo. */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = modifier
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f), CircleShape)
            .padding(horizontal = Space.md, vertical = Space.xs),
    )
}

/** Portrait cover with title and caption, for the group rail. */
@Composable
fun CoverCard(title: String, caption: String, coverUri: String?, badge: String?, onClick: () -> Unit) {
    Column(modifier = Modifier.width(LocalLayout.current.coverWidth).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(Size.coverAspect)
                .clip(RoundedCornerShape(Radius.card))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            coverUri?.let { PhotoImage(it, Modifier.fillMaxSize(), contentDescription = null) }
            badge?.let { Badge(it, Modifier.align(Alignment.BottomStart).padding(Space.md)) }
        }
        Spacer(Modifier.height(Space.sm))
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Caption(caption)
    }
}

/** The "+" cover that ends the rail. */
@Composable
fun NewCoverCard(title: String, onClick: () -> Unit) {
    Column(modifier = Modifier.width(LocalLayout.current.coverWidth).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(Size.coverAspect)
                .clip(RoundedCornerShape(Radius.card))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text("+", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.height(Space.sm))
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

/** Horizontal rail padding so the last card can scroll clear of the gutter. */
val RailPadding = PaddingValues(end = Space.page)

/**
 * Thumbnail loader shared by tiles and covers: ContentResolver.loadThumbnail (API 29+), which
 * the picker and media providers serve without a full decode. A lost grant renders as a plain
 * surface instead of crashing.
 */
@Composable
fun PhotoImage(uri: String, modifier: Modifier = Modifier, contentDescription: String?) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = uri) {
        value = withContext(Dispatchers.IO) {
            // The system thumbnail road fails for Google Photos cloud items; decode a small copy instead.
            loadThumbnail(context.contentResolver, Uri.parse(uri))
                ?: runCatching { Images.decode(context, Uri.parse(uri), THUMB_EDGE).asImageBitmap() }
                    .onFailure { Log.w("PhotoImage", "no thumbnail for $uri", it) }.getOrNull()
        }
    }
    bitmap?.let {
        Image(bitmap = it, contentDescription = contentDescription, contentScale = ContentScale.Crop, modifier = modifier)
    }
}

private const val THUMB_EDGE = 320

private fun loadThumbnail(resolver: ContentResolver, uri: Uri): ImageBitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null // Android 9: the decode fallback below handles it
    return runCatching { resolver.loadThumbnail(uri, AndroidSize(THUMB_EDGE, THUMB_EDGE), null).asImageBitmap() }.getOrNull()
}
