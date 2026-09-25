package com.clearline.inference

import com.clearline.core.ChatMessage
import com.clearline.core.ChatRole
import com.clearline.core.ToolSchema
import java.util.Locale
import kotlinx.serialization.json.*

/**
 * Kotlin rendering of the template embedded in the pinned Q4_0 GGUF, with the
 * default keep_past_thinking=false and add_generation_prompt=true options.
 * Source: model-metadata/lfm2.5-q4_0-chat-template.jinja, SHA-256 below.
 * Tools are supplied as JSON strings (a supported branch of that template).
 * Tokenization must use add_special=false because this renderer supplies BOS.
 */
object LiquidChatTemplate {
    const val TEMPLATE_SHA256 = "f05bf4b967dc993bdc7a2fe6e43759ee218eb0eb340d68b063e1c4f8ad148176"
    const val BOS = "<|startoftext|>"

    fun render(messages: List<ChatMessage>, tools: List<ToolSchema>): String {
        require(messages.isNotEmpty()) { "At least one message is required" }
        require(messages.drop(1).none { it.role == ChatRole.SYSTEM }) { "System message must be first" }
        require(tools.map { it.name }.distinct().size == tools.size) { "Duplicate tool schema" }
        var body = messages
        var system = ""
        if (messages.first().role == ChatRole.SYSTEM) {
            system = messages.first().content
            body = messages.drop(1)
        }
        if (tools.isNotEmpty()) {
            if (system.isNotEmpty()) system += "\n"
            system += tools.joinToString(", ", "List of tools: [", "]") { schema ->
                val parameters = Json.parseToJsonElement(schema.parametersJson)
                require(parameters is JsonObject) { "Tool parameters must be an object" }
                evidenceJson(buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", schema.name)
                        put("description", schema.description)
                        put("parameters", parameters)
                    }
                })
            }
        }
        val lastAssistant = body.indexOfLast { it.role == ChatRole.ASSISTANT }
        return buildString {
            append(BOS)
            if (system.isNotEmpty()) appendMessage("system", system)
            body.forEachIndexed { index, message ->
                var content = message.content
                if (message.role == ChatRole.ASSISTANT && index != lastAssistant && "</think>" in content) {
                    content = content.substringAfterLast("</think>").trim()
                }
                appendMessage(message.role.name.lowercase(Locale.ROOT), content)
            }
            append("<|im_start|>assistant\n")
        }
    }

    /** Escape untrusted content before saving/rendering JSON; never alter an exact stored exchange. */
    fun evidenceJson(value: JsonElement): String = value.toString()
        .replace("<", "\\u003c").replace(">", "\\u003e")
        .replace("&", "\\u0026").replace("'", "\\u0027")

    /** The runtime returns EOG; it is a role delimiter, not saved assistant content. */
    internal fun assistantContent(output: String): String = output.replace(Regex("<\\|im_end\\|>(\\s*)$"), "$1")

    private fun StringBuilder.appendMessage(role: String, content: String) {
        append("<|im_start|>").append(role).append('\n').append(content).append("<|im_end|>\n")
    }
}
