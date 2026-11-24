package io.github.fantuani.padpaste;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelUuid;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@TargetApi(31)
@SuppressLint("MissingPermission") // Callers check Nearby devices permissions before creating a link.
final class BluetoothSyncLink implements Closeable {
    private static final UUID PSM = UUID.fromString("d79d0b38-622a-4fec-a9dc-ba8916a20c73");
    private final Context context;
    private final UUID service;
    private final CompletableFuture<BluetoothDevice> found = new CompletableFuture<>();
    private final CompletableFuture<Integer> channel = new CompletableFuture<>();
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothSocket socket;
    private boolean closed;

    static boolean allowed(Context context) {
        return Build.VERSION.SDK_INT >= 31
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean radioOn(Context context) {
        if (!allowed(context)) return false;
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        return adapter != null && adapter.isEnabled();
    }

    BluetoothSyncLink(Context context, byte[] identity) {
        this.context = context;
        ByteBuffer bytes = ByteBuffer.wrap(identity);
        this.service = new UUID(bytes.getLong(), bytes.getLong());
    }

    BluetoothSocket connect() throws Exception {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) throw new IOException("蓝牙未开启");
        synchronized (this) {
            checkOpen();
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) throw new IOException("蓝牙不可用");
            scanner.startScan(List.of(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(service)).build()),
                    new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), scan);
        }

        BluetoothDevice device;
        try {
            device = found.get(7, TimeUnit.SECONDS);
        } finally {
            stopScan();
        }

        synchronized (this) {
            checkOpen();
            gatt = device.connectGatt(context, false, callbacks, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) throw new IOException("无法连接蓝牙服务");
        }
        int psm = channel.get(9, TimeUnit.SECONDS);
        BluetoothSocket next;
        synchronized (this) {
            checkOpen();
            next = device.createL2capChannel(psm);
            socket = next;
        }
        next.connect();
        return next;
    }

    private final ScanCallback scan = new ScanCallback() {
        @Override public void onScanResult(int callback, ScanResult result) {
            found.complete(result.getDevice());
        }

        @Override public void onScanFailed(int error) {
            found.completeExceptionally(new IOException("蓝牙扫描失败：" + error));
        }
    };

    @SuppressWarnings("deprecation") // getValue is needed only by the pre-Android 13 callback.
    @SuppressLint("NewApi") // The new callback is dispatched only on Android 13 and later.
    private final BluetoothGattCallback callbacks = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt connection, int status, int state) {
            if (status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                if (!connection.discoverServices()) close();
            } else if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                close();
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt connection, int status) {
            var remote = status == BluetoothGatt.GATT_SUCCESS ? connection.getService(service) : null;
            var characteristic = remote == null ? null : remote.getCharacteristic(PSM);
            if (characteristic == null || !connection.readCharacteristic(characteristic)) close();
        }

        @Override public void onCharacteristicRead(BluetoothGatt connection, BluetoothGattCharacteristic characteristic,
                                                   int status) {
            if (Build.VERSION.SDK_INT < 33) readChannel(characteristic, characteristic.getValue(), status);
        }

        @Override public void onCharacteristicRead(BluetoothGatt connection, BluetoothGattCharacteristic characteristic,
                                                   byte[] value, int status) {
            readChannel(characteristic, value, status);
        }
    };

    private void readChannel(BluetoothGattCharacteristic characteristic, byte[] value, int status) {
        if (!PSM.equals(characteristic.getUuid()) || status != BluetoothGatt.GATT_SUCCESS
                || value == null || value.length != 2) {
            close();
            return;
        }
        int psm = Short.toUnsignedInt(ByteBuffer.wrap(value).getShort());
        if (psm == 0) close();
        else channel.complete(psm);
    }

    private void checkOpen() throws IOException {
        if (closed) throw new IOException("蓝牙已断开");
    }

    private synchronized void stopScan() {
        if (scanner == null) return;
        try { scanner.stopScan(scan); } catch (SecurityException ignored) {}
        scanner = null;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        stopScan();
        IOException failure = new IOException("蓝牙已断开");
        found.completeExceptionally(failure);
        channel.completeExceptionally(failure);
        if (socket != null) try { socket.close(); } catch (IOException ignored) {}
        if (gatt != null) try { gatt.close(); } catch (SecurityException ignored) {}
    }
}
