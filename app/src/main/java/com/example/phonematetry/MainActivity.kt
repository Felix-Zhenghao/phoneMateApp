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
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private val PERMISSION_REQUEST_CODE = 1001
    private val OVERLAY_PERMISSION_REQUEST_CODE = 1002
    private val STORAGE_PERMISSION_REQUEST_CODE = 1003
    private val MEDIA_PROJECTION_REQUEST_CODE = 1004
    private lateinit var btnStartAssistant: Button

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjectionIntent: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        initViews()

        // 检查是否是从Service请求重新获取MediaProjection权限
        if (intent.getBooleanExtra("requestMediaProjection", false)) {
            Toast.makeText(this, "正在重新获取截图权限...", Toast.LENGTH_SHORT).show()
            requestMediaProjectionPermission()
        } else {
            checkPermissions()
        }
    }

    private fun initViews() {
        btnStartAssistant = findViewById(R.id.btnStartAssistant)
        btnStartAssistant.setOnClickListener { startVoiceAssistant() }
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
        checkOverlayPermission()
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName))
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
            }
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
            Toast.makeText(this, "需要录音权限才能启动语音助手", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "需要存储权限才能使用截图功能", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "需要悬浮窗权限才能显示助手界面", Toast.LENGTH_SHORT).show()
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

        Toast.makeText(this, "截图权限已更新", Toast.LENGTH_SHORT).show()
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

        val message = StringBuilder("权限设置完成:\n")
        message.append("🎤 录音权限: ").append(if (hasRecordAudio) "✅ 已授予" else "❌ 被拒绝").append("\n")
        message.append("💾 存储权限: ").append(if (hasStorage) "✅ 已授予" else "❌ 被拒绝").append("\n")
        message.append("🔲 悬浮窗权限: ").append(if (hasOverlay) "✅ 已授予" else "❌ 被拒绝").append("\n\n")

        if (hasRecordAudio && hasStorage && hasOverlay) {
            message.append("🎉 所有权限已授予，可以正常使用所有功能！")
        } else {
            message.append("⚠️ 部分权限被拒绝，相关功能将受限。您可以稍后在设置中手动开启。")
        }

        Toast.makeText(this, message.toString(), Toast.LENGTH_LONG).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "录音权限已授予", Toast.LENGTH_SHORT).show()
                // 继续检查存储权限
                checkStoragePermission()
            } else {
                Toast.makeText(this, "录音权限被拒绝，无法使用语音功能", Toast.LENGTH_SHORT).show()
                // 即使录音权限被拒绝，也继续检查其他权限
                checkStoragePermission()
            }
        } else if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "存储权限已授予，现在可以使用截图功能", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "存储权限被拒绝，无法保存截图", Toast.LENGTH_SHORT).show()
            }
            // 继续检查悬浮窗权限
            checkOverlayPermission()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "悬浮窗权限被拒绝，无法显示助手界面", Toast.LENGTH_SHORT).show()
                }
            }
            // 权限检查完成，显示最终状态
            showPermissionStatus()
        } else if (requestCode == MEDIA_PROJECTION_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                mediaProjectionIntent = data
                Toast.makeText(this, "屏幕录制权限已授予", Toast.LENGTH_SHORT).show()

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
                Toast.makeText(this, "屏幕录制权限被拒绝，截图功能将无法使用", Toast.LENGTH_SHORT).show()

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