package com.example.phonematetry
import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Observer
import com.example.phonematetry.data.ModelDownloadStatusType
import com.example.phonematetry.ModelDownloadManager
import com.example.phonematetry.asr.ASRTest

class MainActivity : AppCompatActivity() {

    private val PERMISSION_REQUEST_CODE = 1001
    private val OVERLAY_PERMISSION_REQUEST_CODE = 1002
    private val STORAGE_PERMISSION_REQUEST_CODE = 1003
    private val MEDIA_PROJECTION_REQUEST_CODE = 1004
    private lateinit var btnStartAssistant: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvDownloadStatus: TextView
    private lateinit var tvDownloadProgress: TextView
    private lateinit var tvDownloadSpeed: TextView
    private lateinit var btnRetryDownload: Button
    private lateinit var btnSettings: ImageButton

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjectionIntent: Intent? = null
    private lateinit var modelDownloadManager: ModelDownloadManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 应用语言设置
        SettingsActivity.applyLanguage(this)
        
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        // 初始化模型下载管理器
        modelDownloadManager = ModelDownloadManager(this)

        initViews()
        setupDownloadObservers()

        // 检查是否是从Service请求重新获取MediaProjection权限
        if (intent.getBooleanExtra("requestMediaProjection", false)) {
            Toast.makeText(this, getString(R.string.permission_reacquiring_screenshot), Toast.LENGTH_SHORT).show()
            requestMediaProjectionPermission()
        } else {
            checkPermissions()
        }
        
