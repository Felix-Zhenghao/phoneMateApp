package com.example.phonematetry.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.phonematetry.data.*
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "DownloadWorker"

data class UrlAndFileName(val url: String, val fileName: String)

private const val FOREGROUND_NOTIFICATION_CHANNEL_ID = "model_download_channel_foreground"
private var channelCreated = false

@RequiresApi(Build.VERSION_CODES.O)
class DownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    private val externalFilesDir = context.getExternalFilesDir(null)

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // Unique notification id.
    private val notificationId: Int = params.id.hashCode()

    init {
        if (!channelCreated) {
            // Create a notification channel for showing notifications for model downloading progress.
            val channel =
                NotificationChannel(
                    FOREGROUND_NOTIFICATION_CHANNEL_ID,
                    "Model Downloading",
                    // Make it silent.
                    NotificationManager.IMPORTANCE_LOW,
                )
                    .apply { description = "Notifications for model downloading" }
            notificationManager.createNotificationChannel(channel)
            channelCreated = true
        }
    }

    override suspend fun doWork(): Result {
        val fileUrl = inputData.getString(KEY_MODEL_URL)
        val modelName = inputData.getString(KEY_MODEL_NAME) ?: "Model"
        val version = inputData.getString(KEY_MODEL_COMMIT_HASH)!!
        val fileName = inputData.getString(KEY_MODEL_DOWNLOAD_FILE_NAME)
        val modelDir = inputData.getString(KEY_MODEL_DOWNLOAD_MODEL_DIR)!!
        val isZip = inputData.getBoolean(KEY_MODEL_IS_ZIP, false)
        val unzippedDir = inputData.getString(KEY_MODEL_UNZIPPED_DIR)
        val extraDataFileUrls = inputData.getString(KEY_MODEL_EXTRA_DATA_URLS)?.split(",") ?: listOf()
        val extraDataFileNames =
            inputData.getString(KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES)?.split(",") ?: listOf()
        val totalBytes = inputData.getLong(KEY_MODEL_TOTAL_BYTES, 0L)
        val accessToken = inputData.getString(KEY_MODEL_DOWNLOAD_ACCESS_TOKEN)

        return withContext(Dispatchers.IO) {
            if (fileUrl == null || fileName == null) {
                Result.failure()
            } else {
                return@withContext try {
                    // Set the worker as a foreground service immediately.
                    setForeground(createForegroundInfo(progress = 0, modelName = modelName))

                    // Collect data for all files.
                    val allFiles: MutableList<UrlAndFileName> = mutableListOf()
                    allFiles.add(UrlAndFileName(url = fileUrl, fileName = fileName))
                    for (index in extraDataFileUrls.indices) {
                        allFiles.add(
                            UrlAndFileName(url = extraDataFileUrls[index], fileName = extraDataFileNames[index])
                        )
                    }
                    Log.d(TAG, "About to download: $allFiles")

                    // Download them in sequence.
                    var downloadedBytes = 0L
                    val bytesReadSizeBuffer: MutableList<Long> = mutableListOf()
                    val bytesReadLatencyBuffer: MutableList<Long> = mutableListOf()
                    for (file in allFiles) {
                        val url = URL(file.url)

                        val connection = url.openConnection() as HttpURLConnection
                        if (accessToken != null) {
                            Log.d(TAG, "Using access token: ${accessToken.subSequence(0, 10)}...")
                            connection.setRequestProperty("Authorization", "Bearer $accessToken")
                        }

                        // Prepare output file's dir.
                        val outputDir =
                            File(
                                applicationContext.getExternalFilesDir(null),
                                listOf(modelDir, version).joinToString(separator = File.separator),
                            )
                        if (!outputDir.exists()) {
                            outputDir.mkdirs()
                        }

                        // Read the file and see if it is partially downloaded.
                        val outputFile =
                            File(
                                applicationContext.getExternalFilesDir(null),
                                listOf(modelDir, version, file.fileName).joinToString(separator = File.separator),
                            )
                        val outputFileBytes = outputFile.length()
                        if (outputFileBytes > 0) {
                            Log.d(
                                TAG,
                                "File '${file.fileName}' partial size: ${outputFileBytes}. Trying to resume download",
                            )
                            connection.setRequestProperty("Range", "bytes=${outputFileBytes}-")
                        }
                        connection.connect()
                        Log.d(TAG, "response code: ${connection.responseCode}")

                        if (
                            connection.responseCode == HttpURLConnection.HTTP_OK ||
                            connection.responseCode == HttpURLConnection.HTTP_PARTIAL
                        ) {
                            val contentRange = connection.getHeaderField("Content-Range")

                            if (contentRange != null) {
                                // Parse the Content-Range header
                                val rangeParts = contentRange.substringAfter("bytes ").split("/")
                                val byteRange = rangeParts[0].split("-")
                                val startByte = byteRange[0].toLong()
                                val endByte = byteRange[1].toLong()

                                Log.d(
                                    TAG,
                                    "Content-Range: $contentRange. Start bytes: ${startByte}, end bytes: $endByte",
                                )

                                downloadedBytes += startByte
                            } else {
                                Log.d(TAG, "Download starts from beginning.")
                            }
                        } else if (connection.responseCode == 416) {
                            // HTTP 416 - Requested Range Not Satisfiable
                            // This usually means the file is already completely downloaded
                            Log.d(TAG, "HTTP 416 - File may already be completely downloaded")
                            
                            // Check if the file size matches the expected size
                            val expectedSize = connection.getHeaderField("Content-Range")?.let { range ->
                                // Extract total size from Content-Range header like "bytes */1234567"
                                range.substringAfter("/").toLongOrNull()
                            }
                            
                            if (expectedSize != null && outputFileBytes == expectedSize) {
                                Log.d(TAG, "File is already completely downloaded (${outputFileBytes} bytes)")
                                downloadedBytes += outputFileBytes
                                // Skip downloading this file as it's already complete
                                continue
                            } else {
                                Log.w(TAG, "File size mismatch. Expected: $expectedSize, Actual: $outputFileBytes. Restarting download.")
                                // Delete the file and restart download from beginning
                                outputFile.delete()
                                // Reconnect without Range header
                                connection.disconnect()
                                val newConnection = url.openConnection() as HttpURLConnection
                                if (accessToken != null) {
                                    newConnection.setRequestProperty("Authorization", "Bearer $accessToken")
                                }
                                newConnection.connect()
                                if (newConnection.responseCode != HttpURLConnection.HTTP_OK) {
                                    throw IOException("HTTP error code: ${newConnection.responseCode}")
                                }
                                // Update connection reference
                                connection.disconnect()
                                // Continue with the new connection
                                val newInputStream = newConnection.inputStream
                                val newOutputStream = FileOutputStream(outputFile, false /* overwrite */)
                                
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var bytesRead: Int
                                var lastSetProgressTs: Long = 0
                                var deltaBytes = 0L
                                while (newInputStream.read(buffer).also { bytesRead = it } != -1) {
                                    newOutputStream.write(buffer, 0, bytesRead)
                                    downloadedBytes += bytesRead
                                    deltaBytes += bytesRead

                                    // Report progress every 200 ms.
                                    val curTs = System.currentTimeMillis()
                                    if (curTs - lastSetProgressTs > 200) {
                                        // Calculate download rate.
                                        var bytesPerMs = 0f
                                        if (lastSetProgressTs != 0L) {
                                            if (bytesReadSizeBuffer.size == 5) {
                                                bytesReadSizeBuffer.removeAt(0)
                                            }
                                            bytesReadSizeBuffer.add(deltaBytes)
                                            if (bytesReadLatencyBuffer.size == 5) {
                                                bytesReadLatencyBuffer.removeAt(0)
                                            }
                                            bytesReadLatencyBuffer.add(curTs - lastSetProgressTs)
                                            deltaBytes = 0L
                                            bytesPerMs = bytesReadSizeBuffer.sum().toFloat() / bytesReadLatencyBuffer.sum()
                                        }

                                        // Calculate remaining seconds
                                        var remainingMs = 0f
                                        if (bytesPerMs > 0f && totalBytes > 0L) {
                                            remainingMs = (totalBytes - downloadedBytes) / bytesPerMs
                                        }

                                        setProgress(
                                            Data.Builder()
                                                .putLong(KEY_MODEL_DOWNLOAD_RECEIVED_BYTES, downloadedBytes)
                                                .putLong(KEY_MODEL_DOWNLOAD_RATE, (bytesPerMs * 1000).toLong())
                                                .putLong(KEY_MODEL_DOWNLOAD_REMAINING_MS, remainingMs.toLong())
                                                .build()
                                        )
                                        setForeground(
                                            createForegroundInfo(
                                                progress = (downloadedBytes * 100 / totalBytes).toInt(),
                                                modelName = modelName,
                                            )
                                        )
                                        Log.d(TAG, "downloadedBytes: $downloadedBytes")
                                        lastSetProgressTs = curTs
                                    }
                                }
                                
                                newOutputStream.close()
                                newInputStream.close()
                                newConnection.disconnect()
                                continue
                            }
                        } else {
                            throw IOException("HTTP error code: ${connection.responseCode}")
                        }

                        val inputStream = connection.inputStream
                        val outputStream = FileOutputStream(outputFile, true /* append */)

                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var bytesRead: Int
                        var lastSetProgressTs: Long = 0
                        var deltaBytes = 0L
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            deltaBytes += bytesRead

                            // Report progress every 200 ms.
                            val curTs = System.currentTimeMillis()
                            if (curTs - lastSetProgressTs > 200) {
                                // Calculate download rate.
                                var bytesPerMs = 0f
                                if (lastSetProgressTs != 0L) {
                                    if (bytesReadSizeBuffer.size == 5) {
                                        bytesReadSizeBuffer.removeAt(0)
                                    }
                                    bytesReadSizeBuffer.add(deltaBytes)
                                    if (bytesReadLatencyBuffer.size == 5) {
                                        bytesReadLatencyBuffer.removeAt(0)
                                    }
                                    bytesReadLatencyBuffer.add(curTs - lastSetProgressTs)
                                    deltaBytes = 0L
                                    bytesPerMs = bytesReadSizeBuffer.sum().toFloat() / bytesReadLatencyBuffer.sum()
                                }

                                // Calculate remaining seconds
                                var remainingMs = 0f
                                if (bytesPerMs > 0f && totalBytes > 0L) {
                                    remainingMs = (totalBytes - downloadedBytes) / bytesPerMs
                                }

                                setProgress(
                                    Data.Builder()
                                        .putLong(KEY_MODEL_DOWNLOAD_RECEIVED_BYTES, downloadedBytes)
                                        .putLong(KEY_MODEL_DOWNLOAD_RATE, (bytesPerMs * 1000).toLong())
                                        .putLong(KEY_MODEL_DOWNLOAD_REMAINING_MS, remainingMs.toLong())
                                        .build()
                                )
                                setForeground(
                                    createForegroundInfo(
                                        progress = (downloadedBytes * 100 / totalBytes).toInt(),
                                        modelName = modelName,
                                    )
                                )
                                Log.d(TAG, "downloadedBytes: $downloadedBytes")
                                lastSetProgressTs = curTs
                            }
                        }

                        outputStream.close()
                        inputStream.close()

                        Log.d(TAG, "Download done")

                        // Unzip if the downloaded file is a zip.
                        if (isZip && unzippedDir != null) {
                            setProgress(Data.Builder().putBoolean(KEY_MODEL_START_UNZIPPING, true).build())

                            // Prepare target dir.
                            val destDir =
                                File(
                                    externalFilesDir,
                                    listOf(modelDir, version, unzippedDir).joinToString(File.separator),
                                )
                            if (!destDir.exists()) {
                                destDir.mkdirs()
                            }

                            // Unzip.
                            val unzipBuffer = ByteArray(4096)
                            val zipFilePath =
                                "${externalFilesDir}${File.separator}$modelDir${File.separator}$version${File.separator}${fileName}"
                            val zipIn = ZipInputStream(BufferedInputStream(FileInputStream(zipFilePath)))
                            var zipEntry: ZipEntry? = zipIn.nextEntry

                            while (zipEntry != null) {
                                val filePath = destDir.absolutePath + File.separator + zipEntry.name

                                // Extract files.
                                if (!zipEntry.isDirectory) {
                                    // extract file
                                    val bos = FileOutputStream(filePath)
                                    bos.use { curBos ->
                                        var len: Int
                                        while (zipIn.read(unzipBuffer).also { len = it } > 0) {
                                            curBos.write(unzipBuffer, 0, len)
                                        }
                                    }
                                }
                                // Create dir.
                                else {
                                    val dir = File(filePath)
                                    dir.mkdirs()
                                }

                                zipIn.closeEntry()
                                zipEntry = zipIn.nextEntry
                            }
                            zipIn.close()

                            // Delete the original file.
                            val zipFile = File(zipFilePath)
                            zipFile.delete()
                        }
                    }
                    Result.success()
                } catch (e: IOException) {
                    Result.failure(
                        Data.Builder().putString(KEY_MODEL_DOWNLOAD_ERROR_MESSAGE, e.message).build()
                    )
                }
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        // Initial progress is 0
        return createForegroundInfo(0)
    }

    /**
     * Creates a [ForegroundInfo] object for the download worker's ongoing notification. This
     * notification is used to keep the worker running in the foreground, indicating to the user that
     * an active download is in progress.
     */
    private fun createForegroundInfo(progress: Int, modelName: String? = null): ForegroundInfo {
        // Create a notification for the foreground service
        var title = "正在下载模型"
        if (modelName != null) {
            title = "正在下载 \"$modelName\""
        }
        val content = "下载进度: $progress%"

        val notification =
            NotificationCompat.Builder(applicationContext, FOREGROUND_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true) // Makes the notification non-dismissable
                .setProgress(100, progress, false) // Show progress
                .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }
}