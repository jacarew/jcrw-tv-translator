package com.jcrw.translator

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper

internal object ShortcutState { @Volatile var blocked = false }

/** Global gamepad shortcut and lightweight in-game translator quick panel. */
class QuickMenuAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "JcrwQuickShortcut"
        private val CONFIRM_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_X)
        private val EDITOR_ARROWS = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
        @Volatile private var activeService: QuickMenuAccessibilityService? = null
        fun restorePanelAfterStart() {
            val service = activeService ?: return
            service.mainHandler.postDelayed({
                if (service.panel == null && service.prefs.quickShortcutEnabled) service.showPanel()
            }, 250L)
        }
    }
    private lateinit var prefs: CompanionPreferences
    private lateinit var wm: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var panel: View? = null
    private var translationButton: Button? = null
    private var speechButton: Button? = null
    private var providerButton: Button? = null
    private var sourceButton: Button? = null
    private var scanIntervalButton: Button? = null
    private var editorOverlay: View? = null
    private var editorKind: EditorKind? = null
    private var confirmDownAt = 0L
    private var resizeRegion = false
    private val editorConsumedKeys = HashSet<Int>()
    private enum class EditorKind { REGION, CAPTION_POSITION }
    private fun tx(tr:String,en:String)=if(prefs.uiLanguage=="en")en else tr

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        prefs = CompanionPreferences(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        // Some TV firmware ignores the XML filter flag until it is applied to
        // the connected service instance. Keep the declared capability and
        // explicitly request key filtering after binding as well.
        serviceInfo = serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        Log.i(TAG, "connected; shortcutEnabled=${prefs.quickShortcutEnabled}; keyCode=${prefs.quickShortcutKeyCode}")
    }
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (::prefs.isInitialized && event.keyCode == prefs.quickShortcutKeyCode) {
            Log.i(TAG, "assigned key received; action=${event.action}; repeats=${event.repeatCount}")
        }
        if (event.action == KeyEvent.ACTION_UP && editorConsumedKeys.remove(event.keyCode)) return true
        if (editorOverlay != null) {
            val handled = handleEditorKey(event)
            if (event.action == KeyEvent.ACTION_DOWN && handled) editorConsumedKeys.add(event.keyCode)
            return handled
        }
        if (event.action == KeyEvent.ACTION_DOWN && panel != null && event.keyCode == KeyEvent.KEYCODE_BACK) { hidePanel(); return true }
        if (!prefs.quickShortcutEnabled || ShortcutState.blocked || panel != null || event.keyCode != prefs.quickShortcutKeyCode) return false
        // This is an explicitly assigned spare key: consume both edges so the foreground
        // app does not also activate its normal action (for example, IPTV's OK selection).
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) showPanel()
        return event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP
    }

    private fun showPanel() {
        if (panel != null) return
        val density = resources.displayMetrics.density
        val baseWidth = (300 * density).toInt()
        val panelPadding = (16 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(panelPadding, panelPadding, panelPadding, panelPadding)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xC91B3150.toInt(), 0xBD13233D.toInt())).apply {
                cornerRadius = 30 * density
                setStroke((1.5f * density).toInt(), 0x99D9F4FF.toInt())
            }
            elevation = 18 * density
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((4 * density).toInt(), 0, (4 * density).toInt(), (12 * density).toInt())
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.app_logo)
            contentDescription = tx("Jcrw uygulama simgesi", "Jcrw app icon")
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                cornerRadius = 16 * density
                setStroke((1.5f * density).toInt(), 0x99F34CA9.toInt())
            }
            clipToOutline = true
        }, LinearLayout.LayoutParams((52 * density).toInt(), (52 * density).toInt()))
        val titleBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((12 * density).toInt(), 0, 0, 0) }
        titleBlock.addView(TextView(this).apply {
            text = "Jcrw Translator"
            textSize = 21f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            letterSpacing = .015f
        })
        titleBlock.addView(TextView(this).apply {
            text = tx("HIZLI ÇEVİRİ MENÜSÜ", "TRANSLATION QUICK MENU")
            textSize = 11f
            setTextColor(0xFFFF9AD0.toInt())
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            letterSpacing = .08f
            setPadding(0, (3 * density).toInt(), 0, 0)
        })
        header.addView(titleBlock, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)
        val buttons = mutableListOf<Button>()
        buttons += addButton(root, 0, baseWidth, density, tx("Altyazı alanını taşı / boyutlandır", "Move / resize subtitle area")) { launchRegionPicker() }
        buttons += addButton(root, 1, baseWidth, density, tx("Çevrilen yazının konumunu ayarla", "Adjust translated caption position")) { launchCaptionPositionPicker() }
        translationButton = addButton(root, 2, baseWidth, density, "") { toggleTranslation() }
        buttons += translationButton!!
        speechButton = addButton(root, 3, baseWidth, density, "") { toggleSpeech() }
        buttons += speechButton!!
        providerButton = addButton(root, 4, baseWidth, density, "") { cycleProvider(); updateDynamicButtonLabels() }
        buttons += providerButton!!
        sourceButton = addButton(root, 5, baseWidth, density, "") { cycleSourceLanguage(); updateDynamicButtonLabels() }
        buttons += sourceButton!!
        scanIntervalButton = addButton(root, 6, baseWidth, density, "") { cycleScanInterval(); updateDynamicButtonLabels() }
        buttons += scanIntervalButton!!
        updateDynamicButtonLabels()
        buttons += addButton(root, 7, baseWidth, density, tx("Menüyü kapat", "Close menu")) { hidePanel() }
        buttons.forEach { button ->
            button.setOnFocusChangeListener { _, focused ->
                val targetWidth = baseWidth + if (focused) (55 * density).toInt() else 0
                ValueAnimator.ofInt(button.layoutParams.width, targetWidth).apply {
                    duration = 170
                    addUpdateListener { animator ->
                        button.layoutParams = button.layoutParams.apply { width = animator.animatedValue as Int }
                        button.requestLayout()
                    }
                    start()
                }
            }
        }
        val panelHolder = FrameLayout(this).apply {
            setPadding((32 * density).toInt(), 0, 0, 0)
            addView(root, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START))
        }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, android.graphics.PixelFormat.TRANSLUCENT).apply { gravity = Gravity.START or Gravity.TOP }
        try {
            wm.addView(panelHolder, params)
            panel = panelHolder
            root.getChildAt(1)?.requestFocus()
            Log.i(TAG, "quick menu shown")
        } catch (error: Exception) {
            panel = null
            Log.e(TAG, "could not show quick menu", error)
        }
    }

    private fun addButton(parent: LinearLayout, index: Int, baseWidth: Int, density: Float, label: String, action: () -> Unit): Button {
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            isFocusable = true
            isFocusableInTouchMode = true
            gravity = Gravity.CENTER
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFFF34CA9.toInt(), 0xFFB52B83.toInt())).apply { cornerRadius = 18 * density; setStroke((2 * density).toInt(), 0xFFF6FCFF.toInt()) })
                addState(intArrayOf(), GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xB8204264.toInt(), 0xA91C3151.toInt())).apply { cornerRadius = 18 * density; setStroke((1 * density).toInt(), 0x77D6F2FF.toInt()) })
            }
            setOnClickListener { action() }
        }
        parent.addView(button, LinearLayout.LayoutParams(baseWidth, (44 * density).toInt()).apply {
            bottomMargin = (5 * density).toInt()
        })
        return button
    }

    private fun toggleTranslation() {
        if (prefs.captureRunning) {
            startService(Intent(this, TranslatorService::class.java).setAction(TranslatorService.ACTION_PAUSE))
            prefs.captureRunning = false
            prefs.captureStatus = "Çeviri durduruldu"
            updateDynamicButtonLabels()
        } else if (TranslatorService.projectionSessionAvailable) {
            prefs.captureRunning = true
            startService(Intent(this, TranslatorService::class.java).setAction(TranslatorService.ACTION_RESUME))
            updateDynamicButtonLabels()
        } else {
            hidePanel()
            try {
                startActivity(Intent(this, QuickStartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            } catch (error: Exception) {
                Log.e(TAG, "could not start quick translation", error)
                restorePanelAfterStart()
            }
        }
    }

    private fun toggleSpeech() {
        prefs.speechEnabled = !prefs.speechEnabled
        if (!prefs.speechEnabled && prefs.captureRunning) startService(Intent(this, TranslatorService::class.java).setAction(TranslatorService.ACTION_SPEECH_STOP))
        updateDynamicButtonLabels()
    }

    private fun cycleSourceLanguage() {
        prefs.subtitleSourceLanguage = when (prefs.subtitleSourceLanguage) { "en" -> "ja"; "ja" -> "auto"; else -> "en" }
    }

    private fun cycleScanInterval() {
        val options = listOf(500, 750, 1000, 1500, 2000, 3000)
        prefs.scanIntervalMs = options[(options.indexOf(prefs.scanIntervalMs).coerceAtLeast(0) + 1) % options.size]
    }

    private fun scanIntervalLabel(): String {
        val seconds = prefs.scanIntervalMs / 1000.0
        val number = if (prefs.uiLanguage == "en") String.format(java.util.Locale.US, "%.2f", seconds).trimEnd('0').trimEnd('.') else String.format(java.util.Locale("tr"), "%.2f", seconds).trimEnd('0').trimEnd(',')
        return tx("OCR aralığı: ${number} sn • değiştir", "OCR interval: ${number} sec • change")
    }

    private fun updateDynamicButtonLabels() {
        translationButton?.text = if (prefs.captureRunning) tx("Çeviriyi durdur", "Stop translation") else tx("Çeviriyi başlat", "Start translation")
        speechButton?.text = if (prefs.speechEnabled) tx("Sesli çeviriyi kapat", "Turn voice-over off") else tx("Sesli çeviriyi aç", "Turn voice-over on")
        providerButton?.text = tx("Çeviri kaynağı: ${providerName()}", "Translation provider: ${providerName()}")
        sourceButton?.text = tx("Kaynak dili: ${prefs.subtitleSourceLanguage.uppercase()} • Değiştir", "Source language: ${prefs.subtitleSourceLanguage.uppercase()} • Change")
        scanIntervalButton?.text = scanIntervalLabel()
    }

    private fun launchRegionPicker() { hidePanel(); showEditor(EditorKind.REGION) }
    private fun launchCaptionPositionPicker() { hidePanel(); showEditor(EditorKind.CAPTION_POSITION) }

    /** Keep the foreground player resumed: draw editor controls in an accessibility overlay,
     * rather than launching an Activity that makes video apps pause or blank their surface. */
    private fun showEditor(kind: EditorKind) {
        if (editorOverlay != null) return
        editorKind = kind
        resizeRegion = false
        val view = EditorOverlay(kind)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try {
            wm.addView(view, params)
            editorOverlay = view
            view.requestFocus()
        } catch (error: Exception) {
            editorKind = null
            Log.e(TAG, "could not show editor overlay", error)
            showPanel()
        }
    }

    private fun handleEditorKey(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) { finishEditor(); return true }
            if (event.keyCode in CONFIRM_KEYS) {
                if (event.repeatCount == 0) confirmDownAt = SystemClock.uptimeMillis()
                return true
            }
            val view = editorOverlay as? EditorOverlay ?: return false
            val step = if (event.isShiftPressed) .02f else if (editorKind == EditorKind.REGION) .01f else .005f
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> view.adjust(-step, 0f)
                KeyEvent.KEYCODE_DPAD_RIGHT -> view.adjust(step, 0f)
                KeyEvent.KEYCODE_DPAD_UP -> view.adjust(0f, -step)
                KeyEvent.KEYCODE_DPAD_DOWN -> view.adjust(0f, step)
                else -> return false
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && event.keyCode in CONFIRM_KEYS) {
            val held = SystemClock.uptimeMillis() - confirmDownAt
            if (held > 650L || editorKind == EditorKind.CAPTION_POSITION) finishEditor()
            else {
                resizeRegion = !resizeRegion
                editorOverlay?.invalidate()
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && event.keyCode in EDITOR_ARROWS) return true
        return event.keyCode in CONFIRM_KEYS
    }

    private fun finishEditor() {
        val view = editorOverlay as? EditorOverlay
        when (editorKind) {
            EditorKind.REGION -> prefs.subtitleRegion = view?.region ?: prefs.subtitleRegion
            EditorKind.CAPTION_POSITION -> {
                view?.let { prefs.overlayX = it.xPos; prefs.overlayY = it.yPos }
            }
            null -> Unit
        }
        try { view?.let { wm.removeView(it) } } catch (_: Exception) {}
        editorOverlay = null
        editorKind = null
        Toast.makeText(this, tx("Konum ayarı kaydedildi", "Position saved"), Toast.LENGTH_SHORT).show()
    }

    private inner class EditorOverlay(private val kind: EditorKind) : View(this) {
        var region = RectF(prefs.subtitleRegion)
        var xPos = prefs.overlayX
        var yPos = prefs.overlayY
        private val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3342A5F5 }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD54F.toInt(); style = Paint.Style.STROKE; strokeWidth = 4f }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 23f; setShadowLayer(4f, 1f, 1f, Color.BLACK) }
        private val previewBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = prefs.captionBackgroundColor }
        private val previewText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = prefs.captionTextColor
            textSize = prefs.captionTextSize * resources.displayMetrics.scaledDensity
            typeface = android.graphics.Typeface.create(prefs.captionFont, android.graphics.Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private val bounds = RectF()
        private var lastX = 0f
        private var lastY = 0f
        private var touchResize = false

        init {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
            handleEditorKey(event).takeIf { it } ?: super.onKeyDown(keyCode, event)

        override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
            handleEditorKey(event).takeIf { it } ?: super.onKeyUp(keyCode, event)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(tint.color)
            if (kind == EditorKind.REGION) {
                bounds.set(region.left * width, region.top * height, region.right * width, region.bottom * height)
                canvas.drawRoundRect(bounds, 12f, 12f, fill)
                canvas.drawRoundRect(bounds, 12f, 12f, border)
                canvas.drawRoundRect(bounds.right - 24f, bounds.bottom - 24f, bounds.right + 8f, bounds.bottom + 8f, 5f, 5f, border)
                val hint = if (prefs.uiLanguage == "en") "Inside: move • lower-right: resize • OK: mode • hold OK / BACK: save" else "İçinden: taşı • sağ alt: boyutlandır • OK: mod • OK basılı / BACK: kaydet"
                canvas.drawText(hint, 24f, 42f, label)
                canvas.drawText("Genişlik %${"%.0f".format(region.width() * 100)}  Yükseklik %${"%.0f".format(region.height() * 100)}  ${if (resizeRegion) "BOYUT" else "TAŞI"}", 24f, 76f, label)
            } else {
                val cx = xPos * width
                val cy = yPos * height
                val sample = if (prefs.uiLanguage == "en") "Translated subtitle appears here" else "Çevrilmiş altyazı burada görünür"
                val measured = previewText.measureText(sample).coerceAtMost(width * .84f)
                bounds.set(cx - measured / 2 - 24, cy - previewText.textSize - 16, cx + measured / 2 + 24, cy + 16)
                canvas.drawRoundRect(bounds, 14f, 14f, previewBackground)
                canvas.drawText(sample, cx, cy, previewText)
                canvas.drawText(if (prefs.uiLanguage == "en") "Move with D-pad or drag • OK / BACK to save" else "Yön tuşlarıyla veya sürükleyerek taşı • OK / BACK kaydet", 24f, 42f, label)
            }
        }

        fun adjust(dx: Float, dy: Float) {
            if (kind == EditorKind.REGION) {
                if (resizeRegion) {
                    region.right = (region.right + dx).coerceIn(region.left + .08f, 1f)
                    region.bottom = (region.bottom + dy).coerceIn(region.top + .04f, 1f)
                } else {
                    val w = region.width(); val h = region.height()
                    val left = (region.left + dx).coerceIn(0f, 1f - w)
                    val top = (region.top + dy).coerceIn(0f, 1f - h)
                    region = RectF(left, top, left + w, top + h)
                }
            } else {
                xPos = (xPos + dx).coerceIn(.08f, .92f)
                yPos = (yPos + dy).coerceIn(.08f, .92f)
            }
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x; lastY = event.y
                    touchResize = kind == EditorKind.REGION && event.x > region.right * width - 48 && event.y > region.bottom * height - 48
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.x - lastX) / width
                    val dy = (event.y - lastY) / height
                    if (kind == EditorKind.REGION) {
                        val priorMode = resizeRegion; resizeRegion = touchResize
                        adjust(dx, dy); resizeRegion = priorMode
                    } else adjust(dx, dy)
                    lastX = event.x; lastY = event.y
                    return true
                }
                MotionEvent.ACTION_UP -> { performClick(); return true }
            }
            return true
        }

        override fun performClick(): Boolean { super.performClick(); return true }
    }
    private fun cycleProvider() { val options = listOf("mymemory", "deepl", "openai", "gemini", "libretranslate"); prefs.translationProvider = options[(options.indexOf(prefs.translationProvider).coerceAtLeast(0) + 1) % options.size] }
    private fun providerName() = when (prefs.translationProvider) { "mymemory" -> "MyMemory • Free"; "deepl" -> "DeepL"; "openai" -> "OpenAI"; "gemini" -> "Gemini"; else -> "LibreTranslate" }
    private fun positionName() = when { prefs.overlayY < .3f -> "Üst"; prefs.overlayY < .62f -> "Orta"; else -> "Alt" }
    private fun hidePanel() {
        try { panel?.let { wm.removeView(it) } } catch (_: Exception) {}
        panel = null
        translationButton = null
        speechButton = null
        providerButton = null
        sourceButton = null
        scanIntervalButton = null
    }
    override fun onDestroy() { hidePanel(); try { editorOverlay?.let { wm.removeView(it) } } catch (_: Exception) {}; editorOverlay = null; if (activeService === this) activeService = null; super.onDestroy() }

}
