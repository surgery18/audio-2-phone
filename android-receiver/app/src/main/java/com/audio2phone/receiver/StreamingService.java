package com.audio2phone.receiver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class StreamingService extends Service {
    public static final String ACTION_START = "com.audio2phone.receiver.START";
    public static final String ACTION_STOP = "com.audio2phone.receiver.STOP";
    public static final String ACTION_STATUS = "com.audio2phone.receiver.STATUS";
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_TOKEN = "token";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_SESSION_TOKEN = "session_token";
    public static final String STATE_PAIRING = "pairing";
    public static final String STATE_CONNECTED = "connected";
    public static final String STATE_RECONNECTING = "reconnecting";
    public static final String STATE_STOPPED = "stopped";
    public static final String STATE_ERROR = "error";

    private static final String CHANNEL_ID = "audio2phone_stream";
    private volatile boolean running;
    private Thread streamThread;
    private Socket socket;
    private AudioTrack audioTrack;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_STOP : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopStream(true);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        String host = intent.getStringExtra(EXTRA_HOST);
        int port = intent.getIntExtra(EXTRA_PORT, 4040);
        String code = intent.getStringExtra(EXTRA_CODE);
        String token = intent.getStringExtra(EXTRA_TOKEN);
        String name = intent.getStringExtra(EXTRA_NAME);
        if (host == null || (code == null && token == null)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(1, buildNotification("Connecting to " + host));
        sendStatus(token == null ? STATE_PAIRING : STATE_RECONNECTING, token == null ? "Waiting for Windows approval" : "Reconnecting to " + host, null);
        startStream(host, port, code, token, name);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopStream(true);
        super.onDestroy();
    }

    private void startStream(String host, int port, String code, String token, String name) {
        stopStream(false);
        running = true;
        acquireWakeLock();
        streamThread = new Thread(() -> runStream(host, port, code, token, name), "audio2phone-stream");
        streamThread.start();
    }

    private void runStream(String host, int port, String code, String token, String name) {
        String sessionToken = token;
        int attempt = 0;
        while (running) {
            try {
                attempt++;
                if (attempt > 1) {
                    updateNotification("Reconnecting to " + host);
                    sendStatus(STATE_RECONNECTING, "Reconnect attempt " + attempt, sessionToken);
                    Thread.sleep(Math.min(10_000, 1_500L * attempt));
                }

                sessionToken = connectAndStream(host, port, code, sessionToken);
                attempt = 0;
            } catch (Exception ex) {
                closeSocket();
                releaseAudio();
                if (!running) {
                    break;
                }
                if (sessionToken == null || ex.getMessage().contains("Untrusted") || ex.getMessage().contains("Rejected")) {
                    updateNotification("Stopped: " + ex.getMessage());
                    sendStatus(STATE_ERROR, ex.getMessage(), sessionToken);
                    break;
                }
            }
        }

        stopStream(true);
    }

    private String connectAndStream(String host, int port, String code, String token) throws Exception {
        socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, port), 5000);

        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
        writer.write(token == null ? "PAIR " + code : "RESUME " + token);
        writer.newLine();
        writer.flush();

        InputStream input = socket.getInputStream();
        String response = readAsciiLine(input);
        if (!response.startsWith("OK ")) {
            throw new IOException(response == null ? "Pairing rejected" : response);
        }
        String sessionToken = response.substring(3).trim();
        sendStatus(STATE_CONNECTED, "Paired", sessionToken);

        byte[] header = readFully(input, 20);
        if (header[0] != 'A' || header[1] != '2' || header[2] != 'P' || header[3] != '1') {
            throw new IOException("Invalid stream header");
        }

        int sampleRate = littleInt(header, 4);
        int channels = littleShort(header, 8);
        int bits = littleShort(header, 10);
        if (bits != 16 || (channels != 1 && channels != 2)) {
            throw new IOException("Unsupported audio format");
        }

        int channelMask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(minBuffer, sampleRate * channels * 2 / 5);
        int latencyMs = (int) Math.round(bufferSize * 1000.0 / (sampleRate * channels * 2));

        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

        audioTrack.play();
        updateNotification("Streaming " + sampleRate + " Hz");
        sendStatus(STATE_CONNECTED, "Streaming " + sampleRate + " Hz | buffer ~" + latencyMs + " ms", sessionToken);

        byte[] buffer = new byte[4096];
        long bytesThisSecond = 0;
        long lastStats = System.currentTimeMillis();
        while (running) {
            int read = input.read(buffer);
            if (read < 0) {
                throw new IOException("Streamer disconnected");
            }
            audioTrack.write(buffer, 0, read);
            bytesThisSecond += read;
            long now = System.currentTimeMillis();
            if (now - lastStats >= 1000) {
                long kbps = Math.round(bytesThisSecond * 8.0 / 1000.0);
                sendStatus(STATE_CONNECTED, kbps + " kbps | buffer ~" + latencyMs + " ms", sessionToken);
                bytesThisSecond = 0;
                lastStats = now;
            }
        }

        return sessionToken;
    }

    private void stopStream(boolean announce) {
        running = false;
        closeSocket();
        releaseAudio();
        releaseWakeLock();
        if (announce) {
            sendStatus(STATE_STOPPED, "Stopped", null);
        }
    }

    private byte[] readFully(InputStream input, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(data, offset, length - offset);
            if (read < 0) {
                throw new IOException("Connection closed");
            }
            offset += read;
        }
        return data;
    }

    private String readAsciiLine(InputStream input) throws IOException {
        StringBuilder builder = new StringBuilder();
        while (true) {
            int value = input.read();
            if (value < 0) {
                throw new IOException("Connection closed");
            }
            if (value == '\n') {
                return builder.toString().trim();
            }
            builder.append((char) value);
        }
    }

    private void closeSocket() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        socket = null;
    }

    private void releaseAudio() {
        if (audioTrack != null) {
            audioTrack.pause();
            audioTrack.flush();
            audioTrack.release();
            audioTrack = null;
        }
    }

    private void acquireWakeLock() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Audio2Phone:Stream");
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Audio streaming", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, StreamingService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Audio 2 Phone")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(openPendingIntent)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(1, buildNotification(text));
    }

    private void sendStatus(String state, String message, String sessionToken) {
        Intent intent = new Intent(ACTION_STATUS)
                .setPackage(getPackageName())
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_MESSAGE, message);
        if (sessionToken != null) {
            intent.putExtra(EXTRA_SESSION_TOKEN, sessionToken);
        }
        sendBroadcast(intent);
    }

    private int littleInt(byte[] data, int offset) {
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
    }

    private int littleShort(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }
}
