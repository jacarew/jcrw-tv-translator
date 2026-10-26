package com.jcrw.translator

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Log
import android.animation.ValueAnimator

internal object ShortcutState { @Volatile var blocked = false }

/** Global gamepad shortcut and lightweight in-game translator quick panel. */
class QuickMenuAccessibilityService : AccessibilityService() {
    companion object { private const val TAG = "JcrwQuickShortcut" }
    private lateinit var prefs: CompanionPreferences
    private lateinit var wm: WindowManager
    private var panel: View? = null
    private fun tx(tr:String,en:String)=if(prefs.uiLanguage=="en")en else tr

    override fun onServiceConnected() {
        super.onServiceConnected()
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
        root.addView(TextView(this).apply {
            text = tx("Jcrw • HIZLI ÇEVİRİ MENÜSÜ", "Jcrw • TRANSLATION QUICK MENU")
            textSize = 19f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding((6 * density).toInt(), 0, 0, (8 * density).toInt())
        })
        val buttons = mutableListOf<Button>()
        buttons += addButton(root, 0, baseWidth, density, tx("Altyazı alanını taşı / boyutlandır", "Move / resize subtitle area")) { launchRegionPicker() }
        buttons += addButton(root, 1, baseWidth, density, tx("Çevrilen yazının konumunu ayarla", "Adjust translated caption position")) { launchCaptionPositionPicker() }
        buttons += addButton(root, 2, baseWidth, density, if (prefs.captureRunning) tx("Çeviriyi durdur", "Stop translation") else tx("Çeviriyi başlat", "Start translation")) { toggleTranslation() }
        buttons += addButton(root, 3, baseWidth, density, if (prefs.speechEnabled) tx("Sesli çeviriyi kapat", "Turn voice-over off") else tx("Sesli çeviriyi aç", "Turn voice-over on")) { prefs.speechEnabled = !prefs.speechEnabled; if (!prefs.speechEnabled && prefs.captureRunning) startService(Intent(this, TranslatorService::class.java).setAction(TranslatorService.ACTION_SPEECH_STOP)); hidePanel(); showPanel() }
        buttons += addButton(root, 4, baseWidth, density, tx("Çeviri kaynağı: ${providerName()}", "Translation provider: ${providerName()}")) { cycleProvider(); hidePanel(); showPanel() }
        buttons += addButton(root, 5, baseWidth, density, tx("Kaynak dili: ${prefs.subtitleSourceLanguage.uppercase()} • Değiştir", "Source language: ${prefs.subtitleSourceLanguage.uppercase()} • Change")) { prefs.subtitleSourceLanguage = if (prefs.subtitleSourceLanguage == "en") "ja" else if (prefs.subtitleSourceLanguage == "ja") "auto" else "en"; hidePanel(); showPanel() }
        buttons += addButton(root, 6, baseWidth, density, tx("Menüyü kapat", "Close menu")) { hidePanel() }
        buttons.forEachIndexed { index, button ->
            button.setOnFocusChangeListener { _, focused ->
                val targetWidth = baseWidth + if (focused) (55 * density).toInt() else 0
                ValueAnimator.ofInt(button.layoutParams.width, targetWidth).apply {
                    duration = 170
                    addUpdateListener { animator ->
                        button.layoutParams = button.layoutParams.apply { width = animator.animatedValue as Int }
                        button.requestLayout()
                        updateCardWidth(root, buttons, baseWidth, panelPadding)
                    }
                    start()
                }
                updateCardWidth(root, buttons, baseWidth, panelPadding)
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

    private fun updateCardWidth(root: LinearLayout, buttons: List<Button>, baseWidth: Int, padding: Int) {
        val widest = buttons.maxOfOrNull { it.width.takeIf { width -> width > 0 } ?: it.layoutParams.width } ?: baseWidth
        root.layoutParams = root.layoutParams?.apply { width = widest + padding * 2 }
        root.requestLayout()
    }

    private fun toggleTranslation() {
        if (prefs.captureRunning) {
            prefs.captureRunning = false
            prefs.captureStatus = "Çeviri durduruldu"
            startService(Intent(this, TranslatorService::class.java).setAction(TranslatorService.ACTION_STOP))
        } else {
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            i.putExtra(MainActivity.EXTRA_QUICK_START, true)
            startActivity(i)
        }
        hidePanel()
    }

    private fun launchRegionPicker() { hidePanel(); startActivity(Intent(this, RegionSelectionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    private fun launchCaptionPositionPicker() { hidePanel(); startActivity(Intent(this, CaptionPositionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    private fun cycleProvider() { val options = listOf("deepl", "openai", "gemini", "libretranslate"); prefs.translationProvider = options[(options.indexOf(prefs.translationProvider).coerceAtLeast(0) + 1) % options.size] }
    private fun providerName() = when (prefs.translationProvider) { "deepl" -> "DeepL"; "openai" -> "OpenAI"; "gemini" -> "Gemini"; else -> "LibreTranslate" }
    private fun positionName() = when { prefs.overlayY < .3f -> "Üst"; prefs.overlayY < .62f -> "Orta"; else -> "Alt" }
    private fun hidePanel() { try { panel?.let { wm.removeView(it) } } catch (_: Exception) {}; panel = null }
    override fun onDestroy() { hidePanel(); super.onDestroy() }
}
