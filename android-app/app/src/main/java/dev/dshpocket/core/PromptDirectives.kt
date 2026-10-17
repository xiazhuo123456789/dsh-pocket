package dev.dshpocket.core

import dev.dshpocket.protocol.asArrOrNull
import dev.dshpocket.protocol.asObjOrNull
import dev.dshpocket.protocol.string
import kotlinx.serialization.json.JsonElement

/**
 * 手机端主动附加在用户消息里的「回答语言」指令。
 *
 * ### 为什么需要这么一个东西
 *
 * 用户反馈"回复不是中文简体"，实测发现是**电脑端模型自己选择了繁体中文**——
 * 不是字体问题，也不是本机语言设置问题。本应用对回复文字是纯粹透传：
 * `Json.contentText` 把电脑发来的文本原样交给 Compose 渲染，中间不做任何转换、
 * 不做繁简转换，也不做字体替换。
 *
 * 电脑端的语言选择由它自己的 agent 预设和系统提示决定，而 Remote API 里**没有**
 * 暴露"设置回复语言"这个接口（已核对 `session` 与 `settings` 两个命名空间下的
 * 全部方法名）。因此手机端唯一能影响它的正当手段，就是在发送时附上一句明确的
 * 指令。
 *
 * ### 为什么是一段独立的 content part，而不是改用户的原话
 *
 * `session/prompt` 的 `content` 是 `[{type:"text",text}]` 数组，可以带多段。
 * 把指令作为**独立的第二段**发送，好处是：
 *
 * 1. 用户的原话在电脑端日志里保持字节完全一致，不被我们偷偷改写；
 * 2. 想关掉就只是少发一段，没有"改回去"的还原风险；
 * 3. 本机转写里可以精确地把这一段过滤掉（见 [isDirective]），用户看到的仍是
 *    自己打的那句话，不会看到自己没打过的内容。
 *
 * 反过来，如果把指令拼进同一段文本（"…请用简体中文回答"），电脑端会话日志里
 * 用户的话就被永久改写了，而且本机再无可靠办法区分哪部分是用户原话。
 *
 * ### 诚实性约束
 *
 * 这段文字是**我们加的**，不是用户写的。所以：
 * - 设置项必须显式可关，且副标题写明"会给每条消息附加一句指令"；
 * - 文档与 README 里如实说明，不假装模型"自然就会说简体"。
 */
object PromptDirectives {

    /** 请求简体中文输出的指令原文。 */
    const val SIMPLIFIED_CHINESE: String = "请始终使用简体中文（Simplified Chinese）回答。"

    /**
     * 判断某一段文本是不是本应用附加的指令。
     *
     * 只做**精确全等**比对，不做包含匹配：用户完全可以自己打一句很像的话，
     * 用 `contains` 会把他真正说的话当成指令吞掉。
     */
    fun isDirective(text: String): Boolean = text.trim() == SIMPLIFIED_CHINESE

    /**
     * 从电脑回传的 `content` 里剥掉指令段，只留下用户真正说的话。
     *
     * 为什么必须剥：`content` 是数组，我们把指令作为第二段发出去，电脑端会把它
     * 和用户原话一起记进会话日志，回声事件也原样带回两段。如果直接把整段渲染，
     * 界面上就会出现一句用户从没打过的「请始终使用简体中文回答。」——既让人
     * 困惑，也会让下面的回声去重（比较用户原话）永远匹配不上，于是消息又变成
     * 两条。所以这里对着**同一份常量**做精确排除。
     *
     * 非文本段（图片等）继续保持"跳过而不是编占位符"，与 [Json.contentText] 一致。
     */
    fun contentTextWithoutDirectives(parts: JsonElement?): String {
        val arr = parts.asArrOrNull() ?: return ""
        val kept = arr.mapNotNull { part ->
            val o = part.asObjOrNull() ?: return@mapNotNull null
            if (o.string("type") != "text") return@mapNotNull null
            val text = o.string("text").orEmpty()
            if (isDirective(text)) null else text
        }
        // 用换行连接，与 Json.contentText 保持一致：多段文本本来就是不同段落。
        return kept.joinToString("\n").trim()
    }
}
