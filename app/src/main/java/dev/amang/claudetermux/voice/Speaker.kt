package dev.amang.claudetermux.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.amang.claudetermux.ui.markdown.Block
import dev.amang.claudetermux.ui.markdown.Inline
import dev.amang.claudetermux.ui.markdown.Markdown

/** Reads assistant replies aloud with the phone's text-to-speech engine. */
class Speaker(context: Context) {
    /** Key of the message being read, or null when silent. */
    var speakingKey by mutableStateOf<String?>(null)
        private set

    private var ready = false
    private var queued: Pair<String, String>? = null
    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        queued?.let { (key, text) -> if (ready) start(key, text) }
        queued = null
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                if (utteranceId?.endsWith(":last") == true) speakingKey = null
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                speakingKey = null
            }
        })
    }

    fun toggle(key: String, markdown: String) {
        if (speakingKey == key) {
            stop()
            return
        }
        val text = plainText(markdown)
        if (text.isBlank()) return
        if (ready) start(key, text) else queued = key to text
    }

    private fun start(key: String, text: String) {
        tts.stop()
        speakingKey = key
        // The engine rejects very long inputs, so feed it sentence-sized pieces.
        val chunks = text.chunked(TextToSpeech.getMaxSpeechInputLength().coerceAtMost(3000))
        chunks.forEachIndexed { i, chunk ->
            val id = "$key:$i" + if (i == chunks.lastIndex) ":last" else ""
            tts.speak(chunk, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    fun stop() {
        tts.stop()
        speakingKey = null
    }

    fun shutdown() = tts.shutdown()

    companion object {
        /** Markdown reduced to what should be spoken: no code blocks, tables or markup. */
        fun plainText(markdown: String): String = Markdown.parse(markdown).joinToString("\n") { it.spoken() }

        private fun List<Inline>.spoken() = joinToString("") { it.text }

        private fun Block.spoken(): String = when (this) {
            is Block.Heading -> inlines.spoken() + "."
            is Block.Paragraph -> inlines.spoken()
            is Block.Bullets -> items.joinToString("\n") { item -> item.joinToString(" ") { it.spoken() } }
            is Block.Quote -> blocks.joinToString(" ") { it.spoken() }
            is Block.Code -> "Code block."
            is Block.Table -> "Table."
            Block.Rule -> ""
        }
    }
}
