package com.jarvis.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Google Gemini (Interactions API) ile konuşan ve telefon araçlarını çağıran ajan döngüsü.
 *
 * Gemini ücretsiz katmanla kullanılabilir (aistudio.google.com). Konuşma geçmişi sunucuda
 * tutulur; her istekte bir önceki etkileşimin kimliği (previous_interaction_id) gönderilir.
 */
class GeminiAgent(private val apiKey: String, private val tools: PhoneTools) : Assistant {

    private val http = OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    private val toolsJson: JSONArray = JSONArray().apply {
        put(JSONObject().put("type", "google_search"))
        tools.specs.forEach { put(it.toGeminiJson()) }
    }

    private var lastInteractionId: String? = null

    override fun reset() {
        lastInteractionId = null
    }

    override suspend fun ask(userText: String, onToolCall: (String) -> Unit): String {
        val checkpoint = lastInteractionId
        try {
            var interaction = create(userText)
            repeat(MAX_STEPS) {
                val calls = interaction.steps().filter { it.optString("type") == "function_call" }
                if (calls.isEmpty()) return outputText(interaction).ifBlank { "Tamam." }

                val results = JSONArray()
                for (call in calls) {
                    val name = call.optString("name")
                    onToolCall(name)
                    val (text, isError) = tools.run(name, call.optJSONObject("arguments").toMap())
                    results.put(
                        JSONObject()
                            .put("type", "function_result")
                            .put("name", name)
                            .put("call_id", call.optString("id"))
                            .put(
                                "result",
                                JSONArray().put(
                                    JSONObject()
                                        .put("type", "text")
                                        .put("text", if (isError) "HATA: $text" else text),
                                ),
                            ),
                    )
                }
                // Tüm araç sonuçları tek istekte geri gönderilir.
                interaction = create(results)
            }
            return "Bu iş beklediğimden uzun sürdü, durdum. Tekrar dener misiniz?"
        } catch (e: Exception) {
            // Yarım kalan turu geri al ki bir sonraki istek tutarlı bir geçmişle başlasın.
            lastInteractionId = checkpoint
            throw e
        }
    }

    /** Yeni bir etkileşim oluşturur; [input] metin veya function_result listesi olabilir. */
    private suspend fun create(input: Any): JSONObject {
        val body = JSONObject()
            .put("model", MODEL)
            .put("system_instruction", jarvisSystemPrompt())
            .put("input", input)
            .put("tools", toolsJson)
            // Sesli asistan için hızlı cevap önemli.
            .put("generation_config", JSONObject().put("thinking_level", "low"))
        lastInteractionId?.let { body.put("previous_interaction_id", it) }

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val (code, text) = withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        }
        if (code !in 200..299) {
            // Hata gövdesi bazen tek bir nesne, bazen nesne dizisi olarak gelir.
            val message = runCatching {
                val json = text.trim()
                val obj = if (json.startsWith("[")) JSONArray(json).getJSONObject(0) else JSONObject(json)
                obj.getJSONObject("error").getString("message")
            }.getOrDefault(text.take(300))
            throw AssistantException("Gemini hata verdi ($code): $message")
        }
        val interaction = JSONObject(text)
        lastInteractionId = interaction.optString("id").ifBlank { null }
        return interaction
    }

    private fun JSONObject.steps(): List<JSONObject> {
        val steps = optJSONArray("steps") ?: return emptyList()
        return (0 until steps.length()).mapNotNull { steps.optJSONObject(it) }
    }

    private fun outputText(interaction: JSONObject): String {
        interaction.optString("output_text").takeIf { it.isNotBlank() }?.let { return it.trim() }
        return interaction.steps()
            .filter { it.optString("type") == "model_output" }
            .flatMap { step ->
                val content = step.optJSONArray("content") ?: JSONArray()
                (0 until content.length()).mapNotNull { content.optJSONObject(it) }
            }
            .filter { it.optString("type") == "text" }
            .joinToString("\n") { it.optString("text") }
            .trim()
    }

    private fun JSONObject?.toMap(): Map<String, Any?> {
        if (this == null) return emptyMap()
        return keys().asSequence().associateWith { key -> opt(key).takeUnless { it == JSONObject.NULL } }
    }

    private companion object {
        const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
        const val MODEL = "gemini-3.8-flash"
        const val MAX_STEPS = 10
    }
}
