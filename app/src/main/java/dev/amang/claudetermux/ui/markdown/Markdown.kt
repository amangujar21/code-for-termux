package dev.amang.claudetermux.ui.markdown

/**
 * Small Markdown parser covering what Claude Code emits: headings, paragraphs, nested lists,
 * fenced code, quotes, tables, rules and the usual inline marks. It tolerates half-written input
 * (an unclosed fence or emphasis) because it re-runs on every streamed token.
 */
sealed interface Block {
    data class Heading(val level: Int, val inlines: List<Inline>) : Block
    data class Paragraph(val inlines: List<Inline>) : Block
    data class Code(val language: String, val text: String) : Block
    data class Bullets(val ordered: Boolean, val start: Int, val items: List<List<Block>>) : Block
    data class Quote(val blocks: List<Block>) : Block
    data class Table(val header: List<List<Inline>>, val rows: List<List<List<Inline>>>) : Block
    data object Rule : Block
}

data class Inline(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val link: String? = null,
)

object Markdown {
    private val heading = Regex("""^ {0,3}(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val rule = Regex("""^ {0,3}([-*_])( *\1){2,} *$""")
    private val listItem = Regex("""^(\s*)([-*+]|\d{1,9}[.)])(\s+)(.*)$""")
    private val fence = Regex("""^ {0,3}(`{3,}|~{3,})\s*([^`\s]*).*$""")
    private val tableDivider = Regex("""^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$""")
    private val bareUrl = Regex("""^https?://[^\s<>)\]]+""")

    fun parse(source: String): List<Block> = parseBlocks(source.replace("\r\n", "\n").split('\n'))

    private fun parseBlocks(lines: List<String>): List<Block> {
        val out = ArrayList<Block>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                i++
                continue
            }
            val fenceMatch = fence.matchEntire(line)
            if (fenceMatch != null) {
                val marker = fenceMatch.groupValues[1]
                val body = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].trim().let { it.startsWith(marker) && it.all { c -> c == marker[0] } }) {
                    body += lines[i]
                    i++
                }
                i++ // closing fence, or past the end while still streaming
                out += Block.Code(fenceMatch.groupValues[2], body.joinToString("\n"))
                continue
            }
            val headingMatch = heading.matchEntire(line)
            if (headingMatch != null) {
                out += Block.Heading(headingMatch.groupValues[1].length, parseInline(headingMatch.groupValues[2]))
                i++
                continue
            }
            if (rule.matches(line)) {
                out += Block.Rule
                i++
                continue
            }
            if (line.trimStart().startsWith(">")) {
                val quoted = ArrayList<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    quoted += lines[i].trimStart().removePrefix(">").removePrefix(" ")
                    i++
                }
                out += Block.Quote(parseBlocks(quoted))
                continue
            }
            if (line.contains('|') && i + 1 < lines.size && tableDivider.matches(lines[i + 1]) && lines[i + 1].contains('-')) {
                val header = splitRow(line)
                val rows = ArrayList<List<List<Inline>>>()
                i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    rows += splitRow(lines[i]).let { cells -> List(header.size) { c -> cells.getOrElse(c) { emptyList() } } }
                    i++
                }
                out += Block.Table(header, rows)
                continue
            }
            val itemMatch = listItem.matchEntire(line)
            if (itemMatch != null) {
                val (block, next) = parseList(lines, i, itemMatch)
                out += block
                i = next
                continue
            }
            val paragraph = ArrayList<String>()
            while (i < lines.size && lines[i].isNotBlank() && !(paragraph.isNotEmpty() && startsBlock(lines[i]))) {
                paragraph += lines[i].trim()
                i++
            }
            out += Block.Paragraph(parseInline(paragraph.joinToString("\n")))
        }
        return out
    }

    private fun startsBlock(line: String) =
        fence.matches(line) || heading.matches(line) || rule.matches(line) ||
            line.trimStart().startsWith(">") || listItem.matches(line)

    private fun parseList(lines: List<String>, start: Int, first: MatchResult): Pair<Block, Int> {
        val baseIndent = first.groupValues[1].length
        val ordered = first.groupValues[2].first().isDigit()
        val startNumber = first.groupValues[2].takeWhile { it.isDigit() }.toIntOrNull() ?: 1
        val items = ArrayList<List<Block>>()
        var i = start
        while (i < lines.size) {
            val match = listItem.matchEntire(lines[i]) ?: break
            if (match.groupValues[1].length != baseIndent || match.groupValues[2].first().isDigit() != ordered) break
            val contentIndent = baseIndent + match.groupValues[2].length + match.groupValues[3].length.coerceAtMost(4)
            val body = arrayListOf(match.groupValues[4])
            i++
            while (i < lines.size) {
                val line = lines[i]
                if (line.isBlank()) {
                    // A blank line stays in the item only if indented content follows.
                    val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() }
                    if (next == null || next.indentWidth() <= baseIndent) break
                    body += ""
                    i++
                    continue
                }
                if (line.indentWidth() <= baseIndent) break
                body += line.drop(minOf(contentIndent, line.indentWidth()))
                i++
            }
            items += parseBlocks(body)
            while (i < lines.size && lines[i].isBlank()) {
                val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() } ?: break
                val nextItem = listItem.matchEntire(next)
                if (nextItem == null || nextItem.groupValues[1].length != baseIndent) break
                i++
            }
        }
        return Block.Bullets(ordered, startNumber, items) to i
    }

    private fun String.indentWidth() = takeWhile { it == ' ' || it == '\t' }.sumOf { c -> if (c == '\t') 4 else 1 }

    private fun splitRow(line: String): List<List<Inline>> {
        val cells = ArrayList<String>()
        val current = StringBuilder()
        var inCode = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' && i + 1 < line.length && line[i + 1] == '|' -> {
                    current.append('|')
                    i++
                }
                c == '`' -> {
                    inCode = !inCode
                    current.append(c)
                }
                c == '|' && !inCode -> {
                    cells += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        cells += current.toString()
        val trimmed = cells.map { it.trim() }.toMutableList()
        if (trimmed.firstOrNull()?.isEmpty() == true) trimmed.removeAt(0)
        if (trimmed.lastOrNull()?.isEmpty() == true) trimmed.removeAt(trimmed.lastIndex)
        return trimmed.map { parseInline(it) }
    }

    fun parseInline(source: String): List<Inline> {
        val out = ArrayList<Inline>()
        inline(source, Inline(""), out)
        return merge(out)
    }

    private fun inline(s: String, style: Inline, out: MutableList<Inline>) {
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) {
                out += style.copy(text = plain.toString())
                plain.clear()
            }
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] in "\\`*_{}[]()#+-.!|~>" -> {
                    plain.append(s[i + 1])
                    i += 2
                }
                c == '`' -> {
                    val ticks = s.countRun(i, '`')
                    val close = s.indexOf("`".repeat(ticks), i + ticks)
                    if (close < 0) {
                        plain.append(s, i, i + ticks)
                        i += ticks
                    } else {
                        flush()
                        out += style.copy(text = s.substring(i + ticks, close).replace('\n', ' ').trimCodeSpan(), code = true)
                        i = close + ticks
                    }
                }
                c == '[' -> {
                    val closeText = matchBracket(s, i)
                    val closeUrl = if (closeText > 0 && s.getOrNull(closeText + 1) == '(') s.indexOf(')', closeText + 2) else -1
                    if (closeUrl < 0) {
                        plain.append(c)
                        i++
                    } else {
                        flush()
                        val url = s.substring(closeText + 2, closeUrl).trim().substringBefore(' ')
                        inline(s.substring(i + 1, closeText), style.copy(link = url), out)
                        i = closeUrl + 1
                    }
                }
                (c == '*' || c == '_' || c == '~') -> {
                    val run = s.countRun(i, c).coerceAtMost(if (c == '~') 2 else 3)
                    val wordChar = { ch: Char? -> ch != null && ch.isLetterOrDigit() }
                    val opens = (c != '~' || run == 2) && s.getOrNull(i + run)?.isWhitespace() == false &&
                        !(c == '_' && wordChar(s.getOrNull(i - 1)))
                    val close = if (opens) findClose(s, i + run, c, run) else -1
                    if (close < 0) {
                        plain.append(s, i, i + run)
                        i += run
                    } else {
                        flush()
                        val inner = s.substring(i + run, close)
                        val nested = when {
                            c == '~' -> style.copy(strike = true)
                            run == 1 -> style.copy(italic = true)
                            run == 2 -> style.copy(bold = true)
                            else -> style.copy(bold = true, italic = true)
                        }
                        inline(inner, nested, out)
                        i = close + run
                    }
                }
                c == 'h' && style.link == null && (i == 0 || !s[i - 1].isLetterOrDigit()) -> {
                    val url = bareUrl.find(s.substring(i))?.value?.trimEnd('.', ',', ';', ':', '!', '?')
                    if (url == null) {
                        plain.append(c)
                        i++
                    } else {
                        flush()
                        out += style.copy(text = url, link = url)
                        i += url.length
                    }
                }
                else -> {
                    plain.append(c)
                    i++
                }
            }
        }
        flush()
    }

    private fun String.countRun(from: Int, c: Char): Int {
        var n = 0
        while (from + n < length && this[from + n] == c) n++
        return n
    }

    private fun String.trimCodeSpan() =
        if (length > 2 && startsWith(' ') && endsWith(' ') && isNotBlank()) substring(1, length - 1) else this

    private fun matchBracket(s: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++
                '[' -> depth++
                ']' -> if (--depth == 0) return i
            }
            i++
        }
        return -1
    }

    /** Index of a closing run of exactly [run] [c]s that is not preceded by whitespace, skipping code spans. */
    private fun findClose(s: String, from: Int, c: Char, run: Int): Int {
        var i = from
        while (i < s.length) {
            when {
                s[i] == '\\' -> i += 2
                s[i] == '`' -> {
                    val ticks = s.countRun(i, '`')
                    val close = s.indexOf("`".repeat(ticks), i + ticks)
                    i = if (close < 0) i + ticks else close + ticks
                }
                s[i] == c -> {
                    val len = s.countRun(i, c)
                    val rightFlank = !s[i - 1].isWhitespace()
                    val wordAfter = c == '_' && s.getOrNull(i + len)?.isLetterOrDigit() == true
                    if (len >= run && rightFlank && !wordAfter && i > from) return i + (len - run)
                    i += len
                }
                else -> i++
            }
        }
        return -1
    }

    private fun merge(spans: List<Inline>): List<Inline> {
        val out = ArrayList<Inline>()
        for (span in spans) {
            val last = out.lastOrNull()
            if (last != null && last.copy(text = "") == span.copy(text = "")) {
                out[out.lastIndex] = last.copy(text = last.text + span.text)
            } else if (span.text.isNotEmpty()) {
                out += span
            }
        }
        return out
    }
}
