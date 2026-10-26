package com.jcrw.translator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Window
import android.widget.Toast

/** Invisible bridge for the quick-menu start action. Android still requires
 * a foreground activity to show the system's MediaProjection consent dialog. */
class QuickStartActivity : Activity() {
    private lateinit var prefs: CompanionPreferences
    private val overlayRequest = 71
    private val captureRequest = 72

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.decorView.setBackgroundColor(Color.TRANSPARENT)
        prefs = CompanionPreferences(this)

        // First-run language and license setup still belongs to the full app.
        if (!prefs.hasUiLanguageSelection || !prefs.licenseAccepted) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
            return
        }
        if (prefs.captureRunning) {
            finish()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            try {
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                    overlayRequest
                )
            } catch (_: Exception) {
                toast(tx("Diğer uygulamaların üzerinde gösterim izni gerekli.", "Display-over-other-apps permission is required."))
                finish()
            }
            return
        }
        requestCaptureConsent()
    }

    private fun requestCaptureConsent() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
    }

    @Deprecated("Required for Android TV MediaProjection consent flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            overlayRequest -> {
                if (Settings.canDrawOverlays(this)) requestCaptureConsent()
                else {
                    toast(tx("İzin verilmedi; çeviri başlatılmadı.", "Permission was not granted; translation did not start."))
                    finish()
                }
            }
            captureRequest -> {
                if (resultCode == RESULT_OK && data != null) {
                    val service = Intent(this, TranslatorService::class.java)
                        .setAction(TranslatorService.ACTION_START)
                        .putExtra(TranslatorService.EXTRA_RESULT_CODE, resultCode)
                        .putExtra(TranslatorService.EXTRA_DATA, data)
                    try {
                        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
                        toast(tx("Çeviri başlatılıyor.", "Starting translation."))
                    } catch (_: Exception) {
                        prefs.captureRunning = false
                        toast(tx("Çeviri başlatılamadı. Uygulamayı açıp izinleri kontrol edin.", "Could not start translation. Open the app and check its permissions."))
                    }
                } else {
                    toast(tx("Ekran yakalama izni verilmedi.", "Screen-capture permission was not granted."))
                }
                finish()
            }
            else -> finish()
        }
    }

    private fun tx(tr: String, en: String) = if (prefs.uiLanguage == "en") en else tr
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