        // 测试ASR功能
        testASRFunctionality()
    }

    private fun initViews() {
        btnStartAssistant = findViewById(R.id.btnStartAssistant)
        progressBar = findViewById(R.id.progressBar)
        tvDownloadStatus = findViewById(R.id.tvDownloadStatus)
        tvDownloadProgress = findViewById(R.id.tvDownloadProgress)
        tvDownloadSpeed = findViewById(R.id.tvDownloadSpeed)
        btnRetryDownload = findViewById(R.id.btnRetryDownload)
        btnSettings = findViewById(R.id.btnSettings)
        
        btnStartAssistant.setOnClickListener { startVoiceAssistant() }
        btnRetryDownload.setOnClickListener { modelDownloadManager.retryDownload() }
        btnSettings.setOnClickListener { 
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
        
        // 初始状态下禁用启动按钮
        btnStartAssistant.isEnabled = false
    }
    
    private fun setupDownloadObservers() {
        // 监听下载状态变化
        modelDownloadManager.downloadStatus.observe(this, Observer { status ->
            updateDownloadUI(status)
        })
        
        // 监听模型是否准备就绪
        modelDownloadManager.isModelReady.observe(this, Observer { isReady ->
            btnStartAssistant.isEnabled = isReady
            if (isReady) {
                tvDownloadStatus.text = getString(R.string.download_status_ready)
                progressBar.visibility = android.view.View.GONE
                tvDownloadProgress.visibility = android.view.View.GONE
                tvDownloadSpeed.visibility = android.view.View.GONE
                btnRetryDownload.visibility = android.view.View.GONE
            }
        })
    }
    
    private fun updateDownloadUI(status: com.example.phonematetry.data.ModelDownloadStatus) {
        when (status.status) {
            ModelDownloadStatusType.NOT_DOWNLOADED -> {
                tvDownloadStatus.text = "Preparing to download the model..."
                progressBar.visibility = android.view.View.VISIBLE
                progressBar.progress = 0
                tvDownloadProgress.visibility = android.view.View.GONE
                tvDownloadSpeed.visibility = android.view.View.GONE
                btnRetryDownload.visibility = android.view.View.GONE
            }
            ModelDownloadStatusType.IN_PROGRESS -> {
                val progress = modelDownloadManager.getDownloadProgress()
                tvDownloadStatus.text = getString(R.string.download_status_downloading)
                progressBar.visibility = android.view.View.VISIBLE
                progressBar.progress = progress
                
                val progressText = "${modelDownloadManager.formatFileSize(status.receivedBytes)} / ${modelDownloadManager.formatFileSize(status.totalBytes)} ($progress%)"
                tvDownloadProgress.text = progressText
                tvDownloadProgress.visibility = android.view.View.VISIBLE
                
                val speedText = modelDownloadManager.formatDownloadSpeed(status.bytesPerSecond)
                val remainingText = modelDownloadManager.formatRemainingTime(status.remainingMs)
                tvDownloadSpeed.text = getString(R.string.download_speed_format, speedText, remainingText)
                tvDownloadSpeed.visibility = android.view.View.VISIBLE
                btnRetryDownload.visibility = android.view.View.GONE
            }
            ModelDownloadStatusType.UNZIPPING -> {
                tvDownloadStatus.text = getString(R.string.download_status_unzipping)
                progressBar.visibility = android.view.View.VISIBLE
                progressBar.isIndeterminate = true
                tvDownloadProgress.visibility = android.view.View.GONE
                tvDownloadSpeed.visibility = android.view.View.GONE
                btnRetryDownload.visibility = android.view.View.GONE
            }
            ModelDownloadStatusType.SUCCEEDED -> {
                tvDownloadStatus.text = getString(R.string.download_status_completed)
                progressBar.visibility = android.view.View.GONE
                tvDownloadProgress.visibility = android.view.View.GONE
                tvDownloadSpeed.visibility = android.view.View.GONE
                btnRetryDownload.visibility = android.view.View.GONE
            }
            ModelDownloadStatusType.FAILED -> {
                tvDownloadStatus.text = getString(R.string.download_status_failed, status.errorMessage)
                progressBar.visibility = android.view.View.GONE
                tvDownloadProgress.visibility = android.view.View.GONE
                tvDownloadSpeed.visibility = android.view.View.GONE
                btnRetryDownload.visibility = android.view.View.VISIBLE
            }
            else -> {
                // Handle other states if needed
            }
        }
    }

    private fun testASRFunctionality() {
        // 在后台线程中测试ASR功能
        Thread {
            try {
                val asrTest = ASRTest(this)
                asrTest.runAllTests()
                runOnUiThread {
                    Toast.makeText(this, "ASR Ready", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "ASR Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun checkPermissions() {
        // 按顺序检查权限：录音权限 -> 存储权限 -> 悬浮窗权限
        checkRecordAudioPermission()
    }

    private fun checkRecordAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_CODE)
        } else {
            // 录音权限已有，检查存储权限
            checkStoragePermission()
        }
    }

    private fun checkStoragePermission() {
        val hasStoragePermission: Boolean

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 使用新的媒体权限
            hasStoragePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            if (!hasStoragePermission) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_MEDIA_IMAGES), STORAGE_PERMISSION_REQUEST_CODE)
                return
            }
        } else {
            // Android 12 及以下使用传统存储权限
            hasStoragePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            if (!hasStoragePermission) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), STORAGE_PERMISSION_REQUEST_CODE)
                return
            }
        }

        // 存储权限已有，检查悬浮窗权限
        checkOverlayPermissionAndStartDownload()
    }

    private fun checkOverlayPermissionAndStartDownload() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName))
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
                return
            }
        }
        // 权限检查完成，检查所有权限是否都已授予，然后开始模型下载
        checkAllPermissionsAndStartDownload()
    }

    private fun checkAllPermissionsAndStartDownload() {
        // 检查录音权限
        val hasRecordAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        
        // 检查存储权限
        val hasStorage: Boolean
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasStorage = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            hasStorage = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        
        // 检查悬浮窗权限
        var hasOverlay = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            hasOverlay = Settings.canDrawOverlays(this)
        }
        
        // 只有在所有权限都授予的情况下才开始模型下载
        if (hasRecordAudio && hasStorage && hasOverlay) {
            modelDownloadManager.checkAndDownloadModel()
        }
    }

    private fun startVoiceAssistant() {
        // 检查服务是否已经运行
        if (isServiceRunning(VoiceAssistantService::class.java)) {
            Toast.makeText(this, "APP已经启动，若要关闭，请点击浮窗上的关闭按钮", Toast.LENGTH_LONG).show()
            return
        }

        // 检查权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, getString(R.string.permission_need_audio_for_assistant), Toast.LENGTH_SHORT).show()
            return
        }

        // 检查存储权限
        val hasStoragePermission: Boolean
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasStoragePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            hasStoragePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        if (!hasStoragePermission) {
            Toast.makeText(this, getString(R.string.permission_need_storage_for_screenshot), Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, getString(R.string.permission_need_overlay_for_assistant), Toast.LENGTH_SHORT).show()
            return
        }

        // 请求屏幕录制权限
        requestMediaProjectionPermission()
    }

    private fun requestMediaProjectionPermission() {
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, MEDIA_PROJECTION_REQUEST_CODE)
    }

    private fun startVoiceAssistantService() {
        // 启动语音助手服务
        val serviceIntent = Intent(this, VoiceAssistantService::class.java)
        mediaProjectionIntent?.let {
            serviceIntent.putExtra("mediaProjectionIntent", it)
        }
        startForegroundService(serviceIntent)

        Toast.makeText(this, "手机助手已启动", Toast.LENGTH_SHORT).show()

        // 最小化应用
        moveTaskToBack(true)
    }

    private fun updateServiceMediaProjection() {
        // 更新已运行服务的MediaProjection权限
        val serviceIntent = Intent(this, VoiceAssistantService::class.java)
        mediaProjectionIntent?.let {
            serviceIntent.putExtra("mediaProjectionIntent", it)
            serviceIntent.putExtra("updateMediaProjection", true)
        }
        startForegroundService(serviceIntent)

        Toast.makeText(this, getString(R.string.permission_screenshot_updated), Toast.LENGTH_SHORT).show()
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun showPermissionStatus() {
        val hasRecordAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        val hasStorage: Boolean
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasStorage = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            hasStorage = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        var hasOverlay = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            hasOverlay = Settings.canDrawOverlays(this)
        }

        val message = StringBuilder(getString(R.string.permission_status_complete))
        message.append(if (hasRecordAudio) getString(R.string.permission_audio_granted) else getString(R.string.permission_audio_denied))
        message.append(if (hasStorage) getString(R.string.permission_storage_granted) else getString(R.string.permission_storage_denied))
        message.append(if (hasOverlay) getString(R.string.permission_overlay_granted) else getString(R.string.permission_overlay_denied))

        if (hasRecordAudio && hasStorage && hasOverlay) {
            message.append(getString(R.string.permission_all_granted))
        } else {
            message.append(getString(R.string.permission_some_denied))
        }

        Toast.makeText(this, message.toString(), Toast.LENGTH_LONG).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, getString(R.string.permission_audio_granted_toast), Toast.LENGTH_SHORT).show()
                // 继续检查存储权限
                checkStoragePermission()
            } else {
                Toast.makeText(this, getString(R.string.permission_audio_denied_toast), Toast.LENGTH_SHORT).show()
                // 即使录音权限被拒绝，也继续检查其他权限
                checkStoragePermission()
            }
        } else if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, getString(R.string.permission_storage_granted_toast), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.permission_storage_denied_toast), Toast.LENGTH_SHORT).show()
            }
            // 继续检查悬浮窗权限
            checkOverlayPermissionAndStartDownload()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, getString(R.string.permission_overlay_granted_toast), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.permission_overlay_denied_toast), Toast.LENGTH_SHORT).show()
                }
            }
            // 权限检查完成，显示最终状态并检查所有权限后开始模型下载
            showPermissionStatus()
            checkAllPermissionsAndStartDownload()
        } else if (requestCode == MEDIA_PROJECTION_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                mediaProjectionIntent = data
                Toast.makeText(this, getString(R.string.permission_screen_record_granted), Toast.LENGTH_SHORT).show()

                // 检查是否是重新获取权限的请求
                if (intent.getBooleanExtra("requestMediaProjection", false)) {
                    // 重新获取权限，更新已运行的服务
                    updateServiceMediaProjection()
                    // 关闭Activity，返回到服务
                    finish()
                } else {
                    // 首次启动，启动服务
                    startVoiceAssistantService()
                }
            } else {
                Toast.makeText(this, getString(R.string.permission_screen_record_denied), Toast.LENGTH_SHORT).show()

                if (intent.getBooleanExtra("requestMediaProjection", false)) {
                    // 重新获取权限被拒绝，关闭Activity
                    finish()
                } else {
                    // 首次启动，即使没有屏幕录制权限，也可以启动服务（只是截图功能受限）
                    startVoiceAssistantService()
                }
            }
        }
    }
}