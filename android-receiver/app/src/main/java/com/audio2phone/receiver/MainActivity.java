package com.audio2phone.receiver;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int DISCOVERY_PORT = 4041;
    private static final int BG = 0xfff4f0e8;
    private static final int INK = 0xff17211f;
    private static final int MUTED = 0xff5c6965;
    private static final int GREEN = 0xff1d6f68;
    private static final int BLUE = 0xff285c8f;
    private static final int PANEL = 0xffffffff;

    private final List<Computer> computers = new ArrayList<>();
    private final SecureRandom random = new SecureRandom();
    private AudioManager audioManager;
    private SharedPreferences preferences;
    private LinearLayout root;
    private TextView statusText;
    private TextView liveStatusText;
    private String pendingCode;
    private Computer pendingComputer;
    private boolean showingConnected;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String state = intent.getStringExtra(StreamingService.EXTRA_STATE);
            String message = intent.getStringExtra(StreamingService.EXTRA_MESSAGE);
            String token = intent.getStringExtra(StreamingService.EXTRA_SESSION_TOKEN);
            if (StreamingService.STATE_CONNECTED.equals(state)) {
                if (token != null && pendingComputer != null) {
                    saveLastComputer(pendingComputer, token);
                }
                if (showingConnected && liveStatusText != null) {
                    liveStatusText.setText(message == null ? "Connected" : message);
                } else {
                    showConnected(message == null ? "Connected" : message);
                }
            } else if (StreamingService.STATE_RECONNECTING.equals(state)) {
                if (liveStatusText != null) {
                    liveStatusText.setText(message == null ? "Reconnecting..." : message);
                }
            } else if (StreamingService.STATE_ERROR.equals(state)) {
                showDiscovery();
                statusText.setText("Connection failed: " + message);
            } else if (StreamingService.STATE_STOPPED.equals(state) && pendingCode == null) {
                showDiscovery();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        preferences = getSharedPreferences("audio2phone", MODE_PRIVATE);
        requestNotificationPermission();
        showDiscovery();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(StreamingService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        unregisterReceiver(statusReceiver);
        super.onPause();
    }

    private void showDiscovery() {
        pendingCode = null;
        pendingComputer = null;
        showingConnected = false;
        root = baseRoot();
        root.addView(header("Audio 2 Phone", "Find your PC on this Wi-Fi network."));

        Computer last = getLastComputer();
        if (last != null) {
            LinearLayout lastPanel = panel();
            lastPanel.setPadding(dp(18), dp(16), dp(18), dp(16));
            lastPanel.addView(smallCaps("LAST PAIRED COMPUTER"), fullWidth(-2, 0, 0));
            TextView name = new TextView(this);
            name.setText(last.name);
            name.setTextColor(INK);
            name.setTextSize(20);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setGravity(Gravity.CENTER);
            lastPanel.addView(name, fullWidth(-2, dp(8), 0));
            Button reconnect = primaryButton("Reconnect", BLUE);
            reconnect.setOnClickListener(v -> reconnect(last));
            lastPanel.addView(reconnect, fullWidth(dp(48), dp(14), 0));
            root.addView(lastPanel, fullWidth(-2, dp(22), 0));
        }

        Button findButton = primaryButton("Find computers", GREEN);
        findButton.setOnClickListener(v -> discoverComputers(findButton));
        root.addView(findButton, fullWidth(dp(52), dp(22), 0));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setTag("list");
        root.addView(list, weighted(dp(18)));

        statusText = bodyText("Windows tray app must be running and allowed through Firewall.");
        statusText.setGravity(Gravity.CENTER);
        root.addView(statusText, fullWidth(-2, dp(10), 0));
        setContentView(root);
        renderComputers();
    }

    private void discoverComputers(Button findButton) {
        findButton.setEnabled(false);
        statusText.setText("Searching local network...");
        computers.clear();
        renderComputers();

        new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);
                socket.setSoTimeout(700);

                byte[] request = "A2P_DISCOVER".getBytes(StandardCharsets.US_ASCII);
                DatagramPacket packet = new DatagramPacket(request, request.length, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT);
                socket.send(packet);

                long deadline = System.currentTimeMillis() + 2500;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        byte[] buffer = new byte[256];
                        DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                        socket.receive(response);
                        String message = new String(response.getData(), 0, response.getLength(), StandardCharsets.UTF_8);
                        Computer computer = parseComputer(message, response.getAddress().getHostAddress());
                        if (computer != null) {
                            runOnUiThread(() -> addComputer(computer));
                        }
                    } catch (java.net.SocketTimeoutException ignored) {
                    }
                }
            } catch (Exception ex) {
                runOnUiThread(() -> statusText.setText("Discovery failed: " + ex.getMessage()));
            } finally {
                runOnUiThread(() -> {
                    findButton.setEnabled(true);
                    if (computers.isEmpty()) {
                        statusText.setText("No computers found. Check Windows Firewall and Wi-Fi.");
                    }
                });
            }
        }, "audio2phone-discovery").start();
    }

    private void showPairing(Computer computer, String code) {
        pendingComputer = computer;
        pendingCode = code;
        showingConnected = false;
        root = baseRoot();
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(header("Confirm Pairing", computer.name));

        LinearLayout codePanel = panel();
        codePanel.setGravity(Gravity.CENTER);
        codePanel.setPadding(dp(18), dp(28), dp(18), dp(28));
        TextView label = smallCaps("PAIRING CODE");
        TextView codeView = new TextView(this);
        codeView.setText(code);
        codeView.setTextColor(INK);
        codeView.setTextSize(56);
        codeView.setTypeface(Typeface.DEFAULT_BOLD);
        codeView.setGravity(Gravity.CENTER);
        codePanel.addView(label, fullWidth(-2, 0, 0));
        codePanel.addView(codeView, fullWidth(-2, dp(8), 0));
        root.addView(codePanel, fullWidth(-2, dp(32), 0));

        statusText = bodyText("Approve this same code on the Windows popup.");
        statusText.setGravity(Gravity.CENTER);
        root.addView(statusText, fullWidth(-2, dp(22), 0));

        Button cancel = secondaryButton("Cancel pairing");
        cancel.setOnClickListener(v -> {
            stopStreaming();
            showDiscovery();
        });
        root.addView(cancel, fullWidth(dp(50), dp(28), 0));
        setContentView(root);
    }

    private void showConnected(String message) {
        pendingCode = null;
        showingConnected = true;
        root = baseRoot();
        root.addView(header("Connected", pendingComputer == null ? "Windows audio is streaming." : pendingComputer.name));

        LinearLayout meter = panel();
        meter.setPadding(dp(20), dp(22), dp(20), dp(22));
        meter.addView(smallCaps("PHONE VOLUME"), fullWidth(-2, 0, 0));

        SeekBar volume = new SeekBar(this);
        volume.setMax(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        volume.setProgress(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        meter.addView(volume, fullWidth(-2, dp(16), 0));

        liveStatusText = bodyText(message == null ? "Streaming" : message);
        liveStatusText.setGravity(Gravity.CENTER);
        meter.addView(liveStatusText, fullWidth(-2, dp(16), 0));
        root.addView(meter, fullWidth(-2, dp(28), 0));

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));

        Button disconnect = primaryButton("Disconnect", 0xff9b3f38);
        disconnect.setOnClickListener(v -> {
            stopStreaming();
            showDiscovery();
        });
        root.addView(disconnect, fullWidth(dp(52), dp(18), dp(48)));
        setContentView(root);
    }

    private void startStreaming(Computer computer) {
        String code = String.format(java.util.Locale.US, "%06d", random.nextInt(1_000_000));
        showPairing(computer, code);

        Intent intent = new Intent(this, StreamingService.class)
                .setAction(StreamingService.ACTION_START)
                .putExtra(StreamingService.EXTRA_HOST, computer.host)
                .putExtra(StreamingService.EXTRA_PORT, computer.port)
                .putExtra(StreamingService.EXTRA_CODE, code)
                .putExtra(StreamingService.EXTRA_NAME, computer.name);

        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void reconnect(Computer computer) {
        pendingComputer = computer;
        showingConnected = false;
        showConnected("Reconnecting...");

        Intent intent = new Intent(this, StreamingService.class)
                .setAction(StreamingService.ACTION_START)
                .putExtra(StreamingService.EXTRA_HOST, computer.host)
                .putExtra(StreamingService.EXTRA_PORT, computer.port)
                .putExtra(StreamingService.EXTRA_TOKEN, computer.token)
                .putExtra(StreamingService.EXTRA_NAME, computer.name);

        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void stopStreaming() {
        Intent intent = new Intent(this, StreamingService.class).setAction(StreamingService.ACTION_STOP);
        startService(intent);
        pendingCode = null;
        showingConnected = false;
    }

    private void addComputer(Computer computer) {
        for (Computer existing : computers) {
            if (existing.host.equals(computer.host) && existing.port == computer.port) {
                return;
            }
        }
        computers.add(computer);
        renderComputers();
        statusText.setText("Select a computer to pair.");
    }

    private void renderComputers() {
        if (root == null) return;
        LinearLayout list = root.findViewWithTag("list");
        if (list == null) return;
        list.removeAllViews();

        for (Computer computer : computers) {
            LinearLayout card = panel();
            card.setPadding(dp(18), dp(14), dp(18), dp(14));
            TextView name = new TextView(this);
            name.setText(computer.name);
            name.setTextColor(INK);
            name.setTextSize(18);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            TextView address = bodyText(computer.host + ":" + computer.port);
            card.addView(name);
            card.addView(address, fullWidth(-2, dp(4), 0));
            card.setOnClickListener(v -> startStreaming(computer));
            list.addView(card, fullWidth(-2, 0, dp(10)));
        }
    }

    private Computer parseComputer(String message, String host) {
        String[] parts = message.split("\\|");
        if (parts.length != 3 || !"A2P_HERE".equals(parts[0])) {
            return null;
        }
        try {
            return new Computer(parts[1], host, Integer.parseInt(parts[2]), null);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void saveLastComputer(Computer computer, String token) {
        preferences.edit()
                .putString("last_name", computer.name)
                .putString("last_host", computer.host)
                .putInt("last_port", computer.port)
                .putString("last_token", token)
                .apply();
    }

    private Computer getLastComputer() {
        String host = preferences.getString("last_host", null);
        String token = preferences.getString("last_token", null);
        if (host == null || token == null) {
            return null;
        }
        return new Computer(
                preferences.getString("last_name", host),
                host,
                preferences.getInt("last_port", 4040),
                token);
    }

    private LinearLayout baseRoot() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(22), dp(34), dp(22), dp(22));
        view.setBackgroundColor(BG);
        return view;
    }

    private LinearLayout header(String title, String subtitle) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(INK);
        titleView.setTextSize(30);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        TextView subtitleView = bodyText(subtitle);
        header.addView(titleView);
        header.addView(subtitleView, fullWidth(-2, dp(6), 0));
        return header;
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PANEL);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0x1f17211f);
        panel.setBackground(bg);
        return panel;
    }

    private TextView bodyText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(MUTED);
        view.setTextSize(15);
        return view;
    }

    private TextView smallCaps(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(BLUE);
        view.setTextSize(13);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private Button primaryButton(String text, int color) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16);
        button.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(8));
        button.setBackground(bg);
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(INK);
        button.setTextSize(16);
        button.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x00ffffff);
        bg.setStroke(dp(1), 0x6617211f);
        bg.setCornerRadius(dp(8));
        button.setBackground(bg);
        return button;
    }

    private LinearLayout.LayoutParams fullWidth(int height, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, height);
        params.setMargins(0, top, 0, bottom);
        return params;
    }

    private LinearLayout.LayoutParams weighted(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, 0, 1);
        params.setMargins(0, top, 0, 0);
        return params;
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 10);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class Computer {
        final String name;
        final String host;
        final int port;
        final String token;

        Computer(String name, String host, int port, String token) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.token = token;
        }
    }
}
