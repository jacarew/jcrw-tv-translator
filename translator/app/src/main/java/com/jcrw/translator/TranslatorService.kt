package com.jcrw.translator

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.ImageReader
import android.media.MediaPlayer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.ArrayDeque
import java.util.concurrent.Executors

class TranslatorService:Service() {
    companion object {
        const val ACTION_START="com.jcrw.translator.START"; const val ACTION_STOP="com.jcrw.translator.STOP"; const val ACTION_SPEECH_STOP="com.jcrw.translator.SPEECH_STOP"
        const val EXTRA_RESULT_CODE="result_code"; const val EXTRA_DATA="projection_data"; private const val CHANNEL="jcrw_capture";private const val NOTIFICATION_ID=6231
    }
    private lateinit var prefs:CompanionPreferences
    private lateinit var wm:WindowManager
    private var projection:MediaProjection?=null
    private var projectionCallback:MediaProjection.Callback?=null
    private var display:VirtualDisplay?=null
    private var reader:ImageReader?=null
    private lateinit var thread:HandlerThread
    private lateinit var worker:Handler
    private val main=Handler(Looper.getMainLooper())
    private var recognizer:com.google.mlkit.vision.text.TextRecognizer?=null
    private var tesseract:TessBaseAPI?=null
    private var sampleBusy=false
    private var stopped=false
    private var latestSignature:IntArray?=null
    private var lastOcrAt=0L
    private val recentOcrTexts=ArrayDeque<Pair<String,Long>>()
    private var lastUserError=""
    private var lastUserErrorAt=0L
    private var overlay:TextView?=null
    private var tts:TextToSpeech?=null
    private var ttsReady=false
    private val speechQueue=ArrayDeque<Pair<String,String>>()
    private var speechBusy=false
    private var player:MediaPlayer?=null
    private val speechExecutor=Executors.newSingleThreadExecutor()

