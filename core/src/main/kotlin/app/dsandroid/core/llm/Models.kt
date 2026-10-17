package app.dsandroid.core.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.*

val LlmJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

@Serializable(with = ChatMessageSerializer::class)
data class ChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val parts: List<ContentPart>? = null,
) {
    fun forVision(enabled: Boolean): ChatMessage = if (enabled || parts == null) this else
        copy(content = parts.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("\n"), parts = null)
}

@Serializable
data class ImageUrl(val url: String)

@Serializable
data class ContentPart(val type: String, val text: String? = null, @SerialName("image_url") val imageUrl: ImageUrl? = null) {
    companion object {
        fun text(value: String) = ContentPart("text", text = value)
        fun image(dataUri: String) = ContentPart("image_url", imageUrl = ImageUrl(dataUri))
    }
}

@Serializable
private data class MessageWire(
    val role: String, val content: JsonElement? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

object ChatMessageSerializer : KSerializer<ChatMessage> {
    override val descriptor: SerialDescriptor = MessageWire.serializer().descriptor
    override fun serialize(encoder: Encoder, value: ChatMessage) {
        val content = value.parts?.let { LlmJson.encodeToJsonElement(ListSerializer(ContentPart.serializer()), it) }
            ?: value.content?.let(::JsonPrimitive)
        encoder.encodeSerializableValue(MessageWire.serializer(), MessageWire(value.role, content, value.reasoningContent, value.toolCalls, value.toolCallId))
    }
    override fun deserialize(decoder: Decoder): ChatMessage {
        val wire = decoder.decodeSerializableValue(MessageWire.serializer())
        return ChatMessage(wire.role, (wire.content as? JsonPrimitive)?.contentOrNull, wire.reasoningContent, wire.toolCalls, wire.toolCallId,
            (wire.content as? JsonArray)?.let { LlmJson.decodeFromJsonElement(ListSerializer(ContentPart.serializer()), it) })
    }
}

@Serializable
data class FunctionCall(val name: String, val arguments: String)

@Serializable
data class ToolCall(val id: String, val type: String = "function", val function: FunctionCall)

@Serializable
data class FunctionSpec(val name: String, val description: String, val parameters: JsonObject)

@Serializable
data class ToolSpec(val type: String = "function", val function: FunctionSpec)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,
    val stream: Boolean = true,
    val thinking: JsonObject? = null,
)

@Serializable
data class ChatChunk(val choices: List<StreamChoice> = emptyList())

@Serializable
data class StreamChoice(
    val index: Int = 0,
    val delta: StreamDelta = StreamDelta(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class StreamDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDelta>? = null,
)

@Serializable
data class ToolCallDelta(
    val index: Int,
    val id: String? = null,
    val type: String? = null,
    val function: FunctionDelta? = null,
)

@Serializable
data class FunctionDelta(val name: String? = null, val arguments: String? = null)
