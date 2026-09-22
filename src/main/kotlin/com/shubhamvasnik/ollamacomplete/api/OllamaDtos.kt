package com.shubhamvasnik.ollamacomplete.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModelDetails(
    val family: String? = null,
    @SerialName("parameter_size") val parameterSize: String? = null,
    @SerialName("quantization_level") val quantizationLevel: String? = null,
)

@Serializable
data class ModelInfo(
    val name: String,
    val size: Long = 0,
    val details: ModelDetails? = null,
    /** Reported by /api/tags on recent Ollama versions; empty on older ones. */
    val capabilities: List<String> = emptyList(),
) {
    /** Embedding-only models cannot generate text, so they are hidden from model pickers. */
    val canGenerate: Boolean
        get() = capabilities.isEmpty() || "completion" in capabilities
}

@Serializable
data class TagsResponse(val models: List<ModelInfo> = emptyList())

@Serializable
data class ShowRequest(val model: String)

@Serializable
data class ShowResponse(val capabilities: List<String> = emptyList())

@Serializable
data class VersionResponse(val version: String = "")

@Serializable
data class ModelOptions(
    val temperature: Double? = null,
    @SerialName("num_predict") val numPredict: Int? = null,
    val stop: List<String>? = null,
)

@Serializable
data class GenerateRequest(
    val model: String,
    val prompt: String,
    val suffix: String? = null,
    val system: String? = null,
    val stream: Boolean = false,
    val think: Boolean? = null,
    val options: ModelOptions? = null,
    @SerialName("keep_alive") val keepAlive: String? = null,
)

@Serializable
data class GenerateResponse(
    val response: String = "",
    val thinking: String? = null,
    val done: Boolean = false,
    val error: String? = null,
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    val thinking: String? = null,
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
    val think: Boolean? = null,
    val options: ModelOptions? = null,
    @SerialName("keep_alive") val keepAlive: String? = null,
)

@Serializable
data class ChatChunk(
    val message: ChatMessage? = null,
    val done: Boolean = false,
    val error: String? = null,
)

@Serializable
data class ErrorResponse(val error: String? = null)

class OllamaException(message: String, cause: Throwable? = null) : Exception(message, cause)
