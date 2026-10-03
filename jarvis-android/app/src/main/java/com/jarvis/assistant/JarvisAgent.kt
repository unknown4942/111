package com.jarvis.assistant

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.WebSearchTool20260209
import com.fasterxml.jackson.core.type.TypeReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Claude ile konuşan ve telefon araçlarını çağıran ajan döngüsü.
 *
 * Akış: kullanıcı mesajı → Claude → (gerekirse araç çağrıları → sonuçlar → Claude) → cevap.
 * Konuşma geçmişi yalnızca sona eklenerek tutulur; [reset] ile temizlenir.
 */
class JarvisAgent(apiKey: String, private val tools: PhoneTools) {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .build()

    private val history = mutableListOf<MessageParam>()

    fun reset() = history.clear()

    /**
     * Kullanıcının mesajını işler ve Jarvis'in son cevabını döndürür.
     * [onToolCall] her araç çağrısında arayüzü bilgilendirmek için çağrılır.
     */
    suspend fun ask(userText: String, onToolCall: (String) -> Unit = {}): String {
        val checkpoint = history.size
        history += MessageParam.builder()
            .role(MessageParam.Role.USER)
            .content(userText)
            .build()
        try {
            return runLoop(onToolCall)
        } catch (e: Exception) {
            // Yarım kalan turu geri al ki bir sonraki istek tutarlı bir geçmişle başlasın.
            while (history.size > checkpoint) history.removeAt(history.lastIndex)
            throw e
        }
    }

    private suspend fun runLoop(onToolCall: (String) -> Unit): String {
        repeat(MAX_STEPS) {
            val response = withContext(Dispatchers.IO) { client.messages().create(buildParams()) }
            history += response.toParam()

            when (response.stopReason().orElse(null)) {
                StopReason.TOOL_USE -> history += runTools(response, onToolCall)
                // Sunucu tarafı araç (web araması) uzun sürdüyse devam etmesini iste.
                StopReason.PAUSE_TURN -> Unit
                StopReason.REFUSAL -> return "Üzgünüm, bu isteğe yardımcı olamıyorum."
                else -> return textOf(response).ifBlank { "Tamam." }
            }
        }
        return "Bu iş beklediğimden uzun sürdü, durdum. Tekrar dener misiniz?"
    }

    private fun buildParams(): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(4096L)
            .system(systemPrompt())
            // Sesli asistan için hızlı cevap önemli; derin düşünme gerektirmeyen işler.
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .messages(history)
            .addTool(WebSearchTool20260209.builder().maxUses(3L).build())
            // Güvenlik filtresi bir isteği reddederse sunucu uygun bir modele otomatik geçer.
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        tools.definitions.forEach { builder.addTool(it) }
        return builder.build()
    }

    private suspend fun runTools(response: Message, onToolCall: (String) -> Unit): MessageParam {
        val results = response.content().mapNotNull { block -> block.toolUse().orElse(null) }.map { call ->
            onToolCall(call.name())
            val input: Map<String, Any?> = try {
                jsonMapper().convertValue(call._input(), object : TypeReference<Map<String, Any?>>() {})
                    ?: emptyMap()
            } catch (e: Exception) {
                emptyMap()
            }
            val (text, isError) = try {
                withContext(Dispatchers.Main) { tools.execute(call.name(), input) } to false
            } catch (e: PhoneTools.ToolError) {
                (e.message ?: "Hata") to true
            } catch (e: Exception) {
                "Araç çalışırken hata oluştu: ${e.message}" to true
            }
            ContentBlockParam.ofToolResult(
                ToolResultBlockParam.builder()
                    .toolUseId(call.id())
                    .content(text)
                    .isError(isError)
                    .build(),
            )
        }
        // Tüm araç sonuçları tek bir kullanıcı mesajında geri gönderilir.
        return MessageParam.builder()
            .role(MessageParam.Role.USER)
            .contentOfBlockParams(results)
            .build()
    }

    private fun textOf(message: Message): String =
        message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("\n").trim()

    private fun systemPrompt(): String {
        // Tarih gün bazında tutulur ki istek öneki gün içinde değişmesin.
        val today = SimpleDateFormat("d MMMM yyyy EEEE", Locale("tr", "TR")).format(Date())
        return """
            Sen Jarvis'sin: kullanıcının Android telefonunda çalışan, Türkçe konuşan kişisel bir asistansın.
            Bugün: $today.

            Cevapların çoğunlukla sesli okunur. Bu yüzden kısa, doğal ve konuşma diliyle cevap ver;
            markdown, madde işareti, emoji veya tablo kullanma.

            Telefonda iş yapmak için sana verilen araçları kullan. Bir kişiyi aramak veya ona mesaj atmak
            istendiğinde önce find_contact ile numarayı bul; birden fazla eşleşme varsa hangisi olduğunu sor.
            Arama ve SMS işlemlerinde kullanıcıya ekranda ayrıca onay sorulur, sen tekrar sorma.
            Güncel bilgi (hava durumu, haberler, sonuçlar) gerekirse web aramasını kullan.
            Bir araç hata verirse kullanıcıya kısaca nedenini ve ne yapabileceğini söyle.
            Yapamadığın bir şey istenirse bunu dürüstçe söyle.
        """.trimIndent()
    }

    private companion object {
        const val MODEL = "claude-opus-5-5"
        const val MAX_STEPS = 10
    }
}
