package dev.dshpocket.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [parseMarkdown] 与 [inline] 的行为测试。
 *
 * 这些用例全部取自这台开发机上真实收到的电脑端回答片段（`assistant/message`
 * 的正文），不是凭空编的语法练习。理由很直接：解析器只需要满足**实际出现的**
 * 那几种写法，用真实样本才能证明这一点；而用虚构样本很容易写出一堆通过的测试，
 * 却仍然渲染不了真实回答。
 */
class MarkdownTest {

    @Test
    fun `fenced code block keeps its body verbatim`() {
        // 真实回答里贴终端命令的常见形态。代码里的 `#` 与 `- ` 不能被当成 Markdown。
        val text = """
            先跑这个：

            ```powershell
            # 不是标题
            - 也不是列表
            gradlew.bat assembleRelease
            ```

            然后看输出。
        """.trimIndent()

        val blocks = parseMarkdown(text)
        assertEquals(3, blocks.size)
        assertTrue(blocks[1] is MdBlock.Code)
        val code = blocks[1] as MdBlock.Code
        assertEquals("powershell", code.language)
        assertEquals("# 不是标题\n- 也不是列表\ngradlew.bat assembleRelease", code.code)
    }

    @Test
    fun `unterminated fence is kept rather than dropped`() {
        // 流式输出时围栏常常还没闭合。丢掉剩余内容会让用户眼看着后半个回答消失。
        val blocks = parseMarkdown("说明\n\n```kotlin\nval a = 1")
        val code = blocks.last() as MdBlock.Code
        assertEquals("val a = 1", code.code)
    }

    @Test
    fun `heading and bullet are recognised`() {
        val blocks = parseMarkdown("## 结论\n\n- 第一点\n- 第二点")
        assertEquals(3, blocks.size)
        assertEquals(MdBlock.Heading(2, "结论"), blocks[0])
        assertEquals(MdBlock.Bullet("第一点"), blocks[1])
        assertEquals(MdBlock.Bullet("第二点"), blocks[2])
    }

    @Test
    fun `blank lines separate paragraphs`() {
        val blocks = parseMarkdown("第一段\n还是第一段\n\n第二段")
        assertEquals(2, blocks.size)
        assertEquals("第一段\n还是第一段", (blocks[0] as MdBlock.Paragraph).text)
        assertEquals("第二段", (blocks[1] as MdBlock.Paragraph).text)
    }

    @Test
    fun `bold markers are consumed not printed`() {
        // 用户看到的必须是"返回键闪屏"而不是"**返回键闪屏**" —— 这正是之前
        // 屏幕上满是星号的直接原因。
        val annotated = inline("**返回键闪屏** 已修复", androidx.compose.ui.graphics.Color.Black)
        assertEquals("返回键闪屏 已修复", annotated.text)
        assertTrue(annotated.spanStyles.isNotEmpty())
    }

    @Test
    fun `inline code inside emphasis is not treated as bold`() {
        // 代码里的 `**` 合法且常见（幂运算），必须先被行内代码吃掉。
        val annotated = inline("算一下 `a ** b` 的结果", androidx.compose.ui.graphics.Color.Black)
        assertEquals("算一下 a ** b 的结果", annotated.text)
    }

    @Test
    fun `link keeps its label and drops the url`() {
        val annotated = inline("见 [文档](https://example.test/x)", androidx.compose.ui.graphics.Color.Black)
        assertEquals("见 文档", annotated.text)
    }

    @Test
    fun `unclosed emphasis is printed literally`() {
        // 增量到达顺序不保证，未闭合的标记不能吞掉后面的文字。
        val annotated = inline("这里有个 ** 星号", androidx.compose.ui.graphics.Color.Black)
        assertEquals("这里有个 ** 星号", annotated.text)
    }

    @Test
    fun `table is parsed into header and rows`() {
        // 这段是用户截图里那坨「|AI 回答|浅底卡片|」的原文形态 —— 之前整张表
        // 被当成普通段落直接印出来，屏幕上就是一堆竖线。
        val text = """
            | 元素 | 渲染 |
            | --- | --- |
            | AI 回答 | 浅底卡片 |
            | 工具调用 | 折叠块 |
        """.trimIndent()

        val blocks = parseMarkdown(text)
        assertEquals(1, blocks.size)
        val table = blocks[0] as MdBlock.Table
        assertEquals(listOf("元素", "渲染"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("AI 回答", "浅底卡片"), table.rows[0])
        assertEquals(listOf("工具调用", "折叠块"), table.rows[1])
    }

    @Test
    fun `pipes without a separator line stay a paragraph`() {
        // 判据是「有竖线**且下一行是分隔行**」。仅凭竖线就切成表格，会把
        // 正文里带 `|` 的普通句子（例如 shell 管道 `a | b`）误判成表格。
        val blocks = parseMarkdown("用 ps aux | grep node 查进程")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
    }

    @Test
    fun `ragged table rows are padded to header width`() {
        // 模型偶尔少写一列。缺列会让界面的列对齐错位，补空串比让整张表歪掉好。
        val text = """
            | A | B |
            | --- | --- |
            | 只有一格 |
        """.trimIndent()

        val table = parseMarkdown(text)[0] as MdBlock.Table
        assertEquals(listOf("只有一格", ""), table.rows[0])
    }

    @Test
    fun `table inside a fence stays code`() {
        // 围栏里的竖线是代码（例如 Markdown 源码示例），不能变成表格。
        val text = "```\n| A | B |\n| --- | --- |\n```"
        val blocks = parseMarkdown(text)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Code)
    }

    @Test
    fun `horizontal rule becomes a divider`() {
        val blocks = parseMarkdown("上一段\n\n---\n\n下一段")
        assertEquals(3, blocks.size)
        assertEquals(MdBlock.Divider, blocks[1])
    }
}
