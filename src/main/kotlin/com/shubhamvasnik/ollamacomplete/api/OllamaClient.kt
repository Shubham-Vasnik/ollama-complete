package com.shubhamvasnik.ollamacomplete.api

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.shubhamvasnik.ollamacomplete.settings.OllamaSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Flow as JFlow

/**
 * Thin client for the Ollama REST API (https://github.com/ollama/ollama/blob/main/docs/api.md).
 * All calls are suspending and abort the underlying HTTP exchange when the coroutine is cancelled.
 */
@Service(Service.Level.APP)
class OllamaClient(val scope: CoroutineScope) {

    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    /** model name -> capabilities reported by /api/show (e.g. completion, insert, thinking, tools). */
    private val capabilityCache = ConcurrentHashMap<String, Set<String>>()

    private val baseUrl: String
        get() = OllamaSettings.getInstance().serverUrl.trimEnd('/')

    private val timeout: Duration
        get() = Duration.ofSeconds(OllamaSettings.getInstance().requestTimeoutSec.toLong().coerceAtLeast(5))

    /** [serverUrl] overrides the configured URL, e.g. to test a URL that has not been applied yet. */
    suspend fun version(serverUrl: String? = null): String =
        json.decodeFromString<VersionResponse>(get("/api/version", serverUrl)).version

    /** Lists installed models that can generate text (embedding-only models are filtered out). */
    suspend fun listModels(serverUrl: String? = null): List<ModelInfo> {
        val models = json.decodeFromString<TagsResponse>(get("/api/tags", serverUrl)).models
        capabilityCache.clear()
        models.filter { it.capabilities.isNotEmpty() }.forEach { capabilityCache[it.name] = it.capabilities.toSet() }
        return models.filter { it.canGenerate }.sortedBy { it.name.lowercase() }
    }

    suspend fun capabilities(model: String): Set<String> {
        capabilityCache[model]?.let { return it }
        val caps = json.decodeFromString<ShowResponse>(post("/api/show", json.encodeToString(ShowRequest(model)))).capabilities.toSet()
        capabilityCache[model] = caps
        return caps
    }

    suspend fun generate(request: GenerateRequest): GenerateResponse {
        val body = post("/api/generate", json.encodeToString(request.copy(stream = false)))
        val response = json.decodeFromString<GenerateResponse>(body)
        response.error?.let { throw OllamaException(it) }
        return response
    }

    /** Streams /api/generate as NDJSON chunks. Cancelling the collector aborts the request, which stops generation. */
    fun generateStream(request: GenerateRequest): Flow<GenerateResponse> =
        streamLines("/api/generate", json.encodeToString(request.copy(stream = true))).map { line ->
            val chunk = json.decodeFromString<GenerateResponse>(line)
            chunk.error?.let { throw OllamaException(it) }
            chunk
        }

    /** Streams /api/chat as NDJSON chunks. Cancelling the collector aborts the request. */
    fun chat(request: ChatRequest): Flow<ChatChunk> =
        streamLines("/api/chat", json.encodeToString(request.copy(stream = true))).map { line ->
            val chunk = json.decodeFromString<ChatChunk>(line)
            chunk.error?.let { throw OllamaException(it) }
            chunk
        }

    fun clearCaches() = capabilityCache.clear()

    private suspend fun get(path: String, serverUrl: String? = null): String =
        send(request(path, serverUrl).GET().build())

    private suspend fun post(path: String, body: String): String =
        send(request(path).POST(HttpRequest.BodyPublishers.ofString(body)).build())

    private fun request(path: String, serverUrl: String? = null): HttpRequest.Builder {
        val base = serverUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: baseUrl
        val uri = try {
            URI.create(base + path)
        } catch (e: IllegalArgumentException) {
            throw OllamaException("Invalid Ollama server URL: $base", e)
        }
        return HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Content-Type", "application/json")
    }

    private suspend fun send(request: HttpRequest): String {
        val response = try {
            http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        } catch (e: Exception) {
            throw translate(e, request.uri())
        }
        if (response.statusCode() !in 200..299) throw httpError(response.statusCode(), response.body())
        return response.body()
    }

    private fun streamLines(path: String, body: String): Flow<String> = callbackFlow {
        val request = request(path).POST(HttpRequest.BodyPublishers.ofString(body)).build()
        var subscription: JFlow.Subscription? = null
        var status = 200
        val subscriber = object : JFlow.Subscriber<String> {
            override fun onSubscribe(s: JFlow.Subscription) {
                subscription = s
                s.request(Long.MAX_VALUE)
            }

            override fun onNext(item: String) {
                if (item.isBlank()) return
                if (status !in 200..299) {
                    close(httpError(status, item))
                } else {
                    trySend(item)
                }
            }

            override fun onError(throwable: Throwable) {
                close(translate(throwable, request.uri()))
            }

            override fun onComplete() {
                close()
            }
        }
        val future = http.sendAsync(request) { info ->
            status = info.statusCode()
            HttpResponse.BodySubscribers.fromLineSubscriber(subscriber)
        }
        future.whenComplete { _, error -> if (error != null) close(translate(error, request.uri())) }
        awaitClose {
            subscription?.cancel()
            future.cancel(true)
        }
    }

    private fun httpError(status: Int, body: String): OllamaException {
        val message = runCatching { json.decodeFromString<ErrorResponse>(body).error }.getOrNull()
        return OllamaException(message ?: "Ollama returned HTTP $status")
    }

    private fun translate(error: Throwable, uri: URI): Throwable {
        val cause = if (error is CompletionException && error.cause != null) error.cause!! else error
        return when (cause) {
            is OllamaException -> cause
            is ConnectException -> OllamaException("Cannot connect to Ollama at ${uri.scheme}://${uri.authority}. Is Ollama running?", cause)
            is HttpTimeoutException -> OllamaException("Request to Ollama timed out", cause)
            else -> cause
        }
    }

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

        fun getInstance(): OllamaClient = service()
    }
}
