package com.example.phonematetry.data

import android.content.Context
import java.io.File

data class ModelDataFile(
    val name: String,
    val url: String,
    val downloadFileName: String,
    val sizeInBytes: Long,
)

const val IMPORTS_DIR = "__imports"
private val NORMALIZE_NAME_REGEX = Regex("[^a-zA-Z0-9]")

/** A model for a task */
data class Model(
    /** The name (for display purpose) of the model. */
    val name: String,

    /** The commit hash of the model on HF. */
    val commitHash: String = "_",

    /**
     * The name of the downloaded model file.
     *
     * The final file path of the downloaded model will be:
     * {context.getExternalFilesDir}/{normalizedName}/{version}/{downloadFileName}
     */
    val downloadFileName: String,

    /** The URL to download the model from. */
    val url: String,

    /** The size of the model file in bytes. */
    val sizeInBytes: Long,

    /** A list of additional data files required by the model. */
    val extraDataFiles: List<ModelDataFile> = listOf(),

    /**
     * A description or information about the model.
     *
     * Will be shown at the start of the chat session and in the expanded model item.
     */
    val info: String = "",

    /** The url to jump to when clicking "learn more" in expanded model item. */
    val learnMoreUrl: String = "",

    /** Indicates whether the model is a zip file. */
    val isZip: Boolean = false,

    /** The name of the directory to unzip the model to (if it's a zip file). */
    val unzipDir: String = "",

    /** Whether the model is imported or not. */
    val imported: Boolean = false,

    // The following fields are managed by the app. Don't need to set manually.
    var normalizedName: String = "",
    var instance: Any? = null,
    var initializing: Boolean = false,
    var totalBytes: Long = 0L,
    var accessToken: String? = null,
) {
    init {
        normalizedName = NORMALIZE_NAME_REGEX.replace(name, "_")
    }

    fun preProcess() {
        this.totalBytes = this.sizeInBytes + this.extraDataFiles.sumOf { it.sizeInBytes }
    }

    fun getPath(context: Context, fileName: String = downloadFileName): String {
        if (imported) {
            return listOf(context.getExternalFilesDir(null)?.absolutePath ?: "", fileName)
                .joinToString(File.separator)
        }

        val baseDir =
            listOf(context.getExternalFilesDir(null)?.absolutePath ?: "", normalizedName, commitHash)
                .joinToString(File.separator)
        return if (this.isZip && this.unzipDir.isNotEmpty()) {
            "$baseDir/${this.unzipDir}"
        } else {
            "$baseDir/$fileName"
        }
    }

    fun getExtraDataFile(name: String): ModelDataFile? {
        return extraDataFiles.find { it.name == name }
    }
}

enum class ModelDownloadStatusType {
    NOT_DOWNLOADED,
    PARTIALLY_DOWNLOADED,
    IN_PROGRESS,
    UNZIPPING,
    SUCCEEDED,
    FAILED,
}

data class ModelDownloadStatus(
    val status: ModelDownloadStatusType,
    val totalBytes: Long = 0,
    val receivedBytes: Long = 0,
    val errorMessage: String = "",
    val bytesPerSecond: Long = 0,
    val remainingMs: Long = 0,
)

// Gemma3n E2B模型定义
val GEMMA3N_E2B_MODEL = Model(
    name = "Gemma3n E2B",
    commitHash = "master",
    downloadFileName = "gemma-3n-E2B-it-int4.task",
    url = "https://www.modelscope.cn/models/google/gemma-3n-E2B-it-litert-preview/resolve/master/gemma-3n-E2B-it-int4.task",
    sizeInBytes = 3136226711L,
    info = "Gemma3n E2B是一个高效的语言模型，专为移动设备优化。",
    learnMoreUrl = "https://www.modelscope.cn/models/google/gemma-3n-E2B-it-litert-preview"
).apply {
    preProcess()
}