package dev.dshpocket.core

import dev.dshpocket.protocol.Json
import kotlinx.serialization.json.Json as JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回答语言指令的单元测试。
 *
 * ### 为什么这几条必须有测试
 *
 * 指令是**我们**塞进用户消息里的内容，它同时影响三件事，每件出错都是用户可见的
 * 缺陷：
 *
 * 1. **不该出现在界面上** —— 电脑端会把 `content` 数组原样回声，若不剥掉，转写里
 *    就多出一句用户从没打过的「请始终使用简体中文回答。」
 * 2. **不该破坏回声去重** —— 去重比较的是用户原话（见 `PocketViewModel.send` 的
 *    FIFO）。若剥得不彻底，比较永远不等，于是"发一条显示两条"的老 bug 复发。
 * 3. **不该吞掉用户自己说的话** —— 如果改成 `contains` 判断，用户手打一句很相似的
 *    话就会被当成指令删掉，那是更严重的错误。
 *
 * 这三条都是纯函数行为，可以脱网、脱 Android、脱 Host 验证。
 */
class PromptDirectivesTest {

    /** 把 JSON 字面量解析成 `content` 数组，与线上帧同形。 */
    private fun parts(json: String) = JsonParser.parseToJsonElement(json)

    /**
     * 用户原话 + 指令 → 只剩用户原话。
     *
     * 这是线上的真实形状：`send` 把指令作为第二段发出，电脑端连着两段一起回声。
     */
    @Test
    fun `directive is stripped from echoed content`() {
        val content = parts(
            """
            [{"type":"text","text":"你好"},
             {"type":"text","text":"${PromptDirectives.SIMPLIFIED_CHINESE}"}]
            """.trimIndent()
        )
        assertEquals("你好", PromptDirectives.contentTextWithoutDirectives(content))
    }

    /**
     * 只关掉设置时，`content` 里只有一段，剥离不能动它。
     *
     * 反向保护：剥离逻辑不能"顺手"改动正常消息，否则关掉开关的用户会发现自己的
     * 历史被改写了。
     */
    @Test
    fun `plain content is untouched when the directive is off`() {
        val content = parts("""[{"type":"text","text":"帮我跑一下测试"}]""")
        assertEquals("帮我跑一下测试", PromptDirectives.contentTextWithoutDirectives(content))
    }

    /**
     * 用户自己打了几乎一样的话 —— 必须保留。
     *
     * 这句不是我们的常量（多了一个"请"字的位置差异与语气），所以精确全等判断
     * 必须返回 false。用 `contains` 的实现会在这里失败。
     */
    @Test
    fun `user text similar to the directive is preserved`() {
        val userSaid = "请始终使用简体中文回答（不要繁体）"
        val content = parts("""[{"type":"text","text":"$userSaid"}]""")
        assertEquals(userSaid, PromptDirectives.contentTextWithoutDirectives(content))
        assertFalse(PromptDirectives.isDirective(userSaid))
    }

    /** 常量自身必须被认出来，否则上面两条都无从谈起。 */
    @Test
    fun `constant itself is recognised as a directive`() {
        assertTrue(PromptDirectives.isDirective(PromptDirectives.SIMPLIFIED_CHINESE))
        // 前后空白是传输/裁剪产生的，不应影响判定。
        assertTrue(PromptDirectives.isDirective("  ${PromptDirectives.SIMPLIFIED_CHINESE}  "))
    }

    /**
     * 非文本段继续被跳过，而不是被编成 `[image]` 之类的占位符。
     *
     * 与 [Json.contentText] 保持同一约定：本应用渲染不了的东西就不假装渲染。
     */
    @Test
    fun `non text parts are skipped not stringified`() {
        val content = parts(
            """
            [{"type":"text","text":"看看这张图"},
             {"type":"image","mediaType":"image/png","data":"AAAA"},
             {"type":"text","text":"${PromptDirectives.SIMPLIFIED_CHINESE}"}]
            """.trimIndent()
        )
        assertEquals("看看这张图", PromptDirectives.contentTextWithoutDirectives(content))
    }

    /**
     * `null` 与空数组都返回空串，不抛异常。
     *
     * 帧解码层会把缺失字段传成 `null`，这里的宽容是契约的一部分：转写为空可以，
     * 崩掉不行。
     */
    @Test
    fun `null and empty content yield empty string`() {
        assertEquals("", PromptDirectives.contentTextWithoutDirectives(null))
        assertEquals("", PromptDirectives.contentTextWithoutDirectives(parts("[]")))
    }
}