    override fun onCreate(){super.onCreate();prefs=CompanionPreferences(this);wm=getSystemService(WINDOW_SERVICE) as WindowManager;thread=HandlerThread("jcrw-screen-ocr").apply{start()};worker=Handler(thread.looper);tts=TextToSpeech(this){status->if(status==TextToSpeech.SUCCESS){ttsReady=true;tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());applyLocalVoice();speakNext()}};createChannel()}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==ACTION_STOP){prefs.captureRunning=false;prefs.captureHeartbeat=0L;prefs.captureStatus="Çeviri durduruldu";stopSelf();return START_NOT_STICKY}
        if(intent?.action==ACTION_SPEECH_STOP){synchronized(speechQueue){speechQueue.clear()};tts?.stop();speechBusy=false;player?.let{try{it.stop()}catch(_:Exception){};it.release()};player=null;return START_NOT_STICKY}
        if(intent?.action==ACTION_START && projection==null){startNotice();startCapture(intent)}
        else if(intent==null&&projection==null){prefs.captureRunning=false;stopSelf();return START_NOT_STICKY}
        return START_STICKY
    }
    private fun startNotice(message:String="Ekran yakalama hazırlanıyor…"){
        val openIntent=Intent(this,MainActivity::class.java).apply{flags=Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP}
        val openPending=PendingIntent.getActivity(this,0,openIntent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent=PendingIntent.getService(this,1,Intent(this,TranslatorService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n=Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("Jcrw • Altyazı çevirisi").setContentText(message).setContentIntent(openPending).addAction(android.R.drawable.ic_media_pause,"Çeviriyi durdur",stopIntent).setOngoing(true).build()
        if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(NOTIFICATION_ID,n)
    }
    private fun updateStatus(message:String){
        if(prefs.captureStatus==message)return
        prefs.captureStatus=message
        val openIntent=Intent(this,MainActivity::class.java).apply{flags=Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP}
        val openPending=PendingIntent.getActivity(this,0,openIntent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent=PendingIntent.getService(this,1,Intent(this,TranslatorService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n=Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("Jcrw • Altyazı çevirisi").setContentText(message).setContentIntent(openPending).addAction(android.R.drawable.ic_media_pause,"Çeviriyi durdur",stopIntent).setOngoing(true).build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID,n)
    }
    private fun showTopError(message:String){
        val now=android.os.SystemClock.elapsedRealtime()
        if(message==lastUserError&&now-lastUserErrorAt<15000L)return
        lastUserError=message;lastUserErrorAt=now
        main.post{android.widget.Toast.makeText(this,message,android.widget.Toast.LENGTH_LONG).apply{setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL,0,(24*resources.displayMetrics.density).toInt());show()}}
    }
    private fun createChannel(){if(Build.VERSION.SDK_INT>=26){val ch=NotificationChannel(CHANNEL,"Ekran çevirisi",NotificationManager.IMPORTANCE_LOW);(getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)}}
    private fun startCapture(intent:Intent){try{
        val data=if(Build.VERSION.SDK_INT>=33)intent.getParcelableExtra(EXTRA_DATA,Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA) as? Intent
        val code=intent.getIntExtra(EXTRA_RESULT_CODE,Activity.RESULT_CANCELED);if(data==null||code!=Activity.RESULT_OK)throw IllegalStateException("Ekran yakalama izni alınamadı")
        val manager=getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection=manager.getMediaProjection(code,data)?:throw IllegalStateException("Ekran yakalama başlatılamadı")
        projectionCallback=object:MediaProjection.Callback(){override fun onStop(){prefs.captureRunning=false;prefs.captureHeartbeat=0L;prefs.captureStatus="Ekran yakalama durdu";stopSelf()}}
        projection!!.registerCallback(projectionCallback!!,main)
        val metrics=DisplayMetrics();@Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
        val w=minOf(metrics.widthPixels,prefs.ocrWidth).coerceAtLeast(96);val h=(metrics.heightPixels.toFloat()*w/metrics.widthPixels).toInt().coerceAtLeast(54)
        reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2).also{r->r.setOnImageAvailableListener(::onImage,worker)}
        display=projection!!.createVirtualDisplay("Jcrw screen capture",w,h,metrics.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,worker)
        prefs.captureRunning=true
        prefs.captureHeartbeat=android.os.SystemClock.elapsedRealtime()
        updateStatus("Ekran yakalama ve OCR etkin • altyazı bekleniyor")
        main.post{showOverlay()}
    }catch(e:Exception){prefs.captureRunning=false;val message="Ekran yakalama açılamadı: ${e.message?:"hata"}";prefs.captureStatus=message;updateStatus(message);showTopError(message);stopSelf()}}
    private fun onImage(src:ImageReader){val img=try{src.acquireLatestImage()}catch(_:Exception){null}?:return;prefs.captureHeartbeat=android.os.SystemClock.elapsedRealtime();if(sampleBusy||stopped){img.close();return};sampleBusy=true;display?.setSurface(null)
        val bmp=try{val pl=img.planes[0];val stride=pl.pixelStride;val padded=pl.rowStride/stride;Bitmap.createBitmap(padded,img.height,Bitmap.Config.ARGB_8888).also{pl.buffer.rewind();it.copyPixelsFromBuffer(pl.buffer)}}catch(_:Exception){null}finally{img.close()}
        if(bmp==null){resume();return}
        // Row stride can pad the bitmap. Crop against the actual virtual-display width,
        // otherwise the saved normalized area drifts horizontally on padded images.
        val actual=try{if(bmp.width==reader?.width)bmp else Bitmap.createBitmap(bmp,0,0,reader?.width?:bmp.width,bmp.height)}catch(_:Exception){bmp}
        if(actual!==bmp)bmp.recycle()
        val r=prefs.subtitleRegion;val left=(r.left*actual.width).toInt().coerceIn(0,actual.width-1);val top=(r.top*actual.height).toInt().coerceIn(0,actual.height-1);val right=(r.right*actual.width).toInt().coerceIn(left+1,actual.width);val bottom=(r.bottom*actual.height).toInt().coerceIn(top+1,actual.height);val crop=try{Bitmap.createBitmap(actual,left,top,right-left,bottom-top)}catch(_:Exception){null};actual.recycle();if(crop==null){resume();return}
        val sig=signature(crop);val now=android.os.SystemClock.elapsedRealtime();val prev=latestSignature;val changed=prev==null||prev.size!=sig.size||sig.indices.sumOf{ kotlin.math.abs(sig[it]-prev[it]) }/sig.size>=8
        if(!changed&&now-lastOcrAt<5000){crop.recycle();resume();return};latestSignature=sig;lastOcrAt=now
        if(prefs.ocrEngine=="mlkit"){
            val client=recognizer?:TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also{recognizer=it}
            client.process(InputImage.fromBitmap(crop,0)).addOnSuccessListener{handleText(it.text)}.addOnFailureListener{e->val message="OCR hatası: ${e.localizedMessage?:"metin okunamadı"}";updateStatus(message);showTopError(message)}.addOnCompleteListener{crop.recycle();resume()}
        }
        else try{val api=tesseract?:initTesseract().also{tesseract=it};api.setImage(crop);handleText(api.getUTF8Text().orEmpty())}catch(e:Exception){val message="Tesseract OCR açılamadı: ${e.localizedMessage?:"model hatası"}";updateStatus(message);showTopError(message)}finally{crop.recycle();resume()}
    }
    private fun signature(b:Bitmap):IntArray{val out=IntArray(24*10);var n=0;for(y in 0 until 10)for(x in 0 until 24){val px=((x+.5f)*b.width/24).toInt().coerceIn(0,b.width-1);val py=((y+.5f)*b.height/10).toInt().coerceIn(0,b.height-1);val c=b.getPixel(px,py);out[n++]=(((c shr 16 and 255)*299+(c shr 8 and 255)*587+(c and 255)*114)/1000)};return out}
    private fun handleText(raw:String){val text=dialogueOnly(raw);if(text.isBlank())return;if(isRecentDuplicate(text))return
        if(prefs.translationProvider!="libretranslate"&&prefs.translationApiKey.isBlank()){
            val message="OCR yazıyı algıladı; ${prefs.translationProvider.uppercase()} API anahtarı eksik. Jcrw Ayarları'ndan ekle."
            updateStatus(message);showTopError(message);return
        }
        updateStatus("OCR yazıyı algıladı • çeviri yapılıyor")
        SubtitleTranslator(prefs).translate(text,prefs.subtitleSourceLanguage).whenComplete{translated,error->
            if(error==null&&!translated.isNullOrBlank()){
                updateStatus("Çeviri ekranda gösteriliyor")
                main.post{showText(translated)}
                if(prefs.speechEnabled){synchronized(speechQueue){speechQueue.addLast(translated to prefs.speechGender)};speakNext()}
            } else if(error!=null){
                val cause=generateSequence(error){it.cause}.last()
                val message="Çeviri başarısız: ${cause.localizedMessage?:"sağlayıcı yanıt vermedi"}"
                updateStatus(message);showTopError(message)
            }
        }
    }
    /** Game subtitles often OCR as "Speaker: dialogue". Keep only dialogue for translation/TTS. */
    private fun dialogueOnly(raw:String):String=raw.lineSequence().mapNotNull{line->
        val trimmed=line.trim();if(trimmed.isEmpty())return@mapNotNull null
        val colon=trimmed.indexOf(':')
        if(colon in 1..40&&trimmed.substring(0,colon).matches(Regex("[\\p{L}\\p{N}][\\p{L}\\p{N} ._'’\\-\\[\\]]{0,39}")))
            trimmed.substring(colon+1).trim().takeIf{it.isNotEmpty()}
        else trimmed
    }.joinToString("\n")
    private fun isRecentDuplicate(text:String):Boolean{val now=android.os.SystemClock.elapsedRealtime();while(recentOcrTexts.isNotEmpty()&&now-recentOcrTexts.first().second>90000L)recentOcrTexts.removeFirst();if(recentOcrTexts.any{similar(text,it.first)>=.8})return true;recentOcrTexts.addLast(text to now);while(recentOcrTexts.size>40)recentOcrTexts.removeFirst();return false}
    private fun similar(a:String,b:String):Double{if(a.isBlank()||b.isBlank())return 0.0;val x=a.lowercase().replace(Regex("[^\\p{L}\\p{N}]+")," ").trim();val y=b.lowercase().replace(Regex("[^\\p{L}\\p{N}]+")," ").trim();if(x.isBlank()||y.isBlank())return 0.0;var p=IntArray(y.length+1){it};for(i in x.indices){val c=IntArray(y.length+1);c[0]=i+1;for(j in y.indices)c[j+1]=minOf(c[j]+1,p[j+1]+1,p[j]+if(x[i]==y[j])0 else 1);p=c};return 1.0-p[y.length].toDouble()/maxOf(x.length,y.length)}
    private fun resume(){sampleBusy=false;worker.postDelayed({if(!stopped)display?.setSurface(reader?.surface)},prefs.scanIntervalMs.toLong())}
    private fun initTesseract():TessBaseAPI{val base=File(filesDir,"tesseract");val dir=File(base,"tessdata");val model=File(dir,"eng.traineddata");if(!model.exists()){dir.mkdirs();val c=URL("https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/eng.traineddata").openConnection() as HttpURLConnection;c.connectTimeout=15000;c.readTimeout=30000;try{c.inputStream.use{input->model.outputStream().use{input.copyTo(it)}}}finally{c.disconnect()}};return TessBaseAPI().also{if(!it.init(base.absolutePath,"eng")){it.recycle();throw IllegalStateException("İngilizce OCR modeli yüklenemedi")}}}
    private fun showOverlay(){if(overlay!=null)return;val v=TextView(this).apply{gravity=Gravity.CENTER;setPadding(22,10,22,10);visibility=android.view.View.GONE;isFocusable=false;maxWidth=(resources.displayMetrics.widthPixels*.9f).toInt()}
        val m=DisplayMetrics();@Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        val type=if(Build.VERSION.SDK_INT>=26)WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val lp=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.LEFT;x=(m.widthPixels*prefs.overlayX).toInt();y=(m.heightPixels*prefs.overlayY).toInt()}
        try{wm.addView(v,lp);overlay=v}catch(_:Exception){}
    }
    private fun showText(text:String){val v=overlay?:return;v.text=text;v.setTextColor(prefs.captionTextColor);v.textSize=prefs.captionTextSize.toFloat();v.typeface=Typeface.create(prefs.captionFont,Typeface.BOLD);v.background=GradientDrawable().apply{setColor(prefs.captionBackgroundColor);cornerRadius=16f};v.visibility=android.view.View.VISIBLE;v.post{val lp=v.layoutParams as? WindowManager.LayoutParams?:return@post;val m=DisplayMetrics();@Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m);lp.x=(m.widthPixels*prefs.overlayX-v.width/2).toInt().coerceIn(0,(m.widthPixels-v.width).coerceAtLeast(0));lp.y=(m.heightPixels*prefs.overlayY-v.height/2).toInt().coerceIn(0,(m.heightPixels-v.height).coerceAtLeast(0));try{wm.updateViewLayout(v,lp)}catch(_:Exception){}};main.removeCallbacks(clearCaption);main.postDelayed(clearCaption,5000)}
    private val clearCaption=Runnable{overlay?.apply{text="";visibility=android.view.View.GONE}}
    private fun applyLocalVoice(){val engine=tts?:return;val lang=Locale.forLanguageTag(prefs.subtitleTargetLanguage);engine.setLanguage(lang);val voices=engine.voices?.filter{it.locale.language==lang.language}.orEmpty();val selected=prefs.speechVoice.takeIf{it.isNotBlank()}?.let{name->voices.firstOrNull{it.name==name}}?:voices.firstOrNull{(it.name.contains("female",true))==(prefs.speechGender=="female")}?:voices.firstOrNull();if(selected!=null)engine.voice=selected;engine.setSpeechRate(prefs.speechRate)}
    private fun speakNext(){if(!prefs.speechEnabled||speechBusy)return;val item=synchronized(speechQueue){if(speechQueue.isEmpty())null else speechQueue.removeFirst()}?:return;speechBusy=true
        if(prefs.speechProvider=="gemini")speechExecutor.submit{try{val voice=if(item.second=="male")prefs.geminiMaleVoice else prefs.geminiFemaleVoice;val f=GeminiSpeechSynthesizer(this,prefs).synthesize(item.first,item.second,voice);main.post{playSpeechFile(f)}}catch(e:Exception){main.post{android.widget.Toast.makeText(this,"Gemini TTS başarısız: cihaz sesi kullanılacak",android.widget.Toast.LENGTH_SHORT).show();localSpeak(item.first)}}}
        else localSpeak(item.first)
    }
    private fun localSpeak(text:String){if(!ttsReady){speechBusy=false;return};applyLocalVoice();tts?.setOnUtteranceProgressListener(object:android.speech.tts.UtteranceProgressListener(){override fun onStart(id:String?){};override fun onDone(id:String?){speechBusy=false;speakNext()};override fun onError(id:String?){speechBusy=false;speakNext()}});tts?.setPitch(if(prefs.speechGender=="male").92f else 1.08f);tts?.speak(text,TextToSpeech.QUEUE_FLUSH,null,"jcrw-${System.nanoTime()}")}
    private fun playSpeechFile(file:File){try{player?.release();player=MediaPlayer().apply{setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());setVolume(prefs.speechVolume/100f,prefs.speechVolume/100f);setDataSource(file.absolutePath);setOnCompletionListener{release();player=null;file.delete();speechBusy=false;speakNext()};setOnErrorListener{p,_,_->p.release();player=null;file.delete();speechBusy=false;speakNext();true};prepare();start()}}catch(_:Exception){file.delete();speechBusy=false;speakNext()}}
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onDestroy(){prefs.captureRunning=false;prefs.captureHeartbeat=0L;stopped=true;main.removeCallbacksAndMessages(null);worker.removeCallbacksAndMessages(null);display?.release();display=null;reader?.setOnImageAvailableListener(null,null);reader?.close();reader=null;projectionCallback?.let{projection?.unregisterCallback(it)};projection?.stop();projection=null;try{overlay?.let{wm.removeView(it)}}catch(_:Exception){};overlay=null;recognizer?.close();tesseract?.recycle();tts?.stop();tts?.shutdown();player?.release();thread.quitSafely();speechExecutor.shutdownNow();super.onDestroy()}
}
