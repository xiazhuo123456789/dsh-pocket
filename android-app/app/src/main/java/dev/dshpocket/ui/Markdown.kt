package dev.dshpocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 一个够用就好的 Markdown 渲染器。
 *
 * ### 为什么必须有它
 *
 * 电脑端模型几乎总会用 Markdown 回答（`**加粗**`、`` `代码` ``、列表、围栏代码块）。
 * 之前这里直接把原始字符串丢进 `Text`，于是用户看到的是**带星号和反引号的原文**：
 *
 * ```
 * **1. 返回键闪屏、退不出会话**
 * `hasResumed` 声明在 `PocketRoot` 的 `else` 分支**内部**。
 * ```
 *
 * 这不是"风格问题"，是没渲染。屏幕上全是 `**` 和 `` ` ``，读起来比纯文本还累 ——
 * 用户说的"太丑"里有相当一部分是这个。
 *
 * ### 为什么不用成熟的 Markdown 库
 *
 * 完整 CommonMark 实现（如 multiplatform-markdown-renderer）会带来数倍于本应用
 * 核心逻辑的代码量和依赖面。而模型在实际回答里用到的语法就这几样：围栏代码块、
 * 标题、列表、行内代码、加粗、斜体、链接。为这七种语法引入一个通用解析器不划算，
 * 所以这里写一个**只认这七种**的解析器，遇到别的语法就原样输出 —— 宁可少渲染，
 * 也不要把用户没写的格式凭空造出来。
 *
 * ### 保守原则
 *
 * 解析器绝不修改文字内容，只决定"这段字用什么样式画"。没有闭合的 `**` 就当作
 * 普通字符，不会把后半段整个吞掉 —— 流式输出过程中成对的标记经常还没到齐，
 * 这时候吞字会造成"内容忽隐忽现"。
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Code -> CodeBlock(block.code, block.language, color)
                is MdBlock.Heading -> Text(
                    text = inline(block.text, color),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                )
                is MdBlock.Bullet -> Row(Modifier.fillMaxWidth()) {
                    // 固定宽度的项目符号列，让多行条目的续行对齐到文字而不是符号。
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyLarge,
                        color = color.copy(alpha = 0.55f),
                        modifier = Modifier.width(16.dp),
                    )
                    Text(
                        text = inline(block.text, color),
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                        color = color,
                    )
                }
                is MdBlock.Paragraph -> Text(
                    text = inline(block.text, color),
                    // lineHeight 比字号大不少是刻意的：中文正文行距太挤会显著降低
                    // 长回答的可读性，而模型回答通常很长。
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                    color = color,
                )
            }
        }
    }
}

/**
 * 围栏代码块：等宽字体 + 略深的底 + 横向滚动。
 *
 * 横向滚动而不是自动换行：代码换行会改变它的含义（缩进、字符串字面量都可能被
 * 视觉上截断），宁可让用户手动滑。
 */
@Composable
private fun CodeBlock(
    code: String,
    language: String?,
    contentColor: androidx.compose.ui.graphics.Color,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                RoundedCornerShape(8.dp),
            )
            .padding(10.dp),
    ) {
        if (!language.isNullOrBlank()) {
            Text(
                text = language,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text = code,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                lineHeight = 18.sp,
            ),
            color = contentColor,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            softWrap = false,
        )
    }
}

/** Markdown 的块级元素。只保留实际用得上的四类。 */
internal sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Code(val code: String, val language: String?) : MdBlock
}

private val FENCE = Regex("^\\s*```\\s*([A-Za-z0-9_+-]*)")
private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^\\s*[-*+]\\s+(.*)$")

/**
 * 把整段文本切成块。
 *
 * 空行分隔段落；围栏代码块内部的一切都不当作 Markdown 解析（否则代码里的 `#`
 * 会被当成标题、`- ` 会被当成列表）。
 */
internal fun parseMarkdown(text: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()
    val code = StringBuilder()
    var language: String? = null
    var inCode = false

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            out += MdBlock.Paragraph(paragraph.toString())
            paragraph.clear()
        }
    }

    for (line in text.replace("\r\n", "\n").split('\n')) {
        if (inCode) {
            // 闭合围栏：只认 ``` 开头且后面没有语言名的行。
            if (FENCE.matches(line) && FENCE.matchEntire(line)?.groupValues?.get(1).isNullOrBlank()) {
                out += MdBlock.Code(code.toString().trimEnd('\n'), language)
                code.clear()
                language = null
                inCode = false
            } else {
                code.append(line).append('\n')
            }
            continue
        }

        val fence = FENCE.matchEntire(line)
        if (fence != null) {
            flushParagraph()
            inCode = true
            language = fence.groupValues[1].ifBlank { null }
            continue
        }

        if (line.isBlank()) {
            flushParagraph()
            continue
        }

        val heading = HEADING.matchEntire(line)
        if (heading != null) {
            flushParagraph()
            out += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
            continue
        }

        val bullet = BULLET.matchEntire(line)
        if (bullet != null) {
            flushParagraph()
            out += MdBlock.Bullet(bullet.groupValues[1].trim())
            continue
        }

        if (paragraph.isNotEmpty()) paragraph.append('\n')
        paragraph.append(line)
    }

    // 流式输出时围栏常常还没闭合。把剩余内容当代码块收尾，而不是丢掉 ——
    // 丢掉会让用户眼看着后半个回答消失，再在下一次增量里突然出现。
    if (inCode) out += MdBlock.Code(code.toString().trimEnd('\n'), language)
    flushParagraph()
    return out
}

/**
 * 行内样式：`` `代码` ``、`**加粗**`、`*斜体*`、`[标题](链接)`。
 *
 * 链接只保留标题文字并加下划线，不生成可点击跳转 —— 这是一个远程控制台，
 * 从电脑端转写里点开任意 URL 不是本应用该做的事，而"看起来能点但点了没反应"
 * 比不给链接更糟。
 */
internal fun inline(text: String, color: androidx.compose.ui.graphics.Color): AnnotatedString =
    buildAnnotatedString {
        appendRich(text, color)
    }

private fun AnnotatedString.Builder.appendRich(
    text: String,
    color: androidx.compose.ui.graphics.Color,
) {
    var i = 0
    while (i < text.length) {
        // 行内代码优先：代码里出现 ** 是很寻常的（比如 `a ** b`），必须让代码
        // 先吃掉这一段，否则会被当成加粗开始。
        if (text[i] == '`') {
            val close = text.indexOf('`', i + 1)
            if (close > i) {
                pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = color.copy(alpha = 0.10f),
                        fontSize = 14.sp,
                    )
                )
                append(text.substring(i + 1, close))
                pop()
                i = close + 1
                continue
            }
        }
        if (text.startsWith("**", i)) {
            val close = text.indexOf("**", i + 2)
            if (close > i) {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                appendRich(text.substring(i + 2, close), color)
                pop()
                i = close + 2
                continue
            }
        }
        if (text[i] == '*' && !text.startsWith("**", i)) {
            val close = text.indexOf('*', i + 1)
            if (close > i) {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append(text.substring(i + 1, close))
                pop()
                i = close + 1
                continue
            }
        }
        if (text[i] == '[') {
            val mid = text.indexOf("](", i)
            val close = if (mid > i) text.indexOf(')', mid) else -1
            if (mid > i && close > mid) {
                pushStyle(SpanStyle(textDecoration = TextDecoration.Underline))
                appendRich(text.substring(i + 1, mid), color)
                pop()
                i = close + 1
                continue
            }
        }
        append(text[i])
        i++
    }
}
