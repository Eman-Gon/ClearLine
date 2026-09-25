package com.clearline.inference

import kotlinx.serialization.json.*

/** Server-independent tool protocol. No generated text is executable code. */
class ToolOutputRejected(val reason: String) : IllegalArgumentException(reason)

data class DecodedToolCall(val name: String, val arguments: JsonObject)

class StrictToolParser {
    private val json = Json { isLenient = false; ignoreUnknownKeys = false; allowSpecialFloatingPointValues = false }

    fun parse(output: String, permitted: Set<String>, truncated: Boolean = false): DecodedToolCall {
        if (truncated) throw ToolOutputRejected("truncated_tool_output")
        if (output.length > 16_384) throw ToolOutputRejected("tool_output_too_large")
        var text = output.trim()
        if (text.endsWith("<|im_end|>")) text = text.removeSuffix("<|im_end|>").trim()
        if (text.startsWith("<|tool_call_start|>")) {
            if (!text.endsWith("<|tool_call_end|>")) throw ToolOutputRejected("incomplete_tool_wrapper")
            text = text.removePrefix("<|tool_call_start|>").removeSuffix("<|tool_call_end|>").trim()
        }
        if ('<' in text && ("<|" in text)) throw ToolOutputRejected("unexpected_control_token")
        try {
            DuplicateKeys.reject(text)
            val call = json.parseToJsonElement(text) as? JsonObject ?: throw ToolOutputRejected("one_tool_object_required")
            if (call.keys != setOf("name", "arguments")) throw ToolOutputRejected("invalid_tool_envelope")
            val nameElement = call["name"] as? JsonPrimitive
            val name = nameElement?.takeIf { it.isString }?.content ?: throw ToolOutputRejected("invalid_tool_name")
            if (name !in permitted) throw ToolOutputRejected("tool_not_permitted")
            val arguments = call["arguments"] as? JsonObject ?: throw ToolOutputRejected("invalid_tool_arguments")
            return DecodedToolCall(name, arguments)
        } catch (error: ToolOutputRejected) { throw error }
        catch (_: Exception) { throw ToolOutputRejected("malformed_tool_json") }
    }
}

/** kotlinx JSON otherwise accepts duplicate object keys using the last value. */
private object DuplicateKeys {
    fun reject(text: String) {
        var position = 0
        fun whitespace() { while (position < text.length && text[position].isWhitespace()) position++ }
        fun string(): String {
            whitespace()
            if (position >= text.length || text[position] != '"') throw ToolOutputRejected("malformed_tool_json")
            val begin = position++
            var escaped = false
            while (position < text.length) {
                val character = text[position++]
                if (character == '"' && !escaped) return Json.decodeFromString<String>(text.substring(begin, position))
                escaped = character == '\\' && !escaped
            }
            throw ToolOutputRejected("malformed_tool_json")
        }
        fun value(depth: Int) {
            if (depth > 16) throw ToolOutputRejected("tool_json_too_deep")
            whitespace()
            if (position >= text.length) throw ToolOutputRejected("malformed_tool_json")
            when (text[position]) {
                '{' -> {
                    position++; whitespace()
                    val keys = mutableSetOf<String>()
                    if (position < text.length && text[position] == '}') { position++; return }
                    while (true) {
                        if (!keys.add(string())) throw ToolOutputRejected("duplicate_tool_field")
                        whitespace()
                        if (position >= text.length || text[position++] != ':') throw ToolOutputRejected("malformed_tool_json")
                        value(depth + 1); whitespace()
                        if (position >= text.length) throw ToolOutputRejected("malformed_tool_json")
                        when (text[position++]) { '}' -> return; ',' -> Unit; else -> throw ToolOutputRejected("malformed_tool_json") }
                    }
                }
                '[' -> {
                    position++; whitespace()
                    if (position < text.length && text[position] == ']') { position++; return }
                    while (true) {
                        value(depth + 1); whitespace()
                        if (position >= text.length) throw ToolOutputRejected("malformed_tool_json")
                        when (text[position++]) { ']' -> return; ',' -> Unit; else -> throw ToolOutputRejected("malformed_tool_json") }
                    }
                }
                '"' -> { string() }
                else -> {
                    val start = position
                    while (position < text.length && text[position] !in ",]} \n\r\t") position++
                    if (position == start) throw ToolOutputRejected("malformed_tool_json")
                }
            }
        }
        value(0); whitespace()
        if (position != text.length) throw ToolOutputRejected("multiple_tool_outputs")
    }
}
