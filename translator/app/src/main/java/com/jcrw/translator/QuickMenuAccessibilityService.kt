package com.jcrw.translator

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

internal object ShortcutState { @Volatile var blocked = false }

/** Global gamepad shortcut and lightweight in-game translator quick panel. */
class QuickMenuAccessibilityService : AccessibilityService() {
    private lateinit var prefs: CompanionPreferences
    private lateinit var wm: WindowManager
    private var panel: View? = null
    private val keyHandler = Handler(Looper.getMainLooper())
    private var rightPressed = false
    private val openMenuAfterHold = Runnable {
        if (rightPressed && prefs.quickShortcutEnabled && !ShortcutState.blocked && panel == null) showPanel()
    }

    override fun onServiceConnected() { super.onServiceConnected(); prefs = CompanionPreferences(this); wm = getSystemService(WINDOW_SERVICE) as WindowManager }
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && panel != null && event.keyCode == KeyEvent.KEYCODE_BACK) { hidePanel(); return true }
        if (event.keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false
        if (event.action == KeyEvent.ACTION_UP) { rightPressed = false; keyHandler.removeCallbacks(openMenuAfterHold); return false }
        if (!prefs.quickShortcutEnabled || panel != null || event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount == 0) { rightPressed = true; keyHandler.removeCallbacks(openMenuAfterHold); keyHandler.postDelayed(openMenuAfterHold, 450L) }
        // Keep the shortcut non-blocking so the game/remote-play app still receives controller input.
        return false
    }

    private fun showPanel() {
        if (panel != null) return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 22, 28, 22)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xF20A1832.toInt(), 0xF21C3156.toInt())).apply { cornerRadius = 28f; setStroke(2, 0xFFEC62B5.toInt()) }
        }
        root.addView(TextView(this).apply { text = "Jcrw  •  HIZLI ÇEVİRİ MENÜSÜ"; textSize = 23f; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(4, 0, 0, 14) })
        addButton(root, "Altyazı alanını taşı / boyutlandır") { launchRegionPicker() }
        addButton(root, "Çevrilen yazının konumunu ayarla") { launchCaptionPositionPicker() }
        addButton(root, if (prefs.captureRunning) "Çeviriyi durdur" else "Çeviriyi başlat") { toggleTranslation() }
        addButton(root, if (prefs.speechEnabled) "Sesli çeviriyi kapat" else "Sesli çeviriyi aç") { prefs.speechEnabled = !prefs.speechEnabled; if (!prefs.speechEnabled && prefs.captureRunning) startService(Intent(this, TranslatorService::class.java).setAction(TranslatorService.ACTION_SPEECH_STOP)); hidePanel(); showPanel() }
        addButton(root, "Çeviri kaynağı: ${providerName()}") { cycleProvider(); hidePanel(); showPanel() }
        addButton(root, "Kaynak dili: ${prefs.subtitleSourceLanguage.uppercase()} • Değiştir") { prefs.subtitleSourceLanguage = if (prefs.subtitleSourceLanguage == "en") "ja" else if (prefs.subtitleSourceLanguage == "ja") "auto" else "en"; hidePanel(); showPanel() }
        addButton(root, "Menüyü kapat") { hidePanel() }
        val params = WindowManager.LayoutParams((resources.displayMetrics.widthPixels * .74f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, android.graphics.PixelFormat.TRANSLUCENT).apply { gravity = Gravity.CENTER }
        try { wm.addView(root, params); panel = root; root.getChildAt(1)?.requestFocus() } catch (_: Exception) { panel = null }
    }

    private fun addButton(parent: LinearLayout, label: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            text = label; isAllCaps = false; textSize = 18f; setTextColor(Color.WHITE); isFocusable = true; isFocusableInTouchMode = true
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(0xFFB52B83.toInt()); cornerRadius = 16f; setStroke(2, Color.WHITE) })
                addState(intArrayOf(), GradientDrawable().apply { setColor(0xFF203D66.toInt()); cornerRadius = 16f; setStroke(1, 0xFF557EAE.toInt()) })
            }
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 })
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
    override fun onDestroy() { keyHandler.removeCallbacksAndMessages(null); hidePanel(); super.onDestroy() }
}
