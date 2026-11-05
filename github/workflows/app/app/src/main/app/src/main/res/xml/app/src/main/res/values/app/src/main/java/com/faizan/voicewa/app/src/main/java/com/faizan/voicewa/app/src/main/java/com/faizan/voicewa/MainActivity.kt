package com.faizan.voicewa
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
class MainActivity : Activity() {
    private data class Command(val action: String, val contact: String, val message: String, val goal: String = "")
    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }
    private lateinit var micButton: Button
    private lateinit var statusText: TextView
    private lateinit var keyInput: EditText
    private lateinit var rbEnglish: RadioButton
    private lateinit var rbUrdu: RadioButton
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private val reqAudio = 101
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        MyAccessibilityService.statusListener = { msg -> runOnUiThread { setStatus(msg) } }
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(32), dp(20), dp(20))
        }
        root.addView(TextView(this).apply {
            text = "Voice Phone Control"
            textSize = 26f
            gravity = Gravity.CENTER
        })
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            gravity = Gravity.CENTER
        }
        rbEnglish = RadioButton(this).apply { text = "English (en-US)"; id = View.generateViewId() }
        rbUrdu = RadioButton(this).apply { text = "اردو (ur-PK)"; id = View.generateViewId() }
        group.addView(rbEnglish)
        group.addView(rbUrdu)
        if (prefs.getString("lang", "en-US") == "ur-PK") rbUrdu.isChecked = true else rbEnglish.isChecked = true
        group.setOnCheckedChangeListener { _, checkedId ->
            prefs.edit().putString("lang", if (checkedId == rbUrdu.id) "ur-PK" else "en-US").apply()
        }
        root.addView(group)
        keyInput = EditText(this).apply {
            hint = "OpenAI API key (sk-...)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("openai_key", ""))
        }
        root.addView(keyInput)
        micButton = Button(this).apply {
            text = "🎤  Tap & speak"
            textSize = 20f
            setOnClickListener { onMicClicked() }
        }
        root.addView(micButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(80)
        ).apply { topMargin = dp(20) })
        statusText = TextView(this).apply {
            text = "Try: \"open Chrome and search weather\" or \"send a message to Ali saying hello\""
            textSize = 16f
            setPadding(0, dp(20), 0, dp(20))
        }
        root.addView(statusText)
        root.addView(Button(this).apply {
            text = "Enable Accessibility Service"
            setOnClickListener { showAccessibilityGuide() }
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }
    private fun setStatus(s: String) {
        statusText.text = s
    }
    private fun onMicClicked() {
        if (listening) {
            stopListening()
            return
        }
        prefs.edit().putString("openai_key", keyInput.text.toString().trim()).apply()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                AlertDialog.Builder(this)
                    .setTitle("Microphone needed")
                    .setMessage("The app needs the microphone to hear your command.")
                    .setPositiveButton("Allow") { _, _ ->
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), reqAudio)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), reqAudio)
            }
            return
        }
        startListening()
    }
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code != reqAudio) return
        if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            AlertDialog.Builder(this)
                .setTitle("Microphone blocked")
                .setMessage("Open App settings > Permissions and allow Microphone.")
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.parse("package:$packageName"))
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            setStatus("Microphone permission denied.")
        }
    }
    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("Speech recognition is not available. Install/enable \"Speech Recognition & Synthesis\" by Google.")
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(listener)
        }
        val lang = prefs.getString("lang", "en-US") ?: "en-US"
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        listening = true
        micButton.text = "⏹  Listening… tap to stop"
        setStatus("Listening ($lang)…")
        recognizer?.startListening(intent)
    }
    private fun stopListening() {
        recognizer?.stopListening()
        listening = false
        micButton.text = "🎤  Tap & speak"
    }
    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) setStatus("Hearing: $t")
        }
        override fun onResults(results: Bundle?) {
            listening = false
            micButton.text = "🎤  Tap & speak"
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) setStatus("Didn't catch that. Try again.") else handleSpeech(text)
        }
        override fun onError(error: Int) {
            listening = false
            micButton.text = "🎤  Tap & speak"
            setStatus(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't hear anything. Try again."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error in speech recognition."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission missing."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy, try again."
                    else -> "Speech error code $error"
                }
            )
        }
    }
    private fun handleSpeech(text: String) {
        setStatus("Heard: $text\nUnderstanding…")
        val key = prefs.getString("openai_key", "") ?: ""
        Thread {
            val cmd: Command? = try {
                if (key.isBlank()) parseLocally(text) else parseWithOpenAI(text, key)
            } catch (e: Exception) {
                parseLocally(text)
            }
            runOnUiThread { execute(cmd, text) }
        }.start()
    }
    private val systemPrompt = """
        You convert a spoken command (English, Urdu script, or Roman Urdu) into JSON. Reply with JSON only.
        1) If the user wants to send a WhatsApp message to a person:
           {"action":"send_whatsapp","contact":"<name only>","message":"<text only, same language as spoken, without 'saying'/'that'/'کہ'>"}
        2) For ANY other request to operate the phone (open an app, search something, change a setting, scroll, read, call, etc.):
           {"action":"phone_task","goal":"<the request rewritten as one clear English instruction>"}
        3) If it is not a phone request at all: {"action":"unknown"}
        Do not translate message text.
    """.trimIndent()
    private fun parseWithOpenAI(text: String, apiKey: String): Command? {
        val body = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("temperature", 0)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", text))
            })
        }
        val conn = URL("https://api.openai.com/v1/chat/completions").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) throw RuntimeException("OpenAI HTTP $code: $resp")
            val content = JSONObject(resp)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
            val j = JSONObject(content)
            return Command(
                j.optString("action"),
                j.optString("contact").trim(),
                j.optString("message").trim(),
                j.optString("goal").trim()
            )
        } finally {
            conn.disconnect()
        }
    }
    /** Offline English-only fallback so the app still works without internet or an API key. */
    private fun parseLocally(text: String): Command? {
        val re = Regex(
            """^\s*(?:send|text|message)\s+(?:a\s+)?(?:whatsapp\s+)?(?:message\s+)?(?:to\s+)?(.+?)\s+(?:saying|say|that says|that|with message)\s+(.+)$""",
            RegexOption.IGNORE_CASE
        )
        val m = re.find(text) ?: return null
        return Command("send_whatsapp", m.groupValues[1].trim(), m.groupValues[2].trim())
    }
    private fun execute(cmd: Command?, heard: String) {
        if (cmd == null) {
            setStatus("Heard: $heard\nI couldn't understand. (Without an OpenAI key only the English form \"send a message to X saying Y\" works.)")
            return
        }
        val service = MyAccessibilityService.instance
        if (service == null) {
            showAccessibilityGuide()
            return
        }
        when {
            cmd.action == "send_whatsapp" && cmd.contact.isNotBlank() && cmd.message.isNotBlank() -> {
                setStatus("To: ${cmd.contact}\nMessage: ${cmd.message}\nStarting WhatsApp…")
                service.startTask(cmd.contact, cmd.message)
            }
            cmd.action == "phone_task" && cmd.goal.isNotBlank() -> {
                val key = prefs.getString("openai_key", "") ?: ""
                if (key.isBlank()) {
                    setStatus("Phone control needs an OpenAI API key (paste it above).")
                    return
                }
                setStatus("Task: ${cmd.goal}\nRunning… use the Stop button shown on screen.")
                service.phone.run(cmd.goal, key)
            }
            else -> setStatus("Heard: $heard\nI couldn't turn that into a phone action. Try again.")
        }
    }
    private fun showAccessibilityGuide() {
        AlertDialog.Builder(this)
            .setTitle("Enable accessibility service")
            .setMessage(
                "1. Tap \"Open settings\".\n" +
                    "2. Open Installed apps / Downloaded apps > Voice Phone Control.\n" +
                    "3. Turn it ON and confirm.\n\n" +
                    "If the switch is greyed out (Android 13+ for sideloaded APKs): Settings > Apps > Voice Phone Control > ⋮ menu > \"Allow restricted settings\", then try again."
            )
            .setPositiveButton("Open settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNeutralButton("App info") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.parse("package:$packageName"))
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        MyAccessibilityService.statusListener = null
        super.onDestroy()
    }
}
