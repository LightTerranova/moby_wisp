package org.denovogroup.murmur.bench;

import static org.denovogroup.murmur.bench.MobyTrafficGenerator.randomB64;
import org.denovogroup.murmur.backend.SecurityManager;

import android.app.Activity;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.RequiresApi;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.denovogroup.murmur.backend.AppConstants;
import org.denovogroup.murmur.backend.MessageStore;
import org.denovogroup.murmur.backend.MobyBleTransport;
import org.denovogroup.murmur.backend.MurmurService;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

// New activity with a separate icon for Moby Wisp testing to happen in.
@RequiresApi(api = Build.VERSION_CODES.Q)
public class BleLinkTestActivity extends Activity {
    private final MobyTrafficGenerator.Profile profile = MobyTrafficGenerator.volumeMatched();
    private static final String TAG = "moby_wisp";
    private static final long SCAN_TIMEOUT_MS = 30000;
    private static final int ACK_BYTE = 0x06;
    private TextView log;
    private EditText irkField;
    private EditText macField;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override 
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        irkField = new EditText(this);
        irkField.setHint("IRK");
        irkField.setInputType(InputType.TYPE_CLASS_TEXT);
        String existing = IrkStore.getHex(this);
        if (existing != null) irkField.setText(existing);
        root.addView(irkField);

        root.addView(button("Generate IRK", new View.OnClickListener() {
            @Override public void onClick(View v) {
                String hex = IrkStore.generateAndStore(BleLinkTestActivity.this);
                irkField.setText(hex);
                append("IRK: " + hex);
            }
        }));

        root.addView(button("Save IRK", new View.OnClickListener() {
            @Override public void onClick(View v) {
                IrkStore.setHex(BleLinkTestActivity.this, irkField.getText().toString());
                append("IRK saved");
            }
        }));

        root.addView(button("Toggle public mode", new View.OnClickListener() {
            @Override public void onClick(View v) {
                MobyBleTransport.encryptionEnabled = !MobyBleTransport.encryptionEnabled;
                append(MobyBleTransport.encryptionEnabled ? "Rotating UUID and encrypted PSM" : "Static UUID and plaintext PSM");
            }
        }));

        root.addView(button("Listen (Server)", new View.OnClickListener() {
            @Override public void onClick(View v) { new Thread(serverRunnable).start(); }
        }));

        root.addView(button("Connect (Client)", new View.OnClickListener() {
            @Override public void onClick(View v) { new Thread(clientRunnable).start(); }
        }));

        // adding pre filling mac
        macField = new EditText(this);
        macField.setHint("Server MAC for RFCOMM");
        macField.setInputType(InputType.TYPE_CLASS_TEXT);
        String storedMac = SecurityManager.getStoredMAC(this);
        if (storedMac != null && !storedMac.isEmpty()) macField.setText(storedMac);
        root.addView(macField);

        root.addView(button("Save own MAC for Moby proper", new View.OnClickListener() {
            @Override public void onClick(View v) {
                String mac = macField.getText().toString().trim().toUpperCase();
                SecurityManager.setStoredMAC(BleLinkTestActivity.this, mac);
                append("Stored MAC:" + SecurityManager.getStoredMAC(BleLinkTestActivity.this));
            }
        }));

