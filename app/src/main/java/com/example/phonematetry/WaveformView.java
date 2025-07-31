package com.example.phonematetry;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class WaveformView extends View {
    private Paint paint;
    private List<Float> amplitudes;
    private int maxAmplitudes = 50; // 最多显示50个振幅点
    private float baseLineY;
    private boolean isRecording = false;
    
    public WaveformView(Context context) {
        super(context);
        init();
    }
    
    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public WaveformView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    private void init() {
        paint = new Paint();
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(3f);
        paint.setAntiAlias(true);
        amplitudes = new ArrayList<>();
    }
    
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        baseLineY = h / 2f;
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (getWidth() == 0 || getHeight() == 0) {
            return;
        }
        
        if (!isRecording || amplitudes.isEmpty()) {
            // 没有录音时显示直线
            canvas.drawLine(0, baseLineY, getWidth(), baseLineY, paint);
            return;
        }
        
        // 绘制波形
        float width = getWidth();
        float stepX = width / (maxAmplitudes - 1);
        
        for (int i = 0; i < amplitudes.size() - 1; i++) {
            float x1 = i * stepX;
            float x2 = (i + 1) * stepX;
            
            float amplitude1 = amplitudes.get(i);
            float amplitude2 = amplitudes.get(i + 1);
            
            // 将振幅映射到视图高度，增加放大系数让波形更剧烈
            float y1 = baseLineY - (amplitude1 * getHeight() * 1.5f);
            float y2 = baseLineY - (amplitude2 * getHeight() * 1.5f);
            
            canvas.drawLine(x1, y1, x2, y2, paint);
        }
    }
    
    public void addAmplitude(float amplitude) {
        // 标准化振幅到 -1.0 到 1.0 范围
        amplitude = Math.max(-1.0f, Math.min(1.0f, amplitude));
        
        amplitudes.add(amplitude);
        
        // 保持固定数量的振幅点
        if (amplitudes.size() > maxAmplitudes) {
            amplitudes.remove(0);
        }
        
        // 触发重绘
        post(this::invalidate);
    }
    
    public void setRecording(boolean recording) {
        this.isRecording = recording;
        if (!recording) {
            amplitudes.clear();
        }
        post(this::invalidate);
    }
    
    public void clearWaveform() {
        amplitudes.clear();
        post(this::invalidate);
    }
}