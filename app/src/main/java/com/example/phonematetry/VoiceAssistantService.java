package com.example.phonematetry;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.pm.ServiceInfo;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import java.nio.ByteBuffer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import androidx.core.app.NotificationCompat;

public class VoiceAssistantService extends Service {
    private static final String TAG = "VoiceAssistantService";
    private static final String CHANNEL_ID = "VoiceAssistantChannel";
    private static final int NOTIFICATION_ID = 1;
    
    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams floatingParams;
    private AudioRecord audioRecord;
    private MediaProjectionManager mediaProjectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private Handler backgroundHandler;
    private boolean isRecording = false;
    private Thread recordingThread;
    private WaveformView waveformView;
    
    // 拖动相关变量
    private int initialX, initialY;
    private float initialTouchX, initialTouchY;
    
    // 音频录制参数
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private int bufferSize;
    
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            Log.d(TAG, "VoiceAssistantService onCreate 开始");
            
            // 初始化后台Handler
            HandlerThread handlerThread = new HandlerThread("ScreenCaptureThread");
            handlerThread.start();
            backgroundHandler = new Handler(handlerThread.getLooper());
            
            createNotificationChannel();
            initAudioRecord();
            initMediaProjection();
            createFloatingWindow();
            Log.d(TAG, "VoiceAssistantService onCreate 完成");
        } catch (Exception e) {
            Log.e(TAG, "VoiceAssistantService onCreate 失败", e);
            // 即使出现异常，也不要让服务崩溃
            new Handler(Looper.getMainLooper()).post(() -> {
                Toast.makeText(this, "语音助手启动时出现问题：" + e.getMessage(), Toast.LENGTH_LONG).show();
            });
        }
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            Log.d(TAG, "VoiceAssistantService onStartCommand 开始");
            
            // 首先启动前台服务，确保服务类型正确
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, createNotification());
            }
            
            // 然后获取MediaProjection权限
            if (intent != null && intent.hasExtra("mediaProjectionIntent")) {
                Log.d(TAG, "检测到mediaProjectionIntent");
                Intent mediaProjectionIntent = intent.getParcelableExtra("mediaProjectionIntent");
                if (mediaProjectionIntent != null && mediaProjectionManager != null) {
                    try {
                        Log.d(TAG, "开始初始化MediaProjection");
                        mediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, mediaProjectionIntent);
                        if (mediaProjection != null) {
                            Log.d(TAG, "MediaProjection权限已成功获取");
                            new Handler(Looper.getMainLooper()).post(() -> {
                                Toast.makeText(this, "屏幕录制权限已获取，截图功能可用", Toast.LENGTH_SHORT).show();
                            });
                        } else {
                            Log.e(TAG, "MediaProjection为null");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "获取MediaProjection失败", e);
                        new Handler(Looper.getMainLooper()).post(() -> {
                            Toast.makeText(this, "屏幕录制权限初始化失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                        });
                    }
                } else {
                    Log.e(TAG, "mediaProjectionIntent为null或mediaProjectionManager为null");
                    Log.e(TAG, "mediaProjectionIntent: " + mediaProjectionIntent);
                    Log.e(TAG, "mediaProjectionManager: " + mediaProjectionManager);
                }
            } else {
                Log.w(TAG, "Intent中没有mediaProjectionIntent，截图功能将不可用");
                if (intent == null) {
                    Log.w(TAG, "Intent为null");
                } else {
                    Log.w(TAG, "Intent不包含mediaProjectionIntent");
                }
            }
            
            startVoiceRecording();
            Log.d(TAG, "VoiceAssistantService onStartCommand 完成");
            
        } catch (Exception e) {
            Log.e(TAG, "VoiceAssistantService onStartCommand 失败", e);
            new Handler(Looper.getMainLooper()).post(() -> {
                Toast.makeText(this, "语音助手服务启动失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            });
        }
        
        return START_STICKY;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "VoiceAssistantService onDestroy called");
        
        // 停止录音
        stopVoiceRecording();
        
        // 清理MediaProjection资源
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        
        // 清理后台Handler
        if (backgroundHandler != null) {
            backgroundHandler.getLooper().quitSafely();
            backgroundHandler = null;
        }
        
        // 移除悬浮窗
        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception e) {
                Log.e(TAG, "Error removing floating view", e);
            }
            floatingView = null;
        }
        
        Log.d(TAG, "VoiceAssistantService destroyed");
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "语音助手服务",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("语音助手后台服务");
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }
    
    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("手机助手")
                .setContentText("语音助手正在运行")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
    
    private void initAudioRecord() {
        bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        
        try {
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            );
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }
    
    private void startVoiceRecording() {
        if (audioRecord != null && audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
            isRecording = true;
            audioRecord.startRecording();
            
            // 通知波形视图开始录音
            if (waveformView != null) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    waveformView.setRecording(true);
                });
            }
            
            recordingThread = new Thread(() -> {
                byte[] buffer = new byte[bufferSize];
                while (isRecording) {
                    int bytesRead = audioRecord.read(buffer, 0, bufferSize);
                    if (bytesRead > 0) {
                        // 这里可以处理音频数据，比如语音识别
                        processAudioData(buffer, bytesRead);
                    }
                }
            });
            recordingThread.start();
        }
    }
    
    private void stopVoiceRecording() {
        isRecording = false;
        
        // 通知波形视图停止录音
        if (waveformView != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                waveformView.setRecording(false);
            });
        }
        
        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }
        if (recordingThread != null) {
            recordingThread.interrupt();
        }
    }
    
    private void processAudioData(byte[] buffer, int bytesRead) {
        // 计算音频振幅用于波形显示
        if (waveformView != null && bytesRead > 0) {
            float amplitude = calculateAmplitude(buffer, bytesRead);
            new Handler(Looper.getMainLooper()).post(() -> {
                waveformView.addAmplitude(amplitude);
            });
        }
        
        // 这里可以添加其他语音处理逻辑
        // 比如语音识别、语音唤醒等
    }
    
    private float calculateAmplitude(byte[] buffer, int bytesRead) {
        // 计算RMS（均方根）振幅
        long sum = 0;
        int sampleCount = bytesRead / 2; // 16位音频，每个样本2字节
        
        for (int i = 0; i < bytesRead - 1; i += 2) {
            // 将两个字节组合成一个16位样本
            short sample = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF));
            sum += sample * sample;
        }
        
        if (sampleCount > 0) {
            double rms = Math.sqrt((double) sum / sampleCount);
            // 标准化到-1.0到1.0范围，32767是16位音频的最大值
            float normalizedAmplitude = (float) (rms / 32767.0);
            // 增加振幅放大倍数，让波形更加剧烈
            normalizedAmplitude *= 8.0f; // 放大8倍
            // 限制在合理范围内
            return Math.min(1.0f, normalizedAmplitude);
        }
        
        return 0.0f;
    }
    
    private void createFloatingWindow() {
        try {
            // 检查悬浮窗权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!android.provider.Settings.canDrawOverlays(this)) {
                    Log.e(TAG, "没有悬浮窗权限，无法创建浮窗");
                    new Handler(Looper.getMainLooper()).post(() -> {
                        Toast.makeText(this, "缺少悬浮窗权限，请在设置中开启", Toast.LENGTH_LONG).show();
                    });
                    return;
                }
            }
            
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (windowManager == null) {
                Log.e(TAG, "无法获取WindowManager");
                return;
            }
            
            LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
            if (inflater == null) {
                Log.e(TAG, "无法获取LayoutInflater");
                return;
            }
            
            floatingView = inflater.inflate(R.layout.floating_window, null);
            
            // 设置悬浮窗参数
            floatingParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            );
            
            floatingParams.gravity = Gravity.TOP | Gravity.START;
            floatingParams.x = 100;
            floatingParams.y = 100;
            
            // 初始化波形视图
            waveformView = floatingView.findViewById(R.id.waveformView);
            
            // 添加按钮点击事件
            Button btnScreenshot = floatingView.findViewById(R.id.btnScreenshot);
            Button btnClose = floatingView.findViewById(R.id.btnClose);
            
            if (btnScreenshot != null) {
                btnScreenshot.setOnClickListener(v -> takeScreenshot());
            }
            if (btnClose != null) {
                btnClose.setOnClickListener(v -> stopSelf());
            }
            
            // 添加拖动功能
            floatingView.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            initialX = floatingParams.x;
                            initialY = floatingParams.y;
                            initialTouchX = event.getRawX();
                            initialTouchY = event.getRawY();
                            return true;
                            
                        case MotionEvent.ACTION_MOVE:
                            floatingParams.x = initialX + (int) (event.getRawX() - initialTouchX);
                            floatingParams.y = initialY + (int) (event.getRawY() - initialTouchY);
                            windowManager.updateViewLayout(floatingView, floatingParams);
                            return true;
                            
                        case MotionEvent.ACTION_UP:
                            // 检查是否是点击事件（移动距离很小）
                            float deltaX = Math.abs(event.getRawX() - initialTouchX);
                            float deltaY = Math.abs(event.getRawY() - initialTouchY);
                            if (deltaX < 10 && deltaY < 10) {
                                // 这是一个点击事件，让子视图处理
                                return false;
                            }
                            return true;
                    }
                    return false;
                }
            });
            
            // 添加悬浮窗到屏幕
            windowManager.addView(floatingView, floatingParams);
            Log.d(TAG, "悬浮窗创建成功");
            
        } catch (Exception e) {
            Log.e(TAG, "创建悬浮窗失败", e);
            new Handler(Looper.getMainLooper()).post(() -> {
                Toast.makeText(this, "创建悬浮窗失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            });
        }
    }
    
    private void removeFloatingWindow() {
        if (floatingView != null && windowManager != null) {
            windowManager.removeView(floatingView);
            floatingView = null;
        }
    }
    
    private void takeScreenshot() {
        // 检查存储权限
        boolean hasStoragePermission = checkStoragePermission();
        if (!hasStoragePermission) {
            // 提示用户开启权限
            new Handler(Looper.getMainLooper()).post(() -> {
                Toast.makeText(this, "截图功能需要存储权限，请在设置中开启", Toast.LENGTH_LONG).show();
                openAppSettings();
            });
            return;
        }
        
        // 显示截图提示
        new Handler(Looper.getMainLooper()).post(() -> {
            Toast.makeText(this, "正在截图...", Toast.LENGTH_SHORT).show();
        });
        
        // 临时隐藏浮窗，避免截图中包含浮窗
        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
                
                // 延迟一小段时间确保浮窗完全隐藏，然后进行截图
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    performScreenCapture();
                    
                    // 截图完成后，延迟一段时间再重新显示浮窗
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (floatingView != null) {
                            try {
                                windowManager.addView(floatingView, floatingParams);
                            } catch (Exception e) {
                                Log.e(TAG, "重新显示浮窗失败", e);
                            }
                        }
                    }, 1000); // 1秒后重新显示浮窗
                }, 200); // 200毫秒后截图
                
            } catch (Exception e) {
                Log.e(TAG, "隐藏浮窗失败，直接截图", e);
                performScreenCapture();
            }
        } else {
            performScreenCapture();
        }
    }
    
    private void initMediaProjection() {
        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
    }
    
    private void performScreenCapture() {
        Log.d(TAG, "performScreenCapture called");
        
        try {
            if (mediaProjection == null) {
                Log.e(TAG, "MediaProjection is null, cannot capture screen");
                Toast.makeText(this, "截图失败：需要屏幕录制权限", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 获取屏幕尺寸
            WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (windowManager == null) {
                Log.e(TAG, "WindowManager is null");
                return;
            }
            
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getMetrics(metrics);
            int screenWidth = metrics.widthPixels;
            int screenHeight = metrics.heightPixels;
            int screenDensity = metrics.densityDpi;
            
            Log.d(TAG, "Screen dimensions: " + screenWidth + "x" + screenHeight + ", density: " + screenDensity);
            
            // 使用改进的MediaProjection截图方式
            captureScreenWithMediaProjection(screenWidth, screenHeight, screenDensity);
            
        } catch (Exception e) {
            Log.e(TAG, "Error in performScreenCapture", e);
            Toast.makeText(this, "截图失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void captureScreenWithMediaProjection(int screenWidth, int screenHeight, int screenDensity) {
        Log.d(TAG, "captureScreenWithMediaProjection called");
        
        // 创建ImageReader，使用RGBA_8888格式确保颜色正确，设置为1确保只有一张图像
        ImageReader imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 1);
        
        // 使用AtomicBoolean确保只处理一次截图
        final java.util.concurrent.atomic.AtomicBoolean screenshotProcessed = new java.util.concurrent.atomic.AtomicBoolean(false);
        
        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                // 确保只处理一次截图
                if (!screenshotProcessed.compareAndSet(false, true)) {
                    Log.d(TAG, "Screenshot already processed, skipping");
                    return;
                }
                
                Log.d(TAG, "Image available in ImageReader");
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image != null) {
                        Log.d(TAG, "Image acquired successfully, format: " + image.getFormat());
                        
                        // 使用改进的图像转换方法
                        Bitmap bitmap = convertImageToBitmap(image);
                        if (bitmap != null && !bitmap.isRecycled()) {
                            Log.d(TAG, "Bitmap conversion successful, size: " + bitmap.getWidth() + "x" + bitmap.getHeight());
                            saveScreenshot(bitmap);
                            showScreenshotAnimation();
                        } else {
                            Log.e(TAG, "Bitmap conversion failed or bitmap is recycled");
                            Toast.makeText(VoiceAssistantService.this, "截图转换失败", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        Log.e(TAG, "Failed to acquire image");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error processing image", e);
                } finally {
                    if (image != null) {
                        image.close();
                    }
                    // 延迟清理资源，确保截图完成
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            if (virtualDisplay != null) {
                                virtualDisplay.release();
                                virtualDisplay = null;
                            }
                            if (imageReader != null) {
                                imageReader.close();
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error cleaning up resources", e);
                        }
                    }, 500);
                }
            }
        }, backgroundHandler);
        
        // 创建VirtualDisplay
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.getSurface(), null, backgroundHandler
        );
        
        if (virtualDisplay != null) {
            Log.d(TAG, "VirtualDisplay created successfully");
        } else {
            Log.e(TAG, "Failed to create VirtualDisplay");
        }
    }
    
    private Bitmap imageToBitmap(Image image) {
        try {
            Log.d(TAG, "开始转换Image到Bitmap");
            Log.d(TAG, "Image尺寸: " + image.getWidth() + "x" + image.getHeight());
            Log.d(TAG, "Image格式: " + image.getFormat());
            
            Image.Plane[] planes = image.getPlanes();
            if (planes.length == 0) {
                Log.e(TAG, "Image没有planes");
                return null;
            }
            
            Image.Plane plane = planes[0];
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * image.getWidth();
            
            Log.d(TAG, "pixelStride: " + pixelStride + ", rowStride: " + rowStride + ", rowPadding: " + rowPadding);
            
            // 创建正确尺寸的Bitmap
            int bitmapWidth = image.getWidth() + rowPadding / pixelStride;
            Bitmap bitmap = Bitmap.createBitmap(
                bitmapWidth,
                image.getHeight(),
                Bitmap.Config.ARGB_8888
            );
            
            // 复制像素数据
            bitmap.copyPixelsFromBuffer(plane.getBuffer());
            Log.d(TAG, "像素数据复制完成");
            
            // 如果有padding，需要裁剪到正确尺寸
            if (rowPadding != 0) {
                Log.d(TAG, "裁剪Bitmap到正确尺寸");
                Bitmap croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, image.getWidth(), image.getHeight());
                bitmap.recycle(); // 释放原始bitmap
                bitmap = croppedBitmap;
            }
            
            Log.d(TAG, "Bitmap转换成功，最终尺寸: " + bitmap.getWidth() + "x" + bitmap.getHeight());
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Image转Bitmap失败", e);
            return null;
        }
    }
    
    private Bitmap convertImageToBitmap(Image image) {
        try {
            Log.d(TAG, "Converting Image to Bitmap with improved method");
            
            if (image == null) {
                Log.e(TAG, "Image is null");
                return null;
            }
            
            Image.Plane[] planes = image.getPlanes();
            if (planes.length == 0) {
                Log.e(TAG, "No planes in image");
                return null;
            }
            
            Image.Plane plane = planes[0];
            ByteBuffer buffer = plane.getBuffer();
            
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * image.getWidth();
            
            Log.d(TAG, "Image details - Width: " + image.getWidth() + ", Height: " + image.getHeight());
            Log.d(TAG, "Plane details - PixelStride: " + pixelStride + ", RowStride: " + rowStride + ", RowPadding: " + rowPadding);
            
            // 创建正确尺寸的bitmap
            int width = image.getWidth();
            int height = image.getHeight();
            
            // 处理有padding的情况
            if (rowPadding == 0) {
                // 没有padding，直接创建bitmap
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bitmap.copyPixelsFromBuffer(buffer);
                Log.d(TAG, "Created bitmap without padding: " + bitmap.getWidth() + "x" + bitmap.getHeight());
                return bitmap;
            } else {
                // 有padding，需要特殊处理
                int bitmapWidth = width + rowPadding / pixelStride;
                Bitmap fullBitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888);
                fullBitmap.copyPixelsFromBuffer(buffer);
                
                // 裁剪掉padding部分
                Bitmap croppedBitmap = Bitmap.createBitmap(fullBitmap, 0, 0, width, height);
                fullBitmap.recycle();
                
                Log.d(TAG, "Created bitmap with padding removal: " + croppedBitmap.getWidth() + "x" + croppedBitmap.getHeight());
                return croppedBitmap;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error converting image to bitmap", e);
            return null;
        }
    }
    
    private void createPermissionPromptScreenshot() {
        try {
            // 获取屏幕尺寸
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            int width = metrics.widthPixels;
            int height = metrics.heightPixels;
            
            // 创建一个带提示信息的截图
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(0xFF2196F3); // 蓝色背景
            
            // 保存截图
            saveScreenshot(bitmap);
            
        } catch (Exception e) {
            e.printStackTrace();
            new Handler(Looper.getMainLooper()).post(() -> {
                Toast.makeText(this, "创建提示截图失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            });
        }
    }
    
    private boolean checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
        } else {
            return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }
    
    private void openAppSettings() {
        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }
    
    private void saveScreenshot(Bitmap bitmap) {
        try {
            // 创建文件名
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = "Screenshot_" + timeStamp + ".png";
            
            File file;
            
            // 根据Android版本选择不同的保存路径
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10及以上使用MediaStore API
                saveImageToMediaStore(bitmap, fileName);
                return;
            } else {
                // Android 9及以下使用传统文件系统
                // 获取保存路径
                File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                File screenshotDir = new File(picturesDir, "手机助手截图");
                
                // 确保目录存在
                if (!screenshotDir.exists()) {
                    boolean dirCreated = screenshotDir.mkdirs();
                    if (!dirCreated) {
                        throw new IOException("无法创建目录: " + screenshotDir.getAbsolutePath());
                    }
                }
                
                file = new File(screenshotDir, fileName);
                
                // 保存bitmap到文件
                FileOutputStream fos = new FileOutputStream(file);
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush();
                fos.close();
                
                // 显示成功提示
                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(this, "截图已保存到：" + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
                });
            }
            
        } catch (IOException e) {
            e.printStackTrace();
            new Handler(Looper.getMainLooper()).post(() -> {
                Toast.makeText(this, "保存截图失败：" + e.getMessage() + "，请确保已授予存储权限", Toast.LENGTH_LONG).show();
                openAppSettings();
            });
        }
    }
    
    private void saveImageToMediaStore(Bitmap bitmap, String fileName) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + "手机助手截图");
            
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os != null) {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
                        
                        // 显示成功提示
                        new Handler(Looper.getMainLooper()).post(() -> {
                            Toast.makeText(this, "截图已保存到相册的'手机助手截图'文件夹", Toast.LENGTH_LONG).show();
                        });
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            new Handler(Looper.getMainLooper()).post(() -> {
                Toast.makeText(this, "保存截图失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            });
        }
    }
    
    private void showScreenshotAnimation() {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                // 创建截图动画视图
                View animationView = LayoutInflater.from(this).inflate(R.layout.screenshot_animation, null);
                ImageView flashView = animationView.findViewById(R.id.flashView);
                
                // 设置动画视图参数
                WindowManager.LayoutParams animParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                );
                
                // 添加动画视图到屏幕
                windowManager.addView(animationView, animParams);
                
                // 创建闪烁动画
                Animation flashAnimation = AnimationUtils.loadAnimation(this, R.anim.screenshot_flash);
                flashView.startAnimation(flashAnimation);
                
                // 动画结束后移除视图
                flashAnimation.setAnimationListener(new Animation.AnimationListener() {
                    @Override
                    public void onAnimationStart(Animation animation) {}
                    
                    @Override
                    public void onAnimationEnd(Animation animation) {
                        try {
                            windowManager.removeView(animationView);
                        } catch (Exception e) {
                            Log.e(TAG, "移除动画视图失败", e);
                        }
                    }
                    
                    @Override
                    public void onAnimationRepeat(Animation animation) {}
                });
                
            } catch (Exception e) {
                Log.e(TAG, "显示截图动画失败", e);
            }
        });
    }
}