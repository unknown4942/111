package com.jarvis.assistant

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Jarvis'in arkasındaki yapay zekâ (Claude veya Gemini). */
interface Assistant {
    /**
     * Kullanıcının mesajını işler, gerekirse telefon araçlarını çağırır ve son cevabı döndürür.
     * [onToolCall] her araç çağrısında arayüzü bilgilendirmek için çağrılır.
     */
    suspend fun ask(userText: String, onToolCall: (String) -> Unit = {}): String

    /** Konuşma geçmişini temizler. */
    fun reset()
}

/** Sağlayıcının döndürdüğü, kullanıcıya gösterilebilecek hata. */
class AssistantException(message: String) : Exception(message)

/** Her iki sağlayıcı için ortak sistem talimatı. */
fun jarvisSystemPrompt(): String {
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
