package org.denovogroup.murmur.backend;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.ParcelUuid;
import android.support.annotation.RequiresApi;
import android.util.Log;

import org.denovogroup.murmur.bench.IrkStore;
import org.denovogroup.murmur.bench.ResolvableServiceUuid;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

// BLE L2CAP CoC Transport for Moby
@RequiresApi(api = Build.VERSION_CODES.Q)
public class MobyBleTransport {
    private static final String TAG = "MobyBleTransport";
    private static final int  CONNECT_MAX_ATTEMPTS = 3;
    private static final long CONNECT_RETRY_BACKOFF_MS = 500;
    private static final int  ACCEPT_TIMEOUT_MS = 90000;

    // found moby server
    public static final class DiscoveredServer {
        public final BluetoothDevice device;
        public final int psm;
        public DiscoveredServer(BluetoothDevice device, int psm) {
            this.device = device;
            this.psm = psm;
        }
    }

    private final BluetoothAdapter adapter;
    private final Context context;
    private BluetoothServerSocket serverSocket;
    private AdvertiseCallback advertiseCallback;
    private ScanCallback scanCallback;
    private ParcelUuid currentUuid;
    private long uuidGeneratedAt;

    public MobyBleTransport(Context context) {
        this.context = context.getApplicationContext();
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = manager.getAdapter();
    }

    // Opens L2CAP listening channel, adv PSM and waits
    @SuppressLint("MissingPermission")
    public BluetoothSocket startServerAndAccept() throws Exception {
        serverSocket = adapter.listenUsingInsecureL2capChannel();
        int psm = serverSocket.getPsm();
        Log.i(TAG, "L2CAP listening on PSM " + psm);

        startAdvertising(psm);
        Log.i(TAG, "Waiting for client");
        BluetoothSocket socket = serverSocket.accept(ACCEPT_TIMEOUT_MS);
        Log.i(TAG, "Incoming L2CAP connection: " + socket.getRemoteDevice().getAddress());

        stopAdvertising();
        return socket;
    }

    // Scan for Moby UUIDs, open channel to the first one found
    @SuppressLint("MissingPermission")
    public BluetoothSocket scanAndConnect(long timeoutMillis) throws Exception {
        IOException lastError = null;

        for (int attempt = 1; attempt <= CONNECT_MAX_ATTEMPTS; attempt++) {
            DiscoveredServer server = scanForServer(timeoutMillis);

            Log.i(TAG, "Connecting to " + server.device.getAddress() + " PSM " + server.psm + "(attempt " + attempt + "/" + CONNECT_MAX_ATTEMPTS + ")");

            BluetoothSocket socket = null;
            try {
                socket = server.device.createInsecureL2capChannel(server.psm);
                socket.connect();
                Log.i(TAG, "L2CAP socket connected to " + server.device.getAddress());
                return socket;
            } catch (IOException e) {
                lastError = e;
                Log.w(TAG, "Connect attempt " + attempt + " failed: " + e.getMessage());
                if (socket != null) {
                    try { socket.close(); } catch (IOException ignored) { }
                }
                if (attempt < CONNECT_MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(CONNECT_RETRY_BACKOFF_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw ie;
                    }
                }
            }
        }

        throw (lastError != null) ? lastError : new IOException("Failed to connect after " + CONNECT_MAX_ATTEMPTS + " attempts");
    }

    // unfiltered scan returns first uuid resolved
    @SuppressLint("MissingPermission")
    private DiscoveredServer scanForServer(long timeoutMillis) throws Exception {
        final byte[] irk = IrkStore.get(context);
        if (irk == null) {
            throw new IOException("No IRK");
        }

        final AtomicReference<BluetoothDevice> foundDevice = new AtomicReference<>(null);
        final AtomicReference<Integer> foundPsm = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);

        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();

        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                if (result == null) return;
                ScanRecord record = result.getScanRecord();
                if (record == null || record.getServiceData() == null) return;

                for (Map.Entry<ParcelUuid, byte[]> entry : record.getServiceData().entrySet()) {
                    UUID advertised = entry.getKey().getUuid();
                    if (!ResolvableServiceUuid.resolves(advertised, irk)) continue;

                    byte[] serviceData = entry.getValue();
                    if (serviceData == null || serviceData.length < 2) continue;

                    int psm = ByteBuffer.wrap(serviceData).getShort() & 0xFFFF;
                    if (foundDevice.compareAndSet(null, result.getDevice())) {
                        foundPsm.set(psm);
                        Log.i(TAG, "Resolved Moby peer " + result.getDevice().getAddress() + " PSM " + psm + " (RSSI " + result.getRssi() + ")");
                        latch.countDown();
                    }
                    return;
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                Log.e(TAG, "Scan failed");
                latch.countDown();
            }
        };

        adapter.getBluetoothLeScanner().startScan(null, settings, scanCallback);
        Log.i(TAG, "Scanning for Moby clients");

        boolean signalled = latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        stopScanning();

        BluetoothDevice device = foundDevice.get();
        Integer psm = foundPsm.get();
        if (!signalled || device == null || psm == null) {
            throw new IOException("No Moby server found");
        }
        return new DiscoveredServer(device, psm);
    }

    @SuppressLint("MissingPermission")
    private void startAdvertising(final int psm) throws Exception {
        byte[] irk = IrkStore.get(context);
        if (irk == null) {
            throw new IOException("No IRK");
        }

        currentUuid = ResolvableServiceUuid.generate(irk);
        uuidGeneratedAt = System.currentTimeMillis();

        byte[] psmBytes = ByteBuffer.allocate(2).putShort((short) psm).array();

        AdvertiseSettings settings = new AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).build();

        AdvertiseData data = new AdvertiseData.Builder().setIncludeDeviceName(false).addServiceData(currentUuid, psmBytes).build();

        advertiseCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                Log.i(TAG, "Advertising PSM " + psm + " as " + currentUuid);
            }
            @Override
            public void onStartFailure(int errorCode) {
                Log.e(TAG, "Advertise failed:" + errorCode);
            }
        };

        adapter.getBluetoothLeAdvertiser().startAdvertising(settings, data, advertiseCallback);
    }

    // rotating uuid after timeout (15 min)
    @SuppressLint("MissingPermission")
    public void rotateUuidIfDue() throws Exception {
        if (serverSocket == null || advertiseCallback == null) return;
        if (System.currentTimeMillis() - uuidGeneratedAt < ResolvableServiceUuid.ROTATION_PERIOD_MS) return;

        Log.i(TAG, "Rotating UUID");
        int psm = serverSocket.getPsm();
        stopAdvertising();
        startAdvertising(psm);
    }

    @SuppressLint("MissingPermission")
    private void stopAdvertising() {
        if (advertiseCallback != null && adapter.getBluetoothLeAdvertiser() != null) {
            adapter.getBluetoothLeAdvertiser().stopAdvertising(advertiseCallback);
            advertiseCallback = null;
        }
    }

    @SuppressLint("MissingPermission")
    private void stopScanning() {
        if (scanCallback != null && adapter.getBluetoothLeScanner() != null) {
            adapter.getBluetoothLeScanner().stopScan(scanCallback);
            scanCallback = null;
        }
    }

    @SuppressLint("MissingPermission")
    public void close() {
        stopAdvertising();
        stopScanning();
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException e) { Log.w(TAG, e); }
            serverSocket = null;
        }
    }

    // check if device can do CoC
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }
}