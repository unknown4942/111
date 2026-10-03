package com.jarvis.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Telefonda Jarvis'in kullanabileceği araçlar.
 *
 * Her araç yapay zekâya bir [ToolSpec] tanımı olarak gönderilir; model bir aracı çağırdığında
 * [execute] ilgili Android işlemini yapar ve sonucu metin olarak döndürür.
 *
 * Arama ve SMS gibi geri alınamaz işlemler [confirm] ile kullanıcıya onaylatılır.
 */
class PhoneTools(
    private val activity: Activity,
    private val confirm: suspend (String) -> Boolean,
) {
    private val tr = Locale("tr", "TR")

    val specs: List<ToolSpec> = listOf(
        tool(
            "find_contact",
            "Rehberde isimle kişi arar ve eşleşen kişilerin adlarını ve telefon numaralarını döndürür. " +
                "Arama yapmadan veya mesaj göndermeden önce numarayı bulmak için kullan.",
            mapOf("query" to str("Aranacak isim veya ismin bir parçası, örn. 'annem', 'Ahmet'")),
            listOf("query"),
        ),
        tool(
            "call_phone",
            "Bir telefon numarasını arar. Kullanıcıdan ekranda onay istenir.",
            mapOf(
                "number" to str("Aranacak telefon numarası"),
                "display_name" to str("Kişinin adı (onay ekranında gösterilir)"),
            ),
            listOf("number"),
        ),
        tool(
            "send_sms",
            "Bir telefon numarasına SMS gönderir. Kullanıcıdan ekranda onay istenir.",
            mapOf(
                "number" to str("Alıcının telefon numarası"),
                "message" to str("Gönderilecek mesaj metni"),
                "display_name" to str("Alıcının adı (onay ekranında gösterilir)"),
            ),
            listOf("number", "message"),
        ),
        tool(
            "open_app",
            "Telefonda yüklü bir uygulamayı adıyla açar (örn. 'WhatsApp', 'YouTube', 'Kamera').",
            mapOf("app_name" to str("Uygulamanın adı")),
            listOf("app_name"),
        ),
        tool(
            "set_alarm",
            "Belirtilen saate alarm kurar.",
            mapOf(
                "hour" to int("Saat (0-23)"),
                "minute" to int("Dakika (0-59)"),
                "label" to str("İsteğe bağlı alarm etiketi"),
            ),
            listOf("hour", "minute"),
        ),
        tool(
            "set_timer",
            "Geri sayım zamanlayıcısı kurar.",
            mapOf(
                "seconds" to int("Toplam süre (saniye)"),
                "label" to str("İsteğe bağlı etiket"),
            ),
            listOf("seconds"),
        ),
        tool(
            "flashlight",
            "Feneri açar veya kapatır.",
            mapOf("on" to bool("true: aç, false: kapat")),
            listOf("on"),
        ),
        tool(
            "media_control",
            "Çalan müziği/medyayı kontrol eder veya ses seviyesini değiştirir.",
            mapOf(
                "action" to enum(
                    "Yapılacak işlem",
                    listOf("play_pause", "next", "previous", "volume_up", "volume_down", "mute"),
                ),
            ),
            listOf("action"),
        ),
        tool(
            "open_settings",
            "Telefon ayarlarının ilgili ekranını açar. Android, uygulamaların Wi-Fi/Bluetooth'u " +
                "doğrudan açıp kapatmasına izin vermez; bu yüzden kullanıcıya ilgili paneli göster.",
            mapOf(
                "panel" to enum(
                    "Açılacak ayar ekranı",
                    listOf("wifi", "bluetooth", "internet", "volume", "display", "battery", "location", "airplane", "general"),
                ),
            ),
            listOf("panel"),
        ),
        tool(
            "navigate",
            "Haritalarda bir yere yol tarifi başlatır veya bir yeri arar.",
            mapOf("destination" to str("Gidilecek yer veya adres")),
            listOf("destination"),
        ),
        tool(
            "open_url",
            "Bir web adresini tarayıcıda açar.",
            mapOf("url" to str("Açılacak tam adres (https://...)")),
            listOf("url"),
        ),
        tool(
            "device_status",
            "Telefonun pil seviyesini, şarj durumunu, tarihi ve saati döndürür.",
            emptyMap(),
            emptyList(),
        ),
    )

    /** Claude'un çağırdığı aracı çalıştırır; sonucu Claude'a gidecek metin olarak döndürür. */
    suspend fun execute(name: String, input: Map<String, Any?>): String = when (name) {
        "find_contact" -> findContact(input.string("query"))
        "call_phone" -> callPhone(input.string("number"), input.optString("display_name"))
        "send_sms" -> sendSms(input.string("number"), input.string("message"), input.optString("display_name"))
        "open_app" -> openApp(input.string("app_name"))
        "set_alarm" -> setAlarm(input.int("hour"), input.int("minute"), input.optString("label"))
        "set_timer" -> setTimer(input.int("seconds"), input.optString("label"))
        "flashlight" -> flashlight(input["on"] as? Boolean ?: throw ToolError("'on' alanı eksik"))
        "media_control" -> mediaControl(input.string("action"))
        "open_settings" -> openSettings(input.string("panel"))
        "navigate" -> navigate(input.string("destination"))
        "open_url" -> openUrl(input.string("url"))
        "device_status" -> deviceStatus()
        else -> throw ToolError("Bilinmeyen araç: $name")
    }

    // --- Araçlar ---------------------------------------------------------------------------

    private fun findContact(query: String): String {
        requirePermission(Manifest.permission.READ_CONTACTS, "rehber")
        val q = query.lowercase(tr).trim()
        val matches = linkedSetOf<String>()
        activity.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: continue
                val number = cursor.getString(1) ?: continue
                if (name.lowercase(tr).contains(q)) {
                    matches += "$name: ${number.replace(" ", "")}"
                }
                if (matches.size >= 10) break
            }
        }
        return if (matches.isEmpty()) {
            "Rehberde '$query' ile eşleşen kişi bulunamadı."
        } else {
            "Eşleşen kişiler:\n" + matches.joinToString("\n")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun callPhone(number: String, displayName: String?): String {
        val who = displayName?.let { "$it ($number)" } ?: number
        if (!confirm("$who aransın mı?")) return "Kullanıcı aramayı iptal etti."
        val uri = Uri.parse("tel:" + Uri.encode(number))
        return if (hasPermission(Manifest.permission.CALL_PHONE)) {
            start(Intent(Intent.ACTION_CALL, uri))
            "$who aranıyor."
        } else {
            start(Intent(Intent.ACTION_DIAL, uri))
            "Arama izni olmadığı için numara çevirme ekranı açıldı; kullanıcı arama tuşuna basmalı."
        }
    }

    private suspend fun sendSms(number: String, message: String, displayName: String?): String {
        requirePermission(Manifest.permission.SEND_SMS, "SMS gönderme")
        val who = displayName?.let { "$it ($number)" } ?: number
        if (!confirm("$who kişisine şu mesaj gönderilsin mi?\n\n\"$message\"")) {
            return "Kullanıcı mesaj gönderimini iptal etti."
        }
        val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        val parts = sms.divideMessage(message)
        sms.sendMultipartTextMessage(number, null, parts, null, null)
        return "Mesaj $who kişisine gönderildi."
    }

    private fun openApp(appName: String): String {
        val pm = activity.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcherIntent, 0).map {
            it.loadLabel(pm).toString() to it.activityInfo.packageName
        }
        val wanted = appName.lowercase(tr).trim()
        val match = apps.firstOrNull { it.first.lowercase(tr) == wanted }
            ?: apps.firstOrNull { it.first.lowercase(tr).contains(wanted) }
            ?: apps.firstOrNull { wanted.contains(it.first.lowercase(tr)) }
        if (match == null) {
            val suggestions = apps.map { it.first }.sorted().take(60).joinToString(", ")
            return "'$appName' adında bir uygulama bulunamadı. Yüklü uygulamalardan bazıları: $suggestions"
        }
        val intent = pm.getLaunchIntentForPackage(match.second)
            ?: throw ToolError("${match.first} açılamadı.")
        start(intent)
        return "${match.first} açıldı."
    }

    private fun setAlarm(hour: Int, minute: Int, label: String?): String {
        if (hour !in 0..23 || minute !in 0..59) throw ToolError("Geçersiz saat: $hour:$minute")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        start(intent)
        return "Alarm %02d:%02d için kuruldu.".format(hour, minute)
    }

    private fun setTimer(seconds: Int, label: String?): String {
        if (seconds <= 0) throw ToolError("Süre pozitif olmalı.")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        start(intent)
        return "$seconds saniyelik zamanlayıcı başlatıldı."
    }

    private fun flashlight(on: Boolean): String {
        val cm = activity.getSystemService(CameraManager::class.java)
        val cameraId = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: throw ToolError("Bu telefonda fener bulunamadı.")
        cm.setTorchMode(cameraId, on)
        return if (on) "Fener açıldı." else "Fener kapatıldı."
    }

    private fun mediaControl(action: String): String {
        val am = activity.getSystemService(AudioManager::class.java)
        fun key(code: Int) {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        when (action) {
            "play_pause" -> key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "next" -> key(KeyEvent.KEYCODE_MEDIA_NEXT)
            "previous" -> key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "volume_up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "volume_down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
            else -> throw ToolError("Bilinmeyen medya işlemi: $action")
        }
        return "Medya işlemi yapıldı: $action"
    }

    private fun openSettings(panel: String): String {
        val action = when (panel) {
            "wifi" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            "internet" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS
            "volume" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        start(Intent(action))
        return "'$panel' ayar ekranı açıldı; kullanıcı değişikliği oradan yapabilir."
    }

    private fun navigate(destination: String): String {
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(destination)))
        try {
            start(nav)
        } catch (e: ToolError) {
            start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(destination))))
        }
        return "$destination için harita açıldı."
    }

    private fun openUrl(url: String): String {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" && uri.scheme != "http") throw ToolError("Sadece http/https adresleri açılabilir.")
        start(Intent(Intent.ACTION_VIEW, uri))
        return "$url açıldı."
    }

    private fun deviceStatus(): String {
        val bm = activity.getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val now = SimpleDateFormat("d MMMM yyyy EEEE, HH:mm", tr).format(Date())
        return "Pil: %$level${if (charging) " (şarj oluyor)" else ""}. Tarih ve saat: $now."
    }

    // --- Yardımcılar -----------------------------------------------------------------------

    private fun start(intent: Intent) {
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            throw ToolError("Bu işlemi yapabilecek bir uygulama bulunamadı.")
        }
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    private fun requirePermission(permission: String, what: String) {
        if (!hasPermission(permission)) {
            throw ToolError("Jarvis'in $what izni yok. Kullanıcı uygulama ayarlarından izni vermeli.")
        }
    }

    private fun Map<String, Any?>.string(key: String): String =
        (this[key] as? String)?.takeIf { it.isNotBlank() } ?: throw ToolError("'$key' alanı eksik")

    private fun Map<String, Any?>.optString(key: String): String? =
        (this[key] as? String)?.takeIf { it.isNotBlank() }

    private fun Map<String, Any?>.int(key: String): Int =
        (this[key] as? Number)?.toInt() ?: throw ToolError("'$key' alanı eksik veya sayı değil")

    /**
     * Aracı ana iş parçacığında çalıştırır; hatayı da modele geri gönderilecek metne çevirir.
     * Dönüş: (sonuç metni, hata mı)
     */
    suspend fun run(name: String, input: Map<String, Any?>): Pair<String, Boolean> = try {
        withContext(Dispatchers.Main) { execute(name, input) } to false
    } catch (e: ToolError) {
        (e.message ?: "Hata") to true
    } catch (e: Exception) {
        "Araç çalışırken hata oluştu: ${e.message}" to true
    }

    class ToolError(message: String) : Exception(message)

    private companion object {
        fun str(description: String) = mapOf("type" to "string", "description" to description)
        fun int(description: String) = mapOf("type" to "integer", "description" to description)
        fun bool(description: String) = mapOf("type" to "boolean", "description" to description)
        fun enum(description: String, values: List<String>) =
            mapOf("type" to "string", "description" to description, "enum" to values)

        fun tool(
            name: String,
            description: String,
            properties: Map<String, Map<String, Any>>,
            required: List<String>,
        ) = ToolSpec(name, description, properties, required)
    }
}

/** Yapay zekâ sağlayıcısından bağımsız araç tanımı (JSON Schema ile). */
data class ToolSpec(
    val name: String,
    val description: String,
    val properties: Map<String, Map<String, Any>>,
    val required: List<String>,
) {
    fun toClaudeTool(): Tool {
        val props = Tool.InputSchema.Properties.builder()
        properties.forEach { (key, schema) -> props.putAdditionalProperty(key, JsonValue.from(schema)) }
        return Tool.builder()
            .name(name)
            .description(description)
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(props.build())
                    .required(required)
                    .build(),
            )
            .build()
    }

    fun toGeminiJson(): JSONObject = JSONObject()
        .put("type", "function")
        .put("name", name)
        .put("description", description)
        .put(
            "parameters",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject(properties))
                .put("required", JSONArray(required)),
        )
}
