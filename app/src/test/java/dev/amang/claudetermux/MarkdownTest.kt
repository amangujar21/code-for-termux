package dev.amang.claudetermux

import dev.amang.claudetermux.bridge.SlashCommand
import dev.amang.claudetermux.ui.chat.matchCommands
import dev.amang.claudetermux.ui.markdown.Block
import dev.amang.claudetermux.ui.markdown.Inline
import dev.amang.claudetermux.ui.markdown.Markdown
import dev.amang.claudetermux.voice.Speaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    @Test
    fun `inline marks`() {
        assertEquals(
            listOf(
                Inline("Progress:", bold = true),
                Inline(" the "),
                Inline("status bar", code = true),
                Inline(" is "),
                Inline("new", italic = true),
                Inline(", see "),
                Inline("docs", link = "https://example.com/a_b"),
            ),
            Markdown.parseInline("**Progress:** the `status bar` is *new*, see [docs](https://example.com/a_b)"),
        )
    }

    @Test
    fun `unfinished or literal markers stay as text`() {
        assertEquals(listOf(Inline("a **bold start and snake_case_name and 2 * 3")), Markdown.parseInline("a **bold start and snake_case_name and 2 * 3"))
        assertEquals(listOf(Inline("x "), Inline("a*b", code = true)), Markdown.parseInline("x `a*b`"))
    }

    @Test
    fun `bare urls become links without trailing punctuation`() {
        val spans = Markdown.parseInline("See https://example.com/x.")
        assertEquals(Inline("https://example.com/x", link = "https://example.com/x"), spans[1])
        assertEquals(Inline("."), spans[2])
    }

    @Test
    fun `block structure`() {
        val blocks = Markdown.parse(
            """
            # Title
            Intro line
            continues here.

            - one
              - nested
            - two

            1. first
            2. second

            ```kotlin
            val x = 1
            ```
            > quoted

            | A | B |
            |---|:-:|
            | 1 | `2|3` |

            ---
            """.trimIndent(),
        )
        assertEquals(
            listOf("Heading", "Paragraph", "Bullets", "Bullets", "Code", "Quote", "Table", "Rule"),
            blocks.map { it::class.simpleName },
        )
        val bullets = blocks[2] as Block.Bullets
        assertEquals(2, bullets.items.size)
        assertTrue(bullets.items[0][1] is Block.Bullets)
        assertEquals(Block.Code("kotlin", "val x = 1"), blocks[4])
        val table = blocks[6] as Block.Table
        assertEquals(listOf(Inline("2|3", code = true)), table.rows[0][1])
        assertTrue((blocks[3] as Block.Bullets).ordered)
    }

    @Test
    fun `an unclosed fence while streaming is still a code block`() {
        assertEquals(listOf(Block.Code("sh", "echo hi\nls")), Markdown.parse("```sh\necho hi\nls"))
    }

    @Test
    fun `spoken text drops markup and code`() {
        assertEquals("Title.\nRun the tool now.\nCode block.", Speaker.plainText("## Title\nRun the `tool` **now**.\n```\nx\n```"))
    }

    @Test
    fun `slash matching prefers prefixes and stops at whitespace`() {
        val commands = listOf(SlashCommand("compact"), SlashCommand("model"), SlashCommand("mcp"), SlashCommand("remodel"))
        assertEquals(listOf("model", "mcp", "compact", "remodel"), matchCommands(commands, "/m").map { it.name })
        assertEquals(listOf("model", "remodel"), matchCommands(commands, "/mod").map { it.name })
        assertTrue(matchCommands(commands, "/model opus").isEmpty())
        assertTrue(matchCommands(commands, "hello").isEmpty())
    }
}
