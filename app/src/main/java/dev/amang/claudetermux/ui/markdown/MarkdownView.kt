package dev.amang.claudetermux.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.amang.claudetermux.ui.theme.ExtraColors
import dev.amang.claudetermux.ui.theme.extraColors

@Composable
fun MarkdownView(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { Markdown.parse(markdown) }
    Blocks(blocks, modifier)
}

@Composable
private fun Blocks(blocks: List<Block>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { BlockView(it) }
    }
}

@Composable
private fun BlockView(block: Block) {
    val extra = extraColors
    val body = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onBackground)
    when (block) {
        is Block.Paragraph -> Text(block.inlines.toAnnotated(extra, MaterialTheme.colorScheme.primary), style = body)
        is Block.Heading -> {
            val size = when (block.level) {
                1 -> 24.sp
                2 -> 21.sp
                3 -> 19.sp
                else -> 17.sp
            }
            Text(
                block.inlines.toAnnotated(extra, MaterialTheme.colorScheme.primary),
                style = body.copy(fontSize = size, lineHeight = size * 1.3f, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        is Block.Code -> CodeBlock(block.language, block.text)
        is Block.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            block.items.forEachIndexed { index, item ->
                Row {
                    Text(
                        if (block.ordered) "${block.start + index}." else "•",
                        style = body,
                        modifier = Modifier.width(if (block.ordered) 30.dp else 22.dp).padding(start = 6.dp),
                    )
                    Blocks(item, Modifier.weight(1f))
                }
            }
        }
        is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(extra.composerBorder, RoundedCornerShape(2.dp)))
            Blocks(block.blocks, Modifier.padding(start = 12.dp))
        }
        is Block.Table -> TableView(block, body)
        Block.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(extra.divider))
    }
}

@Composable
fun CodeBlock(language: String, code: String, modifier: Modifier = Modifier) {
    val extra = extraColors
    val clipboard = LocalClipboardManager.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(extra.codeBackground)
            .border(1.dp, extra.divider, RoundedCornerShape(10.dp)),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(language.ifBlank { "text" }, color = extra.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy code", tint = extra.muted, modifier = Modifier.size(16.dp))
            }
        }
        Text(
            code,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onBackground,
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun TableView(table: Block.Table, body: TextStyle) {
    val extra = extraColors
    val link = MaterialTheme.colorScheme.primary
    val style = body.copy(fontSize = 15.sp, lineHeight = 21.sp)
    // Compose has no table layout; size columns from their longest cell instead.
    val widths = remember(table) {
        List(table.header.size) { c ->
            val longest = (listOf(table.header[c]) + table.rows.map { it.getOrElse(c) { emptyList() } })
                .maxOf { cell -> cell.sumOf { it.text.length } }
            (longest * 8 + 28).coerceIn(72, 260).dp
        }
    }
    Column(
        Modifier
            .horizontalScroll(rememberScrollState())
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, extra.divider, RoundedCornerShape(8.dp)),
    ) {
        (listOf(table.header) + table.rows).forEachIndexed { r, row ->
            if (r > 0) Box(Modifier.width(widths.fold(0.dp) { a, b -> a + b }).height(1.dp).background(extra.divider))
            Row(if (r == 0) Modifier.background(extra.inlineCodeBackground) else Modifier) {
                row.forEachIndexed { c, cell ->
                    Text(
                        cell.toAnnotated(extra, link),
                        style = if (r == 0) style.copy(fontWeight = FontWeight.SemiBold) else style,
                        modifier = Modifier.width(widths[c]).padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

fun List<Inline>.toAnnotated(extra: ExtraColors, linkColor: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    for (span in this@toAnnotated) {
        val style = SpanStyle(
            fontWeight = if (span.bold) FontWeight.SemiBold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            textDecoration = if (span.strike) TextDecoration.LineThrough else null,
            fontFamily = if (span.code) FontFamily.Monospace else null,
            fontSize = if (span.code) 0.9.em else androidx.compose.ui.unit.TextUnit.Unspecified,
            color = if (span.code) extra.inlineCode else androidx.compose.ui.graphics.Color.Unspecified,
            background = if (span.code) extra.inlineCodeBackground else androidx.compose.ui.graphics.Color.Unspecified,
        )
        if (span.link != null) {
            val linkStyles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
            withLink(LinkAnnotation.Url(span.link, linkStyles)) { withStyle(style) { append(span.text) } }
        } else {
            withStyle(style) { append(span.text) }
        }
    }
}
