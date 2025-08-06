package com.example.phonematetry.asr;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class AudioRecorder {
    private static final String TAG = "AudioRecorder";
    
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    
    private static final int SILENCE_THRESHOLD_DB = 50; // 50 dB threshold
    private static final long SILENCE_DURATION_MS = 2000; // 2 seconds
    private static final int BUFFER_SIZE_FACTOR = 2;
    
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private Thread recordingThread;
    private List<Short> audioData;
    private AudioRecordingListener listener;
    
    private long lastSoundTime = 0;
    private boolean silenceDetected = false;
    
    public interface AudioRecordingListener {
        void onRecordingStarted();
        void onRecordingFinished(float[] audioSamples);
        void onRecordingError(String error);
        void onSilenceDetected();
        void onSoundDetected();
    }
    
    public AudioRecorder(AudioRecordingListener listener) {
        this.listener = listener;
        this.audioData = new ArrayList<>();
    }
    
    public boolean startRecording() {
        if (isRecording) {
            Log.w(TAG, "Already recording");
            return false;
        }
        
        try {
            int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            bufferSize = Math.max(bufferSize, SAMPLE_RATE * BUFFER_SIZE_FACTOR);
            
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            );
            
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed");
                if (listener != null) {
                    listener.onRecordingError("AudioRecord initialization failed");
                }
                return false;
            }
            
            audioData.clear();
            isRecording = true;
            silenceDetected = false;
            lastSoundTime = System.currentTimeMillis();
            
            audioRecord.startRecording();
            
            recordingThread = new Thread(this::recordingLoop);
            recordingThread.start();
            
            if (listener != null) {
                listener.onRecordingStarted();
            }
            
            Log.d(TAG, "Recording started");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting recording", e);
            if (listener != null) {
                listener.onRecordingError("Error starting recording: " + e.getMessage());
            }
            return false;
        }
    }
    
    public void stopRecording() {
        if (!isRecording) {
            Log.w(TAG, "Not currently recording");
            return;
        }
        
        isRecording = false;
        
        if (recordingThread != null) {
            try {
                recordingThread.join(1000); // Wait up to 1 second
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for recording thread to finish");
            }
        }
        
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping AudioRecord", e);
            }
            audioRecord = null;
        }
        
        // Convert recorded data to float array
        float[] samples = convertToFloatArray();
        
        if (listener != null) {
            listener.onRecordingFinished(samples);
        }
        
        Log.d(TAG, "Recording stopped, samples: " + samples.length);
    }
    
    private void recordingLoop() {
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        short[] buffer = new short[bufferSize];
        
        while (isRecording) {
            int bytesRead = audioRecord.read(buffer, 0, buffer.length);
            
            if (bytesRead > 0) {
                // Add data to our collection
                for (int i = 0; i < bytesRead; i++) {
                    audioData.add(buffer[i]);
                }
                
                // Calculate amplitude for silence detection
                double amplitude = calculateAmplitude(buffer, bytesRead);
                double amplitudeDb = 20 * Math.log10(amplitude / 32767.0); // Convert to dB
                
                long currentTime = System.currentTimeMillis();
                
                if (amplitudeDb > -SILENCE_THRESHOLD_DB) {
                    // Sound detected
                    lastSoundTime = currentTime;
                    if (silenceDetected) {
                        silenceDetected = false;
                        if (listener != null) {
                            listener.onSoundDetected();
                        }
                    }
                } else {
                    // Check for silence duration
                    if (currentTime - lastSoundTime > SILENCE_DURATION_MS) {
                        if (!silenceDetected) {
                            silenceDetected = true;
                            if (listener != null) {
                                listener.onSilenceDetected();
                            }
                            // 不再自动停止录音，只通知静音检测
                        }
                    }
                }
            } else if (bytesRead < 0) {
                Log.e(TAG, "Error reading audio data: " + bytesRead);
                break;
            }
        }
    }
    
    private double calculateAmplitude(short[] buffer, int length) {
        double sum = 0;
        for (int i = 0; i < length; i++) {
            sum += Math.abs(buffer[i]);
        }
        return sum / length;
    }
    
    private float[] convertToFloatArray() {
        float[] samples = new float[audioData.size()];
        for (int i = 0; i < audioData.size(); i++) {
            samples[i] = audioData.get(i) / 32768.0f; // Normalize to [-1, 1]
        }
        return samples;
    }
    
    public boolean isRecording() {
        return isRecording;
    }
    
    public int getSampleRate() {
        return SAMPLE_RATE;
    }
}