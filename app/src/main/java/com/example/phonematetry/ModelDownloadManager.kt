package com.example.phonematetry

import android.content.Context
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.example.phonematetry.data.*
import java.io.File

private const val TAG = "ModelDownloadManager"

class ModelDownloadManager(private val context: Context) {
    private val downloadRepository = DefaultDownloadRepository(context)
    
    // LiveData for download status
    val downloadStatus = MutableLiveData<ModelDownloadStatus>()
    val isModelReady = MutableLiveData<Boolean>(false)
    
    init {
        // 初始化时只设置默认状态，不自动开始检查和下载
        // 下载将在权限完全授予后通过 checkAndDownloadModel() 手动触发
        downloadStatus.value = ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED)
        isModelReady.value = false
    }
    
    /**
     * 检查模型是否已下载
     */
    private fun checkModelStatus() {
        val model = GEMMA3N_E2B_MODEL
        val modelPath = model.getPath(context)
        val modelFile = File(modelPath)
        
        Log.d(TAG, "Checking model at path: $modelPath")
        Log.d(TAG, "Expected model size: ${model.sizeInBytes} bytes")
        
        if (modelFile.exists()) {
            val actualSize = modelFile.length()
            Log.d(TAG, "Found model file with size: $actualSize bytes")
            
            if (actualSize == model.sizeInBytes) {
                Log.d(TAG, "Model already exists and is complete")
                downloadStatus.value = ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED)
                isModelReady.value = true
                return
            } else if (actualSize > model.sizeInBytes) {
                Log.w(TAG, "Model file size ($actualSize) is larger than expected (${model.sizeInBytes}). Treating as complete.")
                downloadStatus.value = ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED)
                isModelReady.value = true
                return
            } else {
                Log.d(TAG, "Model file is incomplete ($actualSize/${model.sizeInBytes} bytes)")
                downloadStatus.value = ModelDownloadStatus(
                    status = ModelDownloadStatusType.PARTIALLY_DOWNLOADED,
                    totalBytes = model.sizeInBytes,
                    receivedBytes = actualSize
                )
            }
        } else {
            Log.d(TAG, "Model file not found")
        }
        
        Log.d(TAG, "Starting download")
        downloadStatus.value = ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED)
        isModelReady.value = false
        startDownload()
    }
    
    /**
     * 开始下载模型
     */
    private fun startDownload() {
        val model = GEMMA3N_E2B_MODEL
        Log.d(TAG, "Starting download for model: ${model.name}")
        
        downloadRepository.downloadModel(model) { downloadedModel, status ->
            Log.d(TAG, "Download status updated: ${status.status}")
            downloadStatus.postValue(status)
            
            when (status.status) {
                ModelDownloadStatusType.SUCCEEDED -> {
                    Log.d(TAG, "Model download completed successfully")
                    isModelReady.postValue(true)
                }
                ModelDownloadStatusType.FAILED -> {
                    Log.e(TAG, "Model download failed: ${status.errorMessage}")
                    isModelReady.postValue(false)
                }
                else -> {
                    // Download in progress
                    isModelReady.postValue(false)
                }
            }
        }
    }
    
    /**
     * 取消下载
     */
    fun cancelDownload() {
        val model = GEMMA3N_E2B_MODEL
        downloadRepository.cancelDownloadModel(model)
        downloadStatus.value = ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED)
        isModelReady.value = false
    }
    
    /**
     * 重新开始下载
     */
    fun retryDownload() {
        Log.d(TAG, "Retrying download")
        downloadStatus.value = ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED)
        isModelReady.value = false
        startDownload()
    }
    
    /**
     * 检查并下载模型
     */
    fun checkAndDownloadModel() {
        Log.d(TAG, "Checking and downloading model if needed")
        checkModelStatus()
    }
    
    /**
     * 强制重新检查模型状态
     * 用于解决模型已下载但状态显示错误的问题
     */
    fun forceCheckModelStatus() {
        Log.d(TAG, "Force checking model status")
        checkModelStatus()
    }
    
    /**
     * 获取下载进度百分比
     */
    fun getDownloadProgress(): Int {
        val status = downloadStatus.value ?: return 0
        return if (status.totalBytes > 0) {
            ((status.receivedBytes * 100) / status.totalBytes).toInt()
        } else {
            0
        }
    }
    
    /**
     * 格式化文件大小
     */
    fun formatFileSize(bytes: Long): String {
        val kb = 1000
        val mb = kb * 1000
        val gb = mb * 1000
        
        return when {
            bytes >= gb -> String.format("%.2f GB", bytes.toDouble() / gb)
            bytes >= mb -> String.format("%.2f MB", bytes.toDouble() / mb)
            bytes >= kb -> String.format("%.2f KB", bytes.toDouble() / kb)
            else -> "$bytes B"
        }
    }
    
    /**
     * 格式化下载速度
     */
    fun formatDownloadSpeed(bytesPerSecond: Long): String {
        return "${formatFileSize(bytesPerSecond)}/s"
    }
    
    /**
     * 格式化剩余时间
     */
    fun formatRemainingTime(remainingMs: Long): String {
        if (remainingMs <= 0) return context.getString(R.string.time_calculating)
        
        val seconds = remainingMs / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        
        return when {
            hours > 0 -> context.getString(R.string.time_format_hours_minutes, hours, minutes % 60)
            minutes > 0 -> context.getString(R.string.time_format_minutes_seconds, minutes, seconds % 60)
            else -> context.getString(R.string.time_format_seconds, seconds)
        }
    }
}