        root.addView(button("Testing Moby Client", new View.OnClickListener() {
            @Override public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override public void run() {
                        setMurmurEnabled(false);
                        stopService(new Intent(BleLinkTestActivity.this, MurmurService.class));
                        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}

                        MessageStore store = MessageStore.getInstance(BleLinkTestActivity.this);
                        store.purgeStore();
                        append("Store purged, size now " + store.getMessageStoreSize());

                        java.util.Random rng = new java.util.Random();
                        long now = System.currentTimeMillis();
                        store.purgeStore();
                        int ok = 0;
                        for (int i = 0; i < 1200; i++) { // change this between 1200 on client and 0 on server for throughput results
                            if (store.addMessage(now + i, now + i + 604_800_000L, randomB64(rng, 44), randomB64(rng, 400))) {
                                ok++;
                            }
                        }
                        append("Seeded " + ok + " messages");

                        setMurmurEnabled(true);
                        startService(new Intent(BleLinkTestActivity.this, MurmurService.class));
                        append("MurmurService started");
                    }
                }).start();
            }
        }));

        root.addView(button("Testing Moby Server", new View.OnClickListener() {
            @Override public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override public void run() {
                        setMurmurEnabled(false);
                        stopService(new Intent(BleLinkTestActivity.this, MurmurService.class));
                        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}

                        MessageStore store = MessageStore.getInstance(BleLinkTestActivity.this);
                        store.purgeStore();
                        append("Store purged, size now " + store.getMessageStoreSize());

                        java.util.Random rng = new java.util.Random();
                        long now = System.currentTimeMillis();
                        store.purgeStore();
                        int ok = 0;
                        for (int i = 0; i < 0; i++) { // change this between 1200 on client and 0 on server for throughput results
                            if (store.addMessage(now + i, now + i + 604_800_000L, randomB64(rng, 44), randomB64(rng, 400))) {
                                ok++;
                            }
                        }
                        append("Seeded " + ok + " messages");

                        setMurmurEnabled(true);
                        startService(new Intent(BleLinkTestActivity.this, MurmurService.class));
                        append("MurmurService started");
                    }
                }).start();
            }
        }));

        root.addView(button("Murmur Off", new View.OnClickListener() {
            @Override public void onClick(View v) { stopMurmur(); }
        }));

        log = new TextView(this);
        log.setTextColor(Color.DKGRAY);
        log.setTextIsSelectable(true);
        ScrollView scroller = new ScrollView(this);
        scroller.addView(log);
        root.addView(scroller);

        setContentView(root);

        append(profile.toString());
    }

    // Server
    private final Runnable serverRunnable = new Runnable() {
        @Override
        public void run() {
            MobyBleTransport serverTransport = new MobyBleTransport(BleLinkTestActivity.this);
            BluetoothSocket socket = null;
            try {
                final long goodputBytes = profile.payloadWireBytes(); // payload

                append("Opening L2CAP listener");
                socket = serverTransport.startServerAndAccept();
                append("Connected:" + socket.getRemoteDevice().getAddress());

                // need to know when it starts and ends
                final long[] firstAt = {-1};
                final long[] lastAt  = {-1};
                final long[] wire    = {0};

                InputStream in = new FilterInputStream(socket.getInputStream()) {
                    private void stamp(int n) {
                        long now = System.currentTimeMillis();
                        if (firstAt[0] < 0) firstAt[0] = now;
                        lastAt[0] = now;
                        wire[0] += n;
                    }
                    @Override public int read() throws IOException {
                        int b = in.read();
                        if (b != -1) stamp(1);
                        return b;
                    }
                    @Override public int read(byte[] b, int off, int len) throws IOException {
                        int n = in.read(b, off, len);
                        if (n > 0) stamp(n);
                        return n;
                    }
                    @Override public int read(byte[] b) throws IOException {
                        return read(b, 0, b.length);
                    }
                };
                OutputStream out = socket.getOutputStream();

                long json = MobyTrafficGenerator.receive(in, profile.frameCount());
                long elapsed = (firstAt[0] < 0) ? 0 : lastAt[0] - firstAt[0];

                out.write(ACK_BYTE);
                out.flush();
                try {in.read();} catch (Exception ignored) { }

                append("wire " + wire[0] + " B, json " + json + " B, payload " + goodputBytes + " B");
                printThroughput("receive", goodputBytes, elapsed);
            } catch (Throwable t) {
                Log.e(TAG, "server failed", t);
                append("Server error" + t);
            } finally {
                closeQuietly(socket);
                serverTransport.close();
            }
        }
    };

    // Client
    private final Runnable clientRunnable = new Runnable() {

        @Override
        public void run() {
            MobyBleTransport clientTransport = new MobyBleTransport(BleLinkTestActivity.this);
            BluetoothSocket socket = null;
            try {
                append("Scanning");
                long setupStart = System.currentTimeMillis();
                socket = clientTransport.scanAndConnect(SCAN_TIMEOUT_MS);
                append("Connected in " + (System.currentTimeMillis() - setupStart) + " ms (scan + resolve + connect)");

                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                long start = System.currentTimeMillis();
                long sent = MobyTrafficGenerator.send(BleLinkTestActivity.this, out, profile);
                int ack = in.read();
                long elapsed = System.currentTimeMillis() - start;

                if (ack != ACK_BYTE) append("No ACK (got " + ack + ")");
                else printThroughput("SEND (round trip)", sent, elapsed);
            } catch (Throwable t) {
                Log.e(TAG, "client failed", t);
                append("Client error: " + t);
            } finally {
                closeQuietly(socket);
                clientTransport.close();
            }
        }
    };

    private void stopMurmur() {
        setMurmurEnabled(false);
        stopService(new Intent(this, MurmurService.class));
        append("MurmurService stopped");
    }

    private void setMurmurEnabled(boolean enabled) {
        getSharedPreferences(AppConstants.PREF_FILE, MODE_PRIVATE).edit().putBoolean(AppConstants.IS_APP_ENABLED, enabled).commit();
    }

    private Button button(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(listener);
        return b;
    }

    private void append(final String line) {
        Log.i(TAG, line);
        ui.post(new Runnable() {
            @Override public void run() { log.append(line + "\n"); }
        });
    }

    // Getting throughput in Megabits from Anix code
    private void printThroughput(String direction, long bytes, long elapsedMs) {
        if (elapsedMs <= 0) {
            append("Throughput [" + direction + "]: no elapsed time measured");
            return;
        }
        if (Objects.equals(direction, "receive")) {
            double kBps = (bytes / 1000.0) / (elapsedMs / 1000.0);
            append("Receiver Goodput: " + kBps + " kBps " + "(" + bytes + " bytes in " + elapsedMs + " ms" + ")");}
    }

    private static void closeQuietly(BluetoothSocket socket) {
        if (socket != null) {
            try { socket.close(); } catch (Exception ignored) { }
        }
    }
}