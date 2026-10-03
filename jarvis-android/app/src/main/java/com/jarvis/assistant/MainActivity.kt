package com.jarvis.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {

    private lateinit var chatContainer: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var statusText: TextView
    private lateinit var messageInput: EditText
    private lateinit var micButton: FloatingActionButton

    private lateinit var voiceInput: VoiceInput
    private lateinit var voiceOutput: VoiceOutput
    private lateinit var phoneTools: PhoneTools
    private var agent: JarvisAgent? = null

    private var busy = false
    private var listening = false

    private val prefs by lazy { getSharedPreferences("jarvis", Context.MODE_PRIVATE) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else addBubble("Sesli komut için mikrofon izni gerekiyor.", fromUser = false)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        chatContainer = findViewById(R.id.chatContainer)
        chatScroll = findViewById(R.id.chatScroll)
        statusText = findViewById(R.id.statusText)
        messageInput = findViewById(R.id.messageInput)
        micButton = findViewById(R.id.micButton)

        phoneTools = PhoneTools(this, ::askConfirmation)
        voiceOutput = VoiceOutput(this)
        voiceInput = VoiceInput(
            this,
            onPartial = { statusText.text = "🎙 $it" },
            onResult = { text ->
                listening = false
                handleUserMessage(text)
            },
            onError = { error ->
                listening = false
                setStatus(getString(R.string.status_idle))
                addBubble(error, fromUser = false)
            },
        )

        findViewById<Button>(R.id.sendButton).setOnClickListener { sendTyped() }
        messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTyped()
                true
            } else {
                false
            }
        }
        micButton.setOnClickListener { onMicPressed() }
        findViewById<Button>(R.id.settingsButton).setOnClickListener { showApiKeyDialog() }
        findViewById<Button>(R.id.newChatButton).setOnClickListener {
            agent?.reset()
            chatContainer.removeAllViews()
            addBubble("Yeni sohbet başladı. Size nasıl yardımcı olabilirim?", fromUser = false)
        }

        requestStartupPermissions()
        createAgent()
        if (agent == null) {
            addBubble("Merhaba! Başlamak için Ayarlar'dan Claude API anahtarınızı girin.", fromUser = false)
            showApiKeyDialog()
        } else {
            addBubble("Merhaba, ben Jarvis. Size nasıl yardımcı olabilirim?", fromUser = false)
        }

        if (savedInstanceState == null && intent?.action == Intent.ACTION_ASSIST) onMicPressed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Asistan kısayoluyla tekrar çağrıldığında doğrudan dinlemeye başla.
        if (intent.action == Intent.ACTION_ASSIST) onMicPressed()
    }

    override fun onDestroy() {
        voiceInput.destroy()
        voiceOutput.shutdown()
        super.onDestroy()
    }

    // --- Kullanıcı girdisi -----------------------------------------------------------------

    private fun sendTyped() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty()) return
        messageInput.text.clear()
        handleUserMessage(text)
    }

    private fun onMicPressed() {
        if (listening) {
            voiceInput.stop()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        if (busy) return
        voiceOutput.stop()
        listening = true
        setStatus(getString(R.string.status_listening))
        voiceInput.start()
    }

    private fun handleUserMessage(text: String) {
        val currentAgent = agent
        if (currentAgent == null) {
            showApiKeyDialog()
            return
        }
        if (busy) return
        busy = true
        addBubble(text, fromUser = true)
        setStatus(getString(R.string.status_thinking))

        lifecycleScope.launch {
            val reply = try {
                currentAgent.ask(text) { toolName -> runOnUiThread { setStatus("⚙ ${toolLabel(toolName)}") } }
            } catch (e: UnauthorizedException) {
                "API anahtarı geçersiz görünüyor. Ayarlar'dan kontrol edin."
            } catch (e: RateLimitException) {
                "Şu anda çok fazla istek var, biraz sonra tekrar deneyin."
            } catch (e: AnthropicServiceException) {
                "Claude servisinden hata döndü (${e.statusCode()}): ${e.message}"
            } catch (e: AnthropicIoException) {
                "İnternet bağlantısında sorun var, tekrar deneyin."
            } catch (e: Exception) {
                "Beklenmeyen bir hata oluştu: ${e.message}"
            }
            addBubble(reply, fromUser = false)
            if (prefs.getBoolean(KEY_SPEAK, true)) voiceOutput.speak(reply)
            setStatus(getString(R.string.status_idle))
            busy = false
        }
    }

    // --- Arayüz yardımcıları ---------------------------------------------------------------

    private fun addBubble(text: String, fromUser: Boolean) {
        val density = resources.displayMetrics.density
        val bubble = TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.text_primary))
            textSize = 16f
            setTextIsSelectable(true)
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
            setBackgroundResource(R.drawable.bubble)
            backgroundTintList = getColorStateList(if (fromUser) R.color.user_bubble else R.color.jarvis_bubble)
            maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt()
        }
        val row = FrameLayout(this).apply {
            val margin = (6 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, margin, 0, margin) }
            addView(
                bubble,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    if (fromUser) Gravity.END else Gravity.START,
                ),
            )
        }
        chatContainer.addView(row)
        chatScroll.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun setStatus(text: String) {
        statusText.text = text
    }

    private fun toolLabel(name: String) = when (name) {
        "find_contact" -> "Rehberde arıyorum…"
        "call_phone" -> "Arama hazırlanıyor…"
        "send_sms" -> "Mesaj hazırlanıyor…"
        "open_app" -> "Uygulama açılıyor…"
        "set_alarm" -> "Alarm kuruluyor…"
        "set_timer" -> "Zamanlayıcı kuruluyor…"
        "flashlight" -> "Fener…"
        "media_control" -> "Medya kontrolü…"
        "open_settings" -> "Ayarlar açılıyor…"
        "navigate" -> "Harita açılıyor…"
        "open_url" -> "Sayfa açılıyor…"
        "device_status" -> "Telefon durumu okunuyor…"
        else -> name
    }

    /** Arama/SMS gibi işlemler için kullanıcıya ekranda Evet/Hayır sorar. */
    private suspend fun askConfirmation(question: String): Boolean = suspendCancellableCoroutine { cont ->
        voiceOutput.speak(question.substringBefore("\n"))
        val dialog = AlertDialog.Builder(this)
            .setTitle("Jarvis")
            .setMessage(question)
            .setPositiveButton(R.string.confirm) { _, _ -> if (cont.isActive) cont.resume(true) }
            .setNegativeButton(R.string.cancel) { _, _ -> if (cont.isActive) cont.resume(false) }
            .setOnCancelListener { if (cont.isActive) cont.resume(false) }
            .show()
        cont.invokeOnCancellation { dialog.dismiss() }
    }

    private fun showApiKeyDialog() {
        val density = resources.displayMetrics.density
        val input = EditText(this).apply {
            hint = "sk-ant-..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString(KEY_API, ""))
        }
        val workspaceInput = EditText(this).apply {
            hint = getString(R.string.workspace_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(prefs.getString(KEY_WORKSPACE, ""))
        }
        val speakToggle = android.widget.CheckBox(this).apply {
            text = getString(R.string.speak_replies)
            isChecked = prefs.getBoolean(KEY_SPEAK, true)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
            addView(workspaceInput)
            addView(speakToggle)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.api_key_title)
            .setMessage(R.string.api_key_message)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                prefs.edit()
                    .putString(KEY_API, input.text.toString().trim())
                    .putString(KEY_WORKSPACE, workspaceInput.text.toString().trim())
                    .putBoolean(KEY_SPEAK, speakToggle.isChecked)
                    .apply()
                createAgent()
                if (agent != null) addBubble("Ayarlar kaydedildi. Hazırım.", fromUser = false)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun createAgent() {
        val key = prefs.getString(KEY_API, null)?.takeIf { it.isNotBlank() }
        val workspaceId = prefs.getString(KEY_WORKSPACE, null)
        agent = key?.let { JarvisAgent(it, workspaceId, phoneTools) }
    }

    private fun requestStartupPermissions() {
        val wanted = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }

    private companion object {
        const val KEY_API = "api_key"
        const val KEY_SPEAK = "speak_replies"
        const val KEY_WORKSPACE = "workspace_id"
    }
}
