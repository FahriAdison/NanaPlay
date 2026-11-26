package com.opencloudgaming.opennow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencloudgaming.opennow.ui.theme.OpenNowPalette

/**
 * 1.0.109: Clean Compose renderer for release notes markdown without raw symbols.
 * Parses headings (##, ###), bullet points (-, *), and inline bold (**) or code (`) into
 * crisp Compose typography with NanaPlay's cyan accent.
 */
@Composable
fun FormattedReleaseNotes(
    notes: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    accentColor: Color = OpenNowPalette.AccentDefault,
) {
    val lines = remember(notes) {
        notes.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (line in lines) {
            when {
                line.startsWith("### ") -> {
                    val content = line.removePrefix("### ").trim()
                    Text(
                        text = parseMarkdownInline(content),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                line.startsWith("## ") -> {
                    val content = line.removePrefix("## ").trim()
                    Text(
                        text = parseMarkdownInline(content),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                line.startsWith("# ") -> {
                    val content = line.removePrefix("# ").trim()
                    Text(
                        text = parseMarkdownInline(content),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    val content = line.substring(2).trim()
                    Row(
                        modifier = Modifier.padding(start = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "•",
                            color = accentColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                        Text(
                            text = parseMarkdownInline(content),
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor,
                        )
                    }
                }
                else -> {
                    Text(
                        text = parseMarkdownInline(line),
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor,
                    )
                }
            }
        }
    }
}

/**
 * Parses inline bold `**text**` and inline code `` `text` `` into an AnnotatedString
 * with FontWeight.Bold for bold and clean stripped text for backticks.
 */
internal fun parseMarkdownInline(text: String): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        val len = text.length
        while (i < len) {
            if (i + 1 < len && text[i] == '*' && text[i + 1] == '*') {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    val boldText = text.substring(i + 2, end)
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(boldText)
                    pop()
                    i = end + 2
                    continue
                }
            } else if (text[i] == '`') {
                val end = text.indexOf('`', i + 1)
                if (end != -1) {
                    val codeText = text.substring(i + 1, end)
                    pushStyle(SpanStyle(fontWeight = FontWeight.Medium))
                    append(codeText)
                    pop()
                    i = end + 1
                    continue
                }
            }
            append(text[i])
            i++
        }
    }
}
