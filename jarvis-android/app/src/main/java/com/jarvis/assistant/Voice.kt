package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

private val TURKISH = Locale("tr", "TR")

/** Telefonun ses tanıma servisiyle Türkçe konuşmayı yazıya çevirir. */
class VoiceInput(
    context: Context,
    private val onPartial: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val recognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context).also { it.setRecognitionListener(listener()) }
        } else {
            null
        }

    val isAvailable get() = recognizer != null

    fun start() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, TURKISH.toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        recognizer?.startListening(intent) ?: onError("Bu telefonda ses tanıma servisi bulunamadı.")
    }

    fun stop() = recognizer?.stopListening()

    fun destroy() = recognizer?.destroy()

    private fun listener() = object : RecognitionListener {
        override fun onResults(results: Bundle) {
            val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) onError("Sizi duyamadım.") else onResult(text)
        }

        override fun onPartialResults(partialResults: Bundle) {
            partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let(onPartial)
        }

        override fun onError(error: Int) {
            onError(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Sizi duyamadım."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon izni gerekli."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Ses tanıma için internet bağlantısı gerekli."
                    else -> "Ses tanıma hatası ($error)."
                },
            )
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}

/** Jarvis'in cevaplarını Türkçe sesli okur. */
class VoiceOutput(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context, this)
    private var ready = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = TURKISH
            ready = true
        }
    }

    fun speak(text: String) {
        if (!ready) return
        // Model markdown kullanmaması için yönlendirildi; yine de kaçanları sesli okumayalım.
        val clean = text.replace(Regex("[*_#`>]"), "")
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "jarvis-reply")
    }

    fun stop() = tts.stop()

    fun shutdown() = tts.shutdown()
}
