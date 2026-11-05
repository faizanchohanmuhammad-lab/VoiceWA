package com.faizan.voicewa
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
/**
 * Drives WhatsApp: open -> search -> pick contact -> type message -> tap send.
 *
 * Design for resilience:
 *  - A small state machine advanced by a ticker (every 700 ms) AND by window events.
 *  - Every lookup tries (1) resource-id, (2) content-description, (3) visible text, (4) class/structure.
 *  - Every tick is wrapped in try/catch: a changed UI can only cause a retry or a clean
 *    "failed" status, never a crash.
 *  - Global timeout so the automation never hangs.
 */
class MyAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "VoiceWA"
        private const val TICK_MS = 700L
        private const val TASK_TIMEOUT_MS = 45_000L
        @Volatile
        var instance: MyAccessibilityService? = null
        /** Called on the main thread with progress / result text. */
        @Volatile
        var statusListener: ((String) -> Unit)? = null
        val WA_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
        private val SEARCH_DESCS = listOf("Search", "تلاش کریں", "تلاش", "Buscar", "Pesquisar", "Cari", "खोजें")
        private val SEND_DESCS = listOf("Send", "بھیجیں", "ارسال", "Enviar", "Kirim", "भेजें")
    }
    private enum class Step { IDLE, OPEN_SEARCH, TYPE_SEARCH, PICK_CONTACT, TYPE_MESSAGE, TAP_SEND }
    private val handler = Handler(Looper.getMainLooper())
    private var phoneController: PhoneController? = null
    /** General phone control (AI agent). */
    val phone: PhoneController
        get() = phoneController ?: PhoneController(this).also { phoneController = it }
    private val tickRunnable = Runnable { tick() }
    private var step = Step.IDLE
    private var contact = ""
    private var message = ""
    private var waPkg = "com.whatsapp"
    private var startedAt = 0L
    private var stepStartedAt = 0L
    private var lastTick = 0L
    private var errorCount = 0
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (step != Step.IDLE && SystemClock.elapsedRealtime() - lastTick > 400) {
            scheduleTick(150)
        }
    }
    override fun onInterrupt() {
    }
    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        handler.removeCallbacksAndMessages(null)
        phoneController?.shutdown()
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        phoneController?.shutdown()
        super.onDestroy()
    }
    /** Entry point used by MainActivity. */
    fun startTask(contactName: String, text: String) {
        val pkg = installedWhatsApp()
        if (pkg == null) {
            report("❌ WhatsApp is not installed.")
            return
        }
        waPkg = pkg
        contact = contactName.trim()
        message = text.trim()
        errorCount = 0
        startedAt = SystemClock.elapsedRealtime()
        stepStartedAt = startedAt
        step = Step.OPEN_SEARCH
        report("Opening WhatsApp…")
        if (!openWhatsApp()) {
            fail("Could not launch WhatsApp.")
            return
        }
        scheduleTick(1500)
    }
    /** 1) Open WhatsApp */
    fun openWhatsApp(): Boolean = try {
        val intent = packageManager.getLaunchIntentForPackage(waPkg)
        if (intent == null) {
            false
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(intent)
            true
        }
    } catch (t: Throwable) {
        Log.e(TAG, "openWhatsApp failed", t)
        false
    }
    /** 2) Search for the contact: click search icon (if needed) and type the name. */
    fun searchContact(root: AccessibilityNodeInfo, name: String): Boolean {
        var field = findSearchField(root)
        if (field == null) {
            val btn = findById(root, "menuitem_search") ?: findByDesc(root, SEARCH_DESCS)
            if (btn != null) click(btn)
            return false // field appears on a later tick
        }
        return setText(field, name)
    }
    /** 3) Fill the message input field. */
    fun fillInput(root: AccessibilityNodeInfo, text: String): Boolean {
        val field = findMessageField(root) ?: return false
        return setText(field, text)
    }
    /** 4) Tap the send button. */
    fun tapSend(root: AccessibilityNodeInfo): Boolean {
        val btn = findById(root, "send")
            ?: findByDesc(root, SEND_DESCS)
            ?: findByText(root, "Send")
            ?: return false
        return click(btn)
    }
    private fun scheduleTick(delay: Long = TICK_MS) {
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, delay)
    }
    private fun goTo(next: Step) {
        step = next
        stepStartedAt = SystemClock.elapsedRealtime()
    }
    private fun tick() {
        if (step == Step.IDLE) return
        lastTick = SystemClock.elapsedRealtime()
        if (lastTick - startedAt > TASK_TIMEOUT_MS) {
            fail("Timed out at step $step. WhatsApp's screen may have changed.")
            return
        }
        try {
            val root = rootInActiveWindow
            val pkg = root?.packageName?.toString()
            if (root == null || pkg != waPkg) {
                if (lastTick - stepStartedAt > 6000) {
                    openWhatsApp()
                    stepStartedAt = lastTick
                }
            } else {
                val elapsed = lastTick - stepStartedAt
                when (step) {
                    Step.OPEN_SEARCH -> {
                        report("Looking for search…")
                        when {
                            findSearchField(root) != null -> goTo(Step.TYPE_SEARCH)
                            findMessageField(root) != null -> {
                                performGlobalAction(GLOBAL_ACTION_BACK)
                            }
                            else -> {
                                val btn = findById(root, "menuitem_search") ?: findByDesc(root, SEARCH_DESCS)
                                if (btn != null && click(btn)) goTo(Step.TYPE_SEARCH)
                                else if (elapsed > 12_000) fail("Search button not found (WhatsApp UI changed?).")
                            }
                        }
                    }
                    Step.TYPE_SEARCH -> {
                        val field = findSearchField(root)
                        if (field == null) {
                            if (elapsed > 5000) goTo(Step.OPEN_SEARCH)
                        } else if (setText(field, contact)) {
                            report("Searching “$contact”…")
                            goTo(Step.PICK_CONTACT)
                        }
                    }
                    Step.PICK_CONTACT -> {
                        if (elapsed < 900) {
                        } else {
                            val target = findContactResult(root, contact)
                            if (target != null && click(target)) {
                                goTo(Step.TYPE_MESSAGE)
                            } else if (elapsed > 9000) {
                                fail("Contact “$contact” not found. Check the spelling or the saved name.")
                            }
                        }
                    }
                    Step.TYPE_MESSAGE -> {
                        if (findSearchField(root) == null && elapsed > 600) {
                            if (fillInput(root, message)) {
                                report("Typing message…")
                                goTo(Step.TAP_SEND)
                            } else if (elapsed > 10_000) {
                                fail("Message box not found (WhatsApp UI changed?).")
                            }
                        } else if (elapsed > 10_000) {
                            fail("Chat did not open.")
                        }
                    }
                    Step.TAP_SEND -> {
                        if (elapsed > 500) { // give the send button time to replace the mic
                            if (tapSend(root)) {
                                finish("✅ Message sent to $contact")
                            } else if (elapsed > 8000) {
                                fail("Send button not found (WhatsApp UI changed?).")
                            }
                        }
                    }
                    Step.IDLE -> Unit
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "tick error at $step", t)
            errorCount++
            if (errorCount > 6) fail("Unexpected error: ${t.message}")
        }
        if (step != Step.IDLE) scheduleTick()
    }
    private fun finish(msg: String) {
        step = Step.IDLE
        handler.removeCallbacks(tickRunnable)
        report(msg)
    }
    private fun fail(msg: String) {
        step = Step.IDLE
        handler.removeCallbacks(tickRunnable)
        report("❌ $msg")
    }
    fun report(msg: String) {
        Log.i(TAG, msg)
        try {
            statusListener?.invoke(msg)
        } catch (t: Throwable) {
            Log.w(TAG, "status listener failed", t)
        }
    }
    @Suppress("DEPRECATION")
    private fun installedWhatsApp(): String? {
        for (p in WA_PACKAGES) {
            try {
                packageManager.getPackageInfo(p, 0)
                return p
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        return null
    }
    /** Resource id lookup, e.g. name="entry" -> "com.whatsapp:id/entry" (works for WhatsApp Business too). */
    private fun findById(root: AccessibilityNodeInfo, name: String): AccessibilityNodeInfo? =
        try {
            root.findAccessibilityNodeInfosByViewId("$waPkg:id/$name").firstOrNull()
        } catch (_: Throwable) {
            null
        }
    private fun findAllById(root: AccessibilityNodeInfo, name: String): List<AccessibilityNodeInfo> =
        try {
            root.findAccessibilityNodeInfosByViewId("$waPkg:id/$name") ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    private fun findByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? =
        try {
            root.findAccessibilityNodeInfosByText(text)?.firstOrNull {
                !it.isEditable && it.className?.toString()?.endsWith("EditText") != true
            }
        } catch (_: Throwable) {
            null
        }
    private fun findByDesc(node: AccessibilityNodeInfo?, descs: List<String>, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > 25) return null
        val d = node.contentDescription?.toString()
        if (d != null && descs.any { d.equals(it, ignoreCase = true) }) return node
        for (i in 0 until node.childCount) {
            val r = findByDesc(node.getChild(i), descs, depth + 1)
            if (r != null) return r
        }
        return null
    }
    private fun collectEditTexts(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (node == null || depth > 25) return
        if (node.className?.toString()?.endsWith("EditText") == true) out.add(node)
        for (i in 0 until node.childCount) collectEditTexts(node.getChild(i), out, depth + 1)
    }
    /** Search box: id search_input, else any EditText whose id mentions "search". */
    private fun findSearchField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        findById(root, "search_input")?.let { return it }
        val edits = mutableListOf<AccessibilityNodeInfo>()
        collectEditTexts(root, edits)
        return edits.firstOrNull { it.viewIdResourceName?.contains("search", true) == true }
    }
    /** Message box: id entry, else any EditText that is not the search box. */
    private fun findMessageField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        findById(root, "entry")?.let { return it }
        val edits = mutableListOf<AccessibilityNodeInfo>()
        collectEditTexts(root, edits)
        return edits.firstOrNull { it.viewIdResourceName?.contains("search", true) != true }
    }
    /** Contact row in search results: known row ids first, then plain text match. */
    private fun findContactResult(root: AccessibilityNodeInfo, name: String): AccessibilityNodeInfo? {
        val wanted = name.trim().lowercase()
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        for (id in listOf("conversations_row_contact_name", "contactpicker_row_name", "contact_row_name")) {
            candidates.addAll(findAllById(root, id))
        }
        fun pick(list: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
            val named = list.filter { it.text != null }
            return named.firstOrNull { it.text.toString().trim().lowercase() == wanted }
                ?: named.firstOrNull { it.text.toString().lowercase().contains(wanted) }
        }
        pick(candidates)?.let { return it }
        val byText = try {
            root.findAccessibilityNodeInfosByText(name)?.filter {
                !it.isEditable && it.className?.toString()?.endsWith("EditText") != true
            } ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
        return pick(byText) ?: byText.firstOrNull()
    }
    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (t: Throwable) {
            Log.w(TAG, "setText failed", t)
            false
        }
    }
    /** Click the node or its nearest clickable ancestor; if that fails, tap its centre with a gesture. */
    private fun click(node: AccessibilityNodeInfo): Boolean {
        try {
            var n: AccessibilityNodeInfo? = node
            var depth = 0
            while (n != null && depth < 6) {
                if (n.isClickable && n.isEnabled && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                n = n.parent
                depth++
            }
        } catch (t: Throwable) {
            Log.w(TAG, "click failed", t)
        }
        return tapCenter(node)
    }
    private fun tapCenter(node: AccessibilityNodeInfo): Boolean {
        return try {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.isEmpty) return false
            val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (t: Throwable) {
            Log.w(TAG, "tapCenter failed", t)
            false
        }
    }
}
