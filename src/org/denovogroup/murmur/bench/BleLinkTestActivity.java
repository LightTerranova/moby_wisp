package org.denovogroup.murmur.bench;

import android.app.Activity;
import android.bluetooth.BluetoothSocket;
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

import org.denovogroup.murmur.backend.MobyBleTransport;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

// New activity with a separate icon for Moby Wisp testing to happen in.
@RequiresApi(api = Build.VERSION_CODES.Q)
public class BleLinkTestActivity extends Activity {
    private final MobyTrafficGenerator.Profile profile = MobyTrafficGenerator.volumeMatched();

    private static final String TAG = "moby_wisp";

    private static final long SCAN_TIMEOUT_MS = 30000;
    private static final int ACK_BYTE = 0x06;

    private TextView log;
    private EditText irkField;
    private MobyBleTransport transport;
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

        root.addView(button("Listen (Server)", new View.OnClickListener() {
            @Override public void onClick(View v) { new Thread(serverRunnable).start(); }
        }));

        root.addView(button("Connect (Client)", new View.OnClickListener() {
            @Override public void onClick(View v) { new Thread(clientRunnable).start(); }
        }));

        log = new TextView(this);
        log.setTextColor(Color.DKGRAY);
        log.setTextIsSelectable(true);
        ScrollView scroller = new ScrollView(this);
        scroller.addView(log);
        root.addView(scroller);

        setContentView(root);
        transport = new MobyBleTransport(this);
    }

    // Server
    private final Runnable serverRunnable = new Runnable() {
        @Override
        public void run() {
            MobyBleTransport serverTransport = new MobyBleTransport(BleLinkTestActivity.this);
            BluetoothSocket socket = null;
            try {
                append("Opening L2CAP listener");
                socket = serverTransport.startServerAndAccept();
                append("Connected:" + socket.getRemoteDevice().getAddress());

                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();

                long expected = MobyTrafficGenerator.totalJsonBytes(MobyTrafficGenerator.buildFrames(BleLinkTestActivity.this, profile));

                long start = System.currentTimeMillis();
                long received = MobyTrafficGenerator.receive(in, profile.frameCount());
                long elapsed = System.currentTimeMillis() - start;

                out.write(ACK_BYTE);
                out.flush();
                try {in.read();} catch (Exception ignored) { }

                printThroughput("RECV", received, elapsed, expected);
            } catch (Exception e) {
                Log.e(TAG, "server failed", e);
                append("Server error: " + e);
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

                List<JSONObject> frames = MobyTrafficGenerator.buildFrames(BleLinkTestActivity.this, profile);
                long expected = MobyTrafficGenerator.totalJsonBytes(frames);

                long start = System.currentTimeMillis();
                long sent = MobyTrafficGenerator.send(out, frames);
                int ack = in.read();
                long elapsed = System.currentTimeMillis() - start;

                if (ack != ACK_BYTE) append("No ACK (got " + ack + ")");
                else printThroughput("SEND (round trip)", sent, elapsed, expected);
            } catch (Exception e) {
                Log.e(TAG, "client failed", e);
                append("Client error: " + e);
            } finally {
                closeQuietly(socket);
                clientTransport.close();
            }
        }
    };

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
    private void printThroughput(String direction, long bytes, long elapsedMs, long expectedBytes) {
        double mbps = (bytes * 8.0) / (elapsedMs / 1000.0) / 1_000_000.0;
        append("Throughput [" + direction + "]: " + mbps + " Mbps (" + bytes + " bytes in " + elapsedMs + " ms)" + (bytes == expectedBytes ? "" : "  [Something broke, needed " + expectedBytes + "]"));
    }

    private static void closeQuietly(BluetoothSocket socket) {
        if (socket != null) {
            try { socket.close(); } catch (Exception ignored) { }
        }
    }
}