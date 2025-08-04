package com.example.phonematetry
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import android.content.ContentValues
import android.Manifest
import android.content.pm.PackageManager

class VoiceAssistantService : Service() {

    private val binder = LocalBinder()
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var waveformView: WaveformView
    private lateinit var btnCapture: Button
    private lateinit var btnClose: Button

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val bufferSize = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    private val audioBuffer = ShortArray(bufferSize / 2)
    private val handler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var isContinuousRecording = false
    private var isMediaProjectionCallbackRegistered = false
    private var lastCaptureTime = 0L
    private val CAPTURE_COOLDOWN = 1000L // 1秒冷却时间
    private var isCapturing = false

    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "voice_assistant_channel"

    inner class LocalBinder : Binder() {
        fun getService(): VoiceAssistantService = this@VoiceAssistantService
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        setupFloatingWindow()
        startAudioRecording()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            if (it.hasExtra("mediaProjectionIntent")) {
                val projectionIntent = it.getParcelableExtra<Intent>("mediaProjectionIntent") ?: return START_STICKY
                val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, projectionIntent)

                if (it.getBooleanExtra("updateMediaProjection", false)) {
                    // 更新权限后，重新启动持续录屏
                    startContinuousScreenRecording()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioRecording()
        removeFloatingWindow()
        stopContinuousScreenRecording()
        mediaProjection?.stop()
        mediaProjection = null
        isMediaProjectionCallbackRegistered = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Voice Assistant", NotificationManager.IMPORTANCE_DEFAULT)
            channel.description = "Voice Assistant Service Channel"
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) ?: throw IllegalStateException("Failed to create PendingIntent")

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("手机助手")
            .setContentText("语音助手正在运行")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun setupFloatingWindow() {
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_window, null)

        waveformView = floatingView.findViewById(R.id.waveformView)
        btnCapture = floatingView.findViewById(R.id.btnCapture)
        btnClose = floatingView.findViewById(R.id.btnClose)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 100

        windowManager.addView(floatingView, params)

        setupTouchListeners()

        btnCapture.setOnClickListener {
            performScreenCapture()
        }

        btnClose.setOnClickListener {
            stopSelf()
        }
    }

    private fun setupTouchListeners() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        floatingView.findViewById<LinearLayout>(R.id.floatingLayout).setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun removeFloatingWindow() {
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }

    private fun startAudioRecording() {
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            44100,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        audioRecord?.startRecording()
        isRecording = true

        Thread {
            while (isRecording) {
                val read = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                if (read > 0) {
                    val amplitude = calculateAmplitude(audioBuffer, read)
                    handler.post { waveformView.updateAmplitude(amplitude) }
                }
            }
        }.start()
    }

    private fun stopAudioRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun calculateAmplitude(buffer: ShortArray, read: Int): Float {
        var maxAmp = 0
        for (i in 0 until read) {
            maxAmp = max(maxAmp, abs(buffer[i].toInt()))
        }
        return maxAmp / 32767f
    }

    private fun startContinuousScreenRecording() {
        // 如果已经在录屏，直接返回
        if (isContinuousRecording) {
            return
        }
        
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val screenDensity = metrics.densityDpi
    
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
    
        // 只在第一次注册MediaProjection回调
        if (!isMediaProjectionCallbackRegistered) {
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    stopContinuousScreenRecording()
                    isMediaProjectionCallbackRegistered = false
                }
            }, null)
            isMediaProjectionCallbackRegistered = true
        }
    
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    
        isContinuousRecording = true
    }

    private fun stopContinuousScreenRecording() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        isContinuousRecording = false
    }

    private fun performScreenCapture() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCaptureTime < CAPTURE_COOLDOWN || isCapturing) {
            return
        }
        isCapturing = true
        lastCaptureTime = currentTime

        if (mediaProjection == null) {
            requestMediaProjectionPermission()
            isCapturing = false
            return
        }

        if (!checkStoragePermission()) {
            isCapturing = false
            return
        }

        // 确保持续录屏已启动
        if (!isContinuousRecording) {
            startContinuousScreenRecording()
        }
        
        // 从持续录屏中保存当前帧
        saveCurrentFrame()
    }

    private fun saveCurrentFrame() {
        val image = imageReader?.acquireLatestImage()
        if (image != null) {
            val bitmap = imageToBitmap(image)
            saveScreenshot(bitmap)
            image.close()
            showScreenshotAnimation()
        }
        isCapturing = false
    }

    // 移除单次截图方法，因为它会导致MediaProjection重复使用问题
    // private fun captureScreenWithMediaProjection() { ... }

    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)

        return Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }

    private fun requestMediaProjectionPermission() {
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra("requestMediaProjection", true)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = Uri.parse("package:$packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun saveScreenshot(bitmap: Bitmap) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "Screenshot_$timeStamp.png"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveImageToMediaStore(bitmap, fileName)
        } else {
            val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES) ?: return
            val file = File(path, fileName)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }

        Toast.makeText(this, "截图已保存: $fileName", Toast.LENGTH_SHORT).show()
    }

    private fun saveImageToMediaStore(bitmap: Bitmap, fileName: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
    }

    private fun showScreenshotAnimation() {
        val animator = ValueAnimator.ofFloat(1f, 0.5f, 1f)
        animator.duration = 300
        animator.interpolator = LinearInterpolator()
        animator.addUpdateListener { animation ->
            val alpha = animation.animatedValue as Float
            floatingView.alpha = alpha
        }
        animator.start()
    }
}