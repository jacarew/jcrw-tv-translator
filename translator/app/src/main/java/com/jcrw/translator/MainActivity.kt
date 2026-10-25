package com.jcrw.translator

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    private lateinit var p: CompanionPreferences
    private lateinit var status: TextView
    private var primaryButton: Button? = null
    private var settingsDialog: AlertDialog? = null
    private val overlayRequest = 41
    private val captureRequest = 42
    private var screenReady = false
    private var pendingQuickStart = false
    private fun tx(tr:String,en:String)=if(p.uiLanguage=="en")en else tr
    private val mainHandler=Handler(Looper.getMainLooper())
    private val refreshStateRunnable=object:Runnable{override fun run(){refreshMainState();mainHandler.postDelayed(this,1200L)}}
    private val finishSplash=Runnable{
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if(p.hasUiLanguageSelection)continueStartupAfterLanguageChoice() else showLanguageSelection()
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); p=CompanionPreferences(this);if(!p.performanceProfileApplied){if(p.ocrWidth>=720)p.ocrWidth=320;p.scanIntervalMs=1500;p.performanceProfileApplied=true};pendingQuickStart=intent.getBooleanExtra(EXTRA_QUICK_START,false);showSplash();mainHandler.postDelayed(finishSplash,1800) }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);if(intent.getBooleanExtra(EXTRA_QUICK_START,false)){pendingQuickStart=true;if(screenReady&&p.licenseAccepted){pendingQuickStart=false;mainHandler.postDelayed({beginStart()},250)}}}
    private fun showSplash(){
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(24,20,24,24);setBackgroundColor(Color.rgb(7,21,47))}
        root.addView(ImageView(this).apply{setImageResource(com.jcrw.translator.R.drawable.app_logo);scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription=tx("Jcrw Çeviri logosu","Jcrw Translator logo")},LinearLayout.LayoutParams(500,500))
        root.addView(TextView(this).apply{text=tx("Artık tüm dijital içerikler Türkçe • jcrw23","All your digital content, in Turkish • jcrw23");textSize=29f;setTextColor(Color.WHITE);gravity=Gravity.CENTER;setPadding(0,16,0,0)})
        setContentView(root)
    }
    private fun showLanguageSelection(){
        val choices=arrayOf("Türkçe","English")
        val dialog=AlertDialog.Builder(this).setTitle("Arayüz dili / Interface language")
            .setItems(choices){_,which->p.uiLanguage=if(which==1)"en" else "tr";continueStartupAfterLanguageChoice()}
            .setCancelable(false).create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener{dialog.listView?.requestFocus()}
        dialog.show()
    }
    private fun continueStartupAfterLanguageChoice(){
        if(!::p.isInitialized)return
        render();screenReady=true
        if(!p.licenseAccepted)showLicense() else if(pendingQuickStart){pendingQuickStart=false;mainHandler.postDelayed({beginStart()},250)} else checkForUpdates()
    }
    private fun showLicense(){
        val english=p.uiLanguage=="en"
        val title=if(english)"Jcrw Translator — first run" else "Jcrw Çeviri — ilk kullanım"
        val body=if(english) "Jcrw Translator is provided free by its publisher. Use it at your own risk; you are responsible for permissions, device settings, and any effects on your device or accounts.\n\nWhen enabled, screen capture is processed on this device for OCR. Only recognized subtitle text—not screenshots—is sent to the translation or speech provider you select. Review that provider's privacy terms and API charges.\n\nThis application includes modified Chiaki-derived translation and speech code under AGPL-3.0-only-OpenSSL. License notices and corresponding source are provided with the project. The upstream license grants rights that cannot be removed by this notice.\n\nAccept to continue. If you decline or return, the application will close." else "Jcrw Çeviri yayıncısı tarafından ücretsiz sunulur. Kendi sorumluluğunuzda kullanın; izinler, cihaz ayarları ve cihazınızda veya hesaplarınızda oluşabilecek etkilerden siz sorumlusunuz.\n\nEtkinleştirdiğinizde ekran yakalama, OCR için bu cihazda işlenir. Ekran görüntüsü değil, yalnızca tanınan altyazı metni seçtiğiniz çeviri veya ses sağlayıcısına gönderilir. Sağlayıcının gizlilik koşullarını ve olası API ücretlerini kontrol edin.\n\nUygulamada AGPL-3.0-only-OpenSSL lisanslı Chiaki kaynaklı değiştirilmiş çeviri ve ses kodu bulunur. Lisans bildirimleri ve ilgili kaynak proje içinde sağlanır. Üst kaynak lisansının tanıdığı haklar bu bildirimle kaldırılamaz.\n\nDevam etmek için kabul edin. Reddederseniz veya geri çıkarsanız uygulama kapanır."
        val dialog=AlertDialog.Builder(this).setTitle(title).setMessage(body).setCancelable(true).setPositiveButton(if(english)"Accept" else "Kabul ediyorum"){_,_->p.licenseAccepted=true;if(!pendingQuickStart)mainHandler.postDelayed({checkForUpdates()},500)}.setNegativeButton(if(english)"Decline" else "Kabul etmiyorum"){_,_->finishAndRemoveTask()}.create()
        dialog.setCanceledOnTouchOutside(false);dialog.setOnCancelListener{finishAndRemoveTask()};dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()};dialog.show()
    }

    private fun checkForUpdates(){
        val prefs=getSharedPreferences("jcrw_translator_updates",Context.MODE_PRIVATE)
        val now=System.currentTimeMillis()
        if(now-prefs.getLong("last_successful_check",0L)<12L*60L*60L*1000L)return
        Thread({
            var connection:HttpURLConnection?=null
            try{
                connection=(URL("https://api.github.com/repos/jacarew/jcrw-tv-translator/releases/latest").openConnection() as HttpURLConnection).apply{
                    connectTimeout=8000;readTimeout=8000
                    setRequestProperty("Accept","application/vnd.github+json")
                    setRequestProperty("User-Agent","Jcrw-Translator-Android-TV")
                }
                if(connection.responseCode !in 200..299)return@Thread
                val release=JSONObject(connection.inputStream.bufferedReader().use{it.readText()})
                val assets=release.optJSONArray("assets")?:return@Thread
                val versionPattern=Regex("(?i)Jcrw-Translator-Android-TV-v([0-9]+(?:\\.[0-9]+){1,2})-universal\\.apk")
                val latestVersion=(0 until assets.length()).asSequence()
                    .mapNotNull{assets.optJSONObject(it)?.optString("name")?.let(versionPattern::find)?.groupValues?.getOrNull(1)}
                    .firstOrNull()?:return@Thread
                val releaseUrl=release.optString("html_url")
                if(releaseUrl.isBlank())return@Thread
                prefs.edit().putLong("last_successful_check",System.currentTimeMillis()).apply()
                @Suppress("DEPRECATION")
                val currentVersion=packageManager.getPackageInfo(packageName,0).versionName.orEmpty()
                if(compareVersions(latestVersion,currentVersion)<=0)return@Thread
                Handler(Looper.getMainLooper()).post{
                    if(isFinishing||isDestroyed)return@post
                    AlertDialog.Builder(this)
                        .setTitle(tx("Yeni sürüm hazır","Update available"))
                        .setMessage(tx("Jcrw Çeviri $latestVersion sürümü yayımlandı. İndirme sayfasını açmak ister misiniz?","Jcrw Translator $latestVersion is available. Open the download page?"))
                        .setNegativeButton(tx("Daha sonra","Later"),null)
                        .setPositiveButton(tx("İndir","Download")){_,_->startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(releaseUrl)))}
                        .show()
                }
            }catch(_:Exception){
                // Optional update checks must never interrupt translation or startup.
            }finally{connection?.disconnect()}
        },"jcrw-translator-update-check").start()
    }

    private fun compareVersions(left:String,right:String):Int{
        val a=left.split('.').map{it.toIntOrNull()?:0}
        val b=right.split('.').map{it.toIntOrNull()?:0}
        for(i in 0 until maxOf(a.size,b.size)){
            val comparison=(a.getOrElse(i){0}).compareTo(b.getOrElse(i){0})
            if(comparison!=0)return comparison
        }
        return 0
    }
    private fun render() {
        val page=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(44),dp(28),dp(44),dp(144));background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(7,20,48),Color.rgb(17,43,82))).apply{cornerRadius=dp(22).toFloat()}}
        val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        header.addView(ImageView(this).apply{setImageResource(R.drawable.app_logo);scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription="Jcrw Translator"},LinearLayout.LayoutParams(dp(108),dp(108)))
        val titles=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),0,0,0)}
        titles.addView(TextView(this).apply{text="Jcrw Translator";textSize=30f;typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.BOLD);setTextColor(Color.WHITE)})
        titles.addView(TextView(this).apply{text=tx("Artık tüm dijital içerikler Türkçe","All your digital content, in Turkish");textSize=17f;setTextColor(Color.rgb(226,190,220));setPadding(0,dp(5),0,0)})
        header.addView(titles,LinearLayout.LayoutParams(0,-2,1f));page.addView(header,LinearLayout.LayoutParams(-1,-2))
        val statusCard=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(14),dp(20),dp(14));background=shape(Color.rgb(22,44,77),Color.rgb(64,112,155))}
        statusCard.addView(TextView(this).apply{text=tx("ÇEVİRİ DURUMU","TRANSLATION STATUS");textSize=12f;typeface=android.graphics.Typeface.DEFAULT_BOLD;setTextColor(Color.rgb(158,191,228))})
        status=TextView(this).apply{textSize=18f;setTextColor(Color.WHITE);setPadding(0,dp(5),0,0);maxLines=2};statusCard.addView(status)
        page.addView(statusCard,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(22);bottomMargin=dp(16)})
        primaryButton=actionButton(page,"",true){toggleTranslation()}
        actionButton(page,tx("Ayarlar","Settings"),false){showSettings()}
        row(page,tx("Açık kaynak lisansları","Open-source licenses"),tx("Kullanım ve gizlilik","Usage and privacy"),
            {showLicenses()},{showUsagePrivacy()})
        supportBanner(page)
        actionButton(page,tx("Simge durumuna küçült","Minimize to background"),false){moveTaskToBack(true)}
        val scroll=ScrollView(this).apply{isFillViewport=true};scroll.addView(page)
        val root=FrameLayout(this).apply {
            addView(scroll,FrameLayout.LayoutParams(-1,-1))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.bmc_qr);scaleType=ImageView.ScaleType.FIT_CENTER
                setPadding(dp(4),dp(4),dp(4),dp(4));background=shape(Color.WHITE,Color.rgb(205,215,230))
                contentDescription=tx("Buy Me a Coffee QR kodu","Buy Me a Coffee QR code")
                isFocusable=true;isClickable=true;setOnClickListener{openGlobalSupportPage()}
            },FrameLayout.LayoutParams(dp(108),dp(108),Gravity.TOP or Gravity.END).apply{rightMargin=dp(12);topMargin=dp(28)})
        }
        setContentView(root);refreshMainState()
    }
    private fun dp(value:Int)= (value*resources.displayMetrics.density).toInt()
    private fun shape(color:Int,stroke:Int=Color.TRANSPARENT)=GradientDrawable().apply{setColor(color);cornerRadius=dp(14).toFloat();if(stroke!=Color.TRANSPARENT)setStroke(dp(2),stroke)}
    private fun buttonBackground(primary:Boolean):StateListDrawable {
        val normal=if(primary)Color.rgb(211,45,129) else Color.rgb(24,48,82)
        val focus=if(primary)Color.rgb(239,78,158) else Color.rgb(40,77,120)
        return StateListDrawable().apply{
            addState(intArrayOf(android.R.attr.state_pressed),shape(focus,Color.rgb(255,210,236)))
            addState(intArrayOf(android.R.attr.state_focused),shape(focus,Color.rgb(255,210,236)))
            addState(intArrayOf(),shape(normal,if(primary)Color.rgb(245,127,190) else Color.rgb(66,105,151)))
        }
    }
    private fun actionButton(parent:LinearLayout,label:String,primary:Boolean,onClick:()->Unit):Button {
        val b=Button(this).apply{text=label;textSize=17f;isAllCaps=false;typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.BOLD);setTextColor(Color.WHITE);minHeight=dp(if(primary)66 else 58);background=buttonBackground(primary);stateListAnimator=null;setPadding(dp(16),dp(8),dp(16),dp(8));isFocusable=true;setOnClickListener{onClick()}}
        parent.addView(b,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)});return b
    }
    private fun row(parent:LinearLayout,left:String,right:String,leftClick:()->Unit,rightClick:()->Unit){
        val pair=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        fun item(label:String,click:()->Unit)=Button(this).apply{text=label;textSize=16f;isAllCaps=false;typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.BOLD);setTextColor(Color.WHITE);minHeight=dp(64);background=buttonBackground(false);stateListAnimator=null;setPadding(dp(10),dp(8),dp(10),dp(8));isFocusable=true;setOnClickListener{click()}}
        pair.addView(item(left,leftClick),LinearLayout.LayoutParams(0,-2,1f).apply{rightMargin=dp(8)})
        pair.addView(item(right,rightClick),LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(8)})
        parent.addView(pair,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)})
    }
    private fun supportBanner(parent:LinearLayout) {
        val bannerBackground=StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed),shape(Color.rgb(197,54,139),Color.rgb(255,220,242)))
            addState(intArrayOf(android.R.attr.state_focused),shape(Color.rgb(197,54,139),Color.rgb(255,220,242)))
            addState(intArrayOf(),GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(82,37,83),Color.rgb(48,45,91))).apply { cornerRadius=dp(16).toFloat(); setStroke(dp(1),Color.rgb(209,110,180)) })
        }
        val banner=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(18),dp(14),dp(18),dp(14))
            background=bannerBackground
            isFocusable=true; isClickable=true
        }
        banner.addView(ImageView(this).apply { setImageResource(R.drawable.ic_coffee); contentDescription=tx("Kahve fincanı","Coffee cup") },LinearLayout.LayoutParams(dp(48),dp(48)))
        val copy=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(14),0,0,0) }
        copy.addView(TextView(this).apply { text=tx("☕ Projeye bir kahve ısmarlayın","☕ Buy the project a coffee"); textSize=18f; typeface=android.graphics.Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) })
        copy.addView(TextView(this).apply { text=tx("Geliştirmeye gönüllü destek • Zorunlu değil","Voluntary support for development • Optional"); textSize=14f; setTextColor(Color.rgb(232,220,245)); setPadding(0,dp(4),0,0) })
        banner.addView(copy,LinearLayout.LayoutParams(0,-2,1f))
        banner.setOnClickListener { showSupportOptions() }
        parent.addView(banner,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(2); bottomMargin=dp(14) })
    }
    private fun showSupportInfo() {
        val content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(4)) }
        content.addView(CoffeeSupportGifView(this),LinearLayout.LayoutParams(-1,dp(150)))
        content.addView(TextView(this).apply { text=tx("Jcrw ücretsizdir. İstersen listeden gönüllü destek tutarını seçip Papara QR kodunu tara. Destek zorunlu değildir ve uygulama özelliklerini açmaz.\n\nÖdemeyi onaylamadan önce tutarı kontrol et. Uygulamayı ve üçüncü taraf hizmetlerini kullanma sorumluluğu kullanıcıya aittir.","Jcrw is free. If you wish, choose a voluntary support amount and scan the Papara QR code. Support is optional and does not unlock app features.\n\nCheck the amount before confirming a payment. Users remain responsible for using the app and third-party services.");textSize=16f;setTextColor(Color.WHITE);setPadding(0,dp(8),0,dp(8)) })
        val amounts=if(p.uiLanguage=="en") arrayOf("Choose an amount","₺10","₺20","₺50","₺100","₺200") else arrayOf("Tutar seçin","₺10","₺20","₺50","₺100","₺200")
        val qr=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER; visibility=View.GONE; contentDescription=tx("Papara ödeme QR kodu","Papara payment QR code") }
        val picker=Spinner(this).apply {
            adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,amounts)
            onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent:AdapterView<*>?) { qr.visibility=View.GONE }
                override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) {
                    val resource=when(position){1->R.drawable.papara_10;2->R.drawable.papara_20;3->R.drawable.papara_50;4->R.drawable.papara_100;5->R.drawable.papara_200;else->0}
                    qr.visibility=if(resource==0)View.GONE else View.VISIBLE
                    if(resource!=0)qr.setImageResource(resource)
                }
            }
        }
        content.addView(picker,LinearLayout.LayoutParams(-1,dp(56)))
        content.addView(qr,LinearLayout.LayoutParams(-1,dp(300)))
        val scroll=ScrollView(this).apply { addView(content) }
        AlertDialog.Builder(this)
            .setTitle(tx("☕ Jcrw projesine destek","☕ Support the Jcrw project"))
            .setView(scroll)
            .setPositiveButton(tx("Tamam","OK"),null).show()
    }
    private fun showSupportOptions(){
        val options=arrayOf(tx("Papara · Türkiye","Papara · Türkiye"),tx("Buy Me a Coffee · Uluslararası","Buy Me a Coffee · International"))
        AlertDialog.Builder(this).setTitle(tx("Projeye nasıl destek olmak istersin?","How would you like to support the project?"))
            .setItems(options){_,which->if(which==0)showSupportInfo() else openGlobalSupportPage()}
            .setNegativeButton(tx("İptal","Cancel"),null).show()
    }
    private fun openGlobalSupportPage(){
        try{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://buymeacoffee.com/jcrw")))}
        catch(_:android.content.ActivityNotFoundException){Toast.makeText(this,tx("Bağlantıyı açacak tarayıcı bulunamadı","No browser is available to open the link"),Toast.LENGTH_LONG).show()}
    }
    private fun showUsagePrivacy(){AlertDialog.Builder(this).setTitle(tx("Nasıl çalışır?","How it works")).setMessage(tx("Jcrw Çeviri yalnızca oyunlarda değil; film, dizi veya Android ekranında görünen başka videolarda da kullanılabilir. Ekran yakalama izni verin, çevireceğiniz uygulamaya dönün ve altyazı alanını seçin. Jcrw seçilen alanı cihazda OCR ile okur; yalnızca tanınan metin seçtiğiniz çeviri sağlayıcısına gönderilir, ekran görüntüsü gönderilmez. Çeviriyi ekranda göstermek için diğer uygulamaların üzerinde gösterim izni gerekir. DRM veya FLAG_SECURE kullanan uygulamalar ekranı yakalamayı engelleyebilir. TV'nin HDMI giriş görüntüsü okunamaz.","Jcrw Translator can be used with games, films, TV shows, or other video visible on the Android screen. Grant screen-capture permission, return to the app you want to translate, and choose the subtitle area. Jcrw runs OCR on that selected area on the device; only recognized text—not screenshots—is sent to your chosen translation provider. Overlay permission is needed to show translated captions. DRM or FLAG_SECURE apps may block screen capture. HDMI input cannot be captured.")).setPositiveButton(tx("Tamam","OK"),null).show()}
    private fun serviceIsRunning():Boolean = try{(getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).getRunningServices(Int.MAX_VALUE).any{it.service.className==TranslatorService::class.java.name}}catch(_:Exception){false}
    private fun refreshMainState(){if(!::status.isInitialized)return;val age=android.os.SystemClock.elapsedRealtime()-p.captureHeartbeat;val running=serviceIsRunning()||(p.captureRunning&&age in 0..10000L);if(!running&&p.captureRunning){p.captureRunning=false;p.captureHeartbeat=0L;p.captureStatus=tx("Çeviri durduruldu","Translation stopped")};p.captureRunning=running;status.text=if(running)p.captureStatus else if(p.translationProvider!="libretranslate"&&p.translationApiKey.isBlank())tx("Hazır • API anahtarı girin veya Ayarlar’dan ücretsiz çeviri motoru seçin","Ready • Enter an API key or select a free translation engine in Settings") else tx("Hazır • Çeviriyi başlatabilirsiniz","Ready • You can start translation");primaryButton?.text=if(running)tx("ÇEVİRİYİ DURDUR","STOP TRANSLATION") else tx("ÇEVİRİYİ BAŞLAT","START TRANSLATION");primaryButton?.background=buttonBackground(running)}
    private fun toggleTranslation(){if(serviceIsRunning()||p.captureRunning){p.captureRunning=false;p.captureStatus=tx("Çeviri durduruldu","Translation stopped");startService(Intent(this,TranslatorService::class.java).setAction(TranslatorService.ACTION_STOP));status.text=p.captureStatus;primaryButton?.text=tx("ÇEVİRİYİ BAŞLAT","START TRANSLATION");primaryButton?.background=buttonBackground(false);mainHandler.postDelayed({refreshMainState()},600L)}else beginStart()}
    private fun beginStart(){
        if(!Settings.canDrawOverlays(this)){startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")),overlayRequest);return}
        val manager=getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(),captureRequest)
    }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==overlayRequest){if(Settings.canDrawOverlays(this)) beginStart() else message(tx("Overlay izni gerekli","Overlay permission is required"));return}
        if(requestCode==captureRequest && resultCode==RESULT_OK && data!=null){p.captureRunning=true;p.captureStatus=tx("Ekran yakalama açılıyor…","Starting screen capture…");val i=Intent(this,TranslatorService::class.java).setAction(TranslatorService.ACTION_START).putExtra(TranslatorService.EXTRA_RESULT_CODE,resultCode).putExtra(TranslatorService.EXTRA_DATA,data);if(Build.VERSION.SDK_INT>=26)startForegroundService(i) else startService(i);refreshMainState();message(tx("Ekran yakalama başladı. Altyazı alanını kontrol edip Chiaki veya Remote Play’e geç.","Screen capture started. Check the subtitle region, then switch to Chiaki or Remote Play."))}
        else if(requestCode==captureRequest){p.captureRunning=false;p.captureStatus=tx("Ekran yakalama izni verilmedi","Screen-capture permission was not granted");refreshMainState()}
    }
    private fun message(text:String){Toast.makeText(this,text,Toast.LENGTH_LONG).show()}
    override fun onResume(){super.onResume();ShortcutState.blocked=true;mainHandler.removeCallbacks(refreshStateRunnable);mainHandler.post(refreshStateRunnable)}
    override fun onPause(){ShortcutState.blocked=false;mainHandler.removeCallbacks(refreshStateRunnable);super.onPause()}
    @Suppress("DEPRECATION")
    override fun onBackPressed(){moveTaskToBack(true)}
    private fun showLicenses(){
        val body=assets.open("AGPL-3.0-only-OpenSSL.txt").bufferedReader().use{it.readText()}
        val view=ScrollView(this).apply{addView(TextView(this@MainActivity).apply{text=body;setPadding(24,16,24,16);textSize=15f})}
        AlertDialog.Builder(this).setTitle(tx("Chiaki kaynak kodu lisansı","Chiaki-derived code license")).setView(view).setPositiveButton(tx("Kapat","Close"),null).show()
    }
    private fun showSettings(){
        settingsPage(tx("Ayarlar","Settings")) { box ->
            choice(box,tx("Arayüz dili","Interface language"),arrayOf("Türkçe","English"),if(p.uiLanguage=="en")1 else 0){p.uiLanguage=if(it==1)"en" else "tr"}
            actionButton(box,tx("Altyazı görünümü ve konumu","Subtitle appearance and position"),false){settingsDialog?.dismiss();showCaptionSettings()}
            actionButton(box,tx("OCR ve çeviri","OCR and translation"),false){settingsDialog?.dismiss();showTranslationSettings()}
            actionButton(box,tx("Seslendirme","Speech and voice"),false){settingsDialog?.dismiss();showSpeechSettings()}
            actionButton(box,tx("Kumanda kısayolu","Controller shortcut"),false){settingsDialog?.dismiss();showControllerSettings()}
        }
    }
    private fun settingsPage(title:String,fill:(LinearLayout)->Unit){val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(12),dp(24),dp(12))};fill(box);val scroll=ScrollView(this).apply{addView(box)};settingsDialog=AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton(tx("Kapat","Close"),null).show()}
    private fun showCaptionSettings()=settingsPage(tx("Altyazı özelleştir","Customize subtitles")){box->
        actionButton(box,tx("Altyazı alanını taşı / boyutlandır","Move / resize subtitle area"),false){settingsDialog?.dismiss();startActivity(Intent(this,RegionSelectionActivity::class.java))}
        actionButton(box,tx("Çevrilmiş altyazı konumunu ayarla","Adjust translated subtitle position"),false){settingsDialog?.dismiss();startActivity(Intent(this,CaptionPositionActivity::class.java))}
        choice(box,tx("Altyazı yazı boyutu","Caption font size"),arrayOf("8 sp • çok küçük","10 sp • küçük","12 sp","14 sp","18 sp","22 sp","26 sp","30 sp","36 sp","42 sp","48 sp"),listOf(8,10,12,14,18,22,26,30,36,42,48).indexOf(p.captionTextSize).coerceAtLeast(0)){p.captionTextSize=listOf(8,10,12,14,18,22,26,30,36,42,48)[it]}
        choice(box,tx("Altyazı yazı rengi","Caption text color"),ColorNames.map{if(p.uiLanguage=="en")it.second else it.first}.toTypedArray(),ColorNames.indexOfFirst{it.third==p.captionTextColor}.coerceAtLeast(0)){p.captionTextColor=ColorNames[it].third}
        choice(box,tx("Altyazı arka plan rengi","Caption background color"),BackgroundNames.map{if(p.uiLanguage=="en")it.second else it.first}.toTypedArray(),BackgroundNames.indexOfFirst{it.third==p.captionBackgroundColor}.coerceAtLeast(0)){p.captionBackgroundColor=BackgroundNames[it].third}
        choice(box,tx("Altyazı yazı tipi","Caption font"),arrayOf("Sans Serif","Sans Serif Kalın","Serif","Monospace"),listOf("sans-serif","sans-serif-medium","serif","monospace").indexOf(p.captionFont).coerceAtLeast(1)){p.captionFont=listOf("sans-serif","sans-serif-medium","serif","monospace")[it]}
    }
    private fun showTranslationSettings()=settingsPage(tx("OCR ve çeviri","OCR and translation")){box->
        choice(box,tx("OCR motoru","OCR engine"),arrayOf("ML Kit (cihazda / on-device)","Tesseract (indirilen model / downloaded model)"),if(p.ocrEngine=="tesseract")1 else 0){p.ocrEngine=if(it==1)"tesseract" else "mlkit"}
        choice(box,tx("OCR çözünürlüğü • düşük değer daha az yük","OCR resolution • lower uses less CPU"),arrayOf("96 px","160 px","240 px","320 px","480 px","720 px"),listOf(96,160,240,320,480,720).indexOf(p.ocrWidth).coerceAtLeast(0)){p.ocrWidth=listOf(96,160,240,320,480,720)[it]}
        choice(box,tx("OCR tarama aralığı","OCR sampling interval"),arrayOf("1 saniye • hızlı","1,5 saniye • dengeli","2 saniye • daha az yük","3 saniye • en az yük"),listOf(1000,1500,2000,3000).indexOf(p.scanIntervalMs).coerceAtLeast(1)){p.scanIntervalMs=listOf(1000,1500,2000,3000)[it]}
        choice(box,tx("Çeviri servisi","Translation provider"),arrayOf("DeepL API","OpenAI / GPT","Google Gemini","LibreTranslate"),listOf("deepl","openai","gemini","libretranslate").indexOf(p.translationProvider).coerceAtLeast(0)){p.translationProvider=listOf("deepl","openai","gemini","libretranslate")[it]}
        choice(box,tx("Kaynak dili","Source language"),arrayOf("English","Japanese","Auto"),listOf("en","ja","auto").indexOf(p.subtitleSourceLanguage).coerceAtLeast(0)){p.subtitleSourceLanguage=listOf("en","ja","auto")[it]}
        choice(box,tx("Çeviri hedef dili","Translation target language"),arrayOf("Türkçe","English","Deutsch","Español","Français","日本語"),listOf("tr","en","de","es","fr","ja").indexOf(p.subtitleTargetLanguage).coerceAtLeast(0)){p.subtitleTargetLanguage=listOf("tr","en","de","es","fr","ja")[it]}
        edit(box,tx("Çeviri API anahtarı","Translation API key"),p.translationApiKey,true){p.translationApiKey=it}
        if(p.translationProvider=="libretranslate")edit(box,tx("LibreTranslate sunucu adresi","LibreTranslate server URL"),p.translationEndpoint){p.translationEndpoint=it}
    }
    private fun showSpeechSettings()=settingsPage(tx("Seslendirme ayarları","Speech settings")){box->
        choice(box,tx("Seslendirme","Speech"),arrayOf(tx("Kapalı","Off"),tx("Açık","On")),if(p.speechEnabled)1 else 0){p.speechEnabled=it==1}
        choice(box,tx("Seslendirme motoru","Speech engine"),arrayOf(tx("Cihaz TTS (ücretsiz)","Device TTS (free)"),tx("Gemini TTS (anahtar gerekir)","Gemini TTS (API key required)")),if(p.speechProvider=="gemini")1 else 0){p.speechProvider=if(it==1)"gemini" else "android"}
        choice(box,tx("Ses türü","Voice gender"),arrayOf(tx("Kadın","Female"),tx("Erkek","Male")),if(p.speechGender=="male")1 else 0){p.speechGender=if(it==1)"male" else "female"}
        choice(box,tx("Gemini kadın sesi","Gemini female voice"),GeminiVoices,GeminiVoices.indexOf(p.geminiFemaleVoice).coerceAtLeast(0)){p.geminiFemaleVoice=GeminiVoices[it]}
        choice(box,tx("Gemini erkek sesi","Gemini male voice"),GeminiVoices,GeminiVoices.indexOf(p.geminiMaleVoice).coerceAtLeast(1)){p.geminiMaleVoice=GeminiVoices[it]}
        choice(box,tx("Tek ses modu Gemini sesi","Gemini voice for single-voice mode"),GeminiVoices,GeminiVoices.indexOf(p.geminiSpeechVoice).coerceAtLeast(0)){p.geminiSpeechVoice=GeminiVoices[it]}
        edit(box,tx("Gemini TTS API anahtarı","Gemini TTS API key"),p.geminiSpeechApiKey,true){p.geminiSpeechApiKey=it}
        val volume=SeekBar(this).apply{max=100;progress=p.speechVolume;setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,v:Int,u:Boolean){p.speechVolume=v};override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?) {}})}
        box.addView(TextView(this).apply{text=tx("Ses düzeyi: ${p.speechVolume}%","Volume: ${p.speechVolume}%");textSize=18f;setTextColor(Color.WHITE)});box.addView(volume)
        val rate=SeekBar(this).apply{max=15;progress=((p.speechRate-.5f)*10).toInt().coerceIn(0,15);setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,v:Int,u:Boolean){p.speechRate=.5f+v/10f};override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?) {}})}
        box.addView(TextView(this).apply{text=tx("Ses hızı: ${"%.1f".format(p.speechRate)}x","Speech rate: ${"%.1f".format(p.speechRate)}x");textSize=18f;setTextColor(Color.WHITE)});box.addView(rate)
    }
    private fun showControllerSettings()=settingsPage(tx("Kumanda kısayolu","Controller shortcut")){box->
        choice(box,tx("D-pad sağ uzun basma kısayolu", "Hold D-pad right for quick menu"),arrayOf(tx("Kapalı", "Off"),tx("Açık • yaklaşık yarım saniye basılı tut", "On • hold for about half a second")),if(p.quickShortcutEnabled)1 else 0){p.quickShortcutEnabled=it==1}
        actionButton(box,tx("Erişilebilirlik ayarlarını aç","Open accessibility settings"),false){try{startActivity(Intent(Settings.ACTION_SETTINGS))}catch(_:Exception){message(tx("TV ayarlarını elle açıp Erişilebilirlik bölümüne gidin","Open TV settings and find Accessibility"))}}
    }
    private fun choice(parent:LinearLayout,title:String,items:Array<String>,selected:Int,onSelect:(Int)->Unit){val b=Button(this).apply{text="$title: ${items[selected]}";textSize=16f;isAllCaps=false;typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.BOLD);setTextColor(Color.WHITE);minHeight=dp(58);background=buttonBackground(false);stateListAnimator=null;setPadding(dp(12),dp(8),dp(12),dp(8));setOnClickListener{AlertDialog.Builder(this@MainActivity).setTitle(title).setSingleChoiceItems(items,selected){d,w->onSelect(w);this.text="$title: ${items[w]}";d.dismiss()}.show()}};parent.addView(b,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(9)})}
    private fun edit(parent:LinearLayout,title:String,value:String,secret:Boolean=false,onSave:(String)->Unit){parent.addView(Button(this).apply{text="$title: "+if(value.isBlank())tx("girilmedi","not set") else if(secret)tx("kayıtlı","saved") else value;textSize=16f;isAllCaps=false;typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.BOLD);setTextColor(Color.WHITE);minHeight=dp(58);background=buttonBackground(false);stateListAnimator=null;setPadding(dp(12),dp(8),dp(12),dp(8));setOnClickListener{val input=EditText(this@MainActivity).apply{setText(value);inputType=if(secret)InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT};AlertDialog.Builder(this@MainActivity).setTitle(title).setView(input).setPositiveButton(tx("Kaydet","Save")){_,_->onSave(input.text.toString());this.text="$title: "+if(secret)tx("kayıtlı","saved") else input.text.toString()}.setNegativeButton(tx("İptal","Cancel"),null).show()}},LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(9)})}
    companion object {
        const val EXTRA_QUICK_START="com.jcrw.translator.QUICK_START"
        private val ColorNames=listOf(Triple("Beyaz","White",Color.WHITE),Triple("Sarı","Yellow",Color.YELLOW),Triple("Camgöbeği","Cyan",Color.CYAN),Triple("Pembe","Pink",0xFFFF80AB.toInt()),Triple("Yeşil","Green",Color.GREEN))
        private val BackgroundNames=listOf(Triple("Siyah • %85","Black • 85%",0xD9000000.toInt()),Triple("Siyah • %60","Black • 60%",0x99000000.toInt()),Triple("Lacivert • %85","Navy • 85%",0xD9071530.toInt()),Triple("Saydam","Transparent",0x00000000))
        private val GeminiVoices=arrayOf("Achernar","Achird","Algenib","Algieba","Alnilam","Aoede","Autonoe","Callirrhoe","Charon","Despina","Enceladus","Erinome","Fenrir","Gacrux","Iapetus","Kore","Laomedeia","Leda","Orus","Puck","Pulcherrima","Rasalgethi","Sadachbia","Schedar","Sulafat","Umbriel","Vindemiatrix","Zephyr","Zubenelgenubi")
    }
    override fun onDestroy(){mainHandler.removeCallbacks(finishSplash);mainHandler.removeCallbacks(refreshStateRunnable);super.onDestroy()}
}
