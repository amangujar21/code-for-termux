package dev.amang.claudetermux

import dev.amang.claudetermux.chat.ChatItem
import dev.amang.claudetermux.chat.ChatReducer
import dev.amang.claudetermux.chat.ChatState
import dev.amang.claudetermux.chat.RunState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReducerTest {
    private fun json(text: String) = Json.parseToJsonElement(text.trimIndent()) as JsonObject
    private fun sdk(message: String) = json("""{"type":"sdk","message":$message}""")
    private fun ChatState.feed(vararg events: JsonObject) =
        events.foldIndexed(this) { i, state, event -> ChatReducer.reduce(state, state.lastSeq + 1 + i * 0, event) }

    @Test
    fun `streamed text is shown live and replaced by the final message`() {
        var state = ChatState().feed(
            json("""{"type":"user.echo","text":"hi","attachments":[]}"""),
            json("""{"type":"state","state":"running","model":"opus","mode":"default","effort":"medium"}"""),
            sdk("""{"type":"stream_event","parent_tool_use_id":null,"event":{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}}"""),
            sdk("""{"type":"stream_event","parent_tool_use_id":null,"event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}}"""),
            sdk("""{"type":"stream_event","parent_tool_use_id":null,"event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}}"""),
        )
        assertEquals(RunState.Running, state.runState)
        assertEquals("medium", state.effort)
        val live = state.visibleItems.last() as ChatItem.Text
        assertEquals("Hello", live.markdown)
        assertTrue(live.streaming)

        state = state.feed(
            sdk("""{"type":"assistant","uuid":"a1","parent_tool_use_id":null,"message":{"content":[{"type":"text","text":"Hello!"}]}}"""),
            sdk("""{"type":"result","subtype":"success","result":"Hello!"}"""),
            json("""{"type":"state","state":"idle","model":"opus","mode":"default"}"""),
        )
        assertEquals(listOf("hi", "Hello!"), state.visibleItems.map { (it as? ChatItem.User)?.text ?: (it as ChatItem.Text).markdown })
        assertFalse((state.visibleItems.last() as ChatItem.Text).streaming)
        assertEquals(RunState.Idle, state.runState)
        assertEquals(1, state.turnsCompleted)
        assertNull(state.effort)
    }

    @Test
    fun `tool results attach to their call and subagent chatter is hidden`() {
        val state = ChatState().feed(
            sdk("""{"type":"assistant","uuid":"a1","parent_tool_use_id":null,"message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}]}}"""),
            sdk("""{"type":"assistant","uuid":"a2","parent_tool_use_id":"t1","message":{"content":[{"type":"text","text":"inner"}]}}"""),
            sdk("""{"type":"user","parent_tool_use_id":null,"message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","is_error":true,"content":[{"type":"text","text":"boom"}]}]}}"""),
        )
        val tool = state.items.single() as ChatItem.Tool
        assertEquals("boom", tool.result)
        assertTrue(tool.isError && tool.done)
    }

    @Test
    fun `permission cards resolve in place`() {
        val state = ChatState().feed(
            json("""{"type":"permission.request","requestId":"r1","toolName":"Write","input":{"file_path":"a"},"suggestions":[{"type":"addRules"}]}"""),
            json("""{"type":"permission.resolved","requestId":"r1","behavior":"allow"}"""),
        )
        val card = state.items.single() as ChatItem.Permission
        assertTrue(card.canAlwaysAllow)
        assertEquals("allow", card.outcome)
    }

    @Test
    fun `events at or below the last sequence are ignored`() {
        val echo = json("""{"type":"user.echo","text":"once","attachments":[]}""")
        val once = ChatReducer.reduce(ChatState(), 5, echo)
        assertEquals(once, ChatReducer.reduce(once, 5, echo))
        assertEquals(once, ChatReducer.reduce(once, 3, echo))
    }

    @Test
    fun `history hides command plumbing and closes unfinished tools`() {
        val history = listOf(
            json("""{"type":"user","uuid":"u1","message":{"role":"user","content":"<command-name>/clear</command-name>"}}"""),
            json("""{"type":"user","uuid":"u2","message":{"role":"user","content":[{"type":"text","text":"real question"},{"type":"image","source":{}}]}}"""),
            json("""{"type":"assistant","uuid":"a1","message":{"content":[{"type":"thinking","thinking":"hmm"},{"type":"tool_use","id":"t9","name":"Read","input":{"file_path":"/x/y.kt"}}]}}"""),
            json("""{"type":"user","uuid":"u3","message":{"role":"user","content":"[Request interrupted by user]"}}"""),
        )
        val items = ChatReducer.fromHistory(history).items
        assertEquals(listOf("User", "Thinking", "Tool", "Notice"), items.map { it::class.simpleName })
        assertEquals(listOf("image"), (items[0] as ChatItem.User).attachments)
        assertTrue((items[2] as ChatItem.Tool).done)
    }

    @Test
    fun `a failed turn surfaces its error`() {
        val state = ChatState().feed(sdk("""{"type":"result","subtype":"error_during_execution","errors":["rate limited"]}"""))
        val notice = state.items.single() as ChatItem.Notice
        assertTrue(notice.isError)
        assertEquals("rate limited", notice.text)
    }
}
