package pt.ipacediag;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import pt.ipacediag.core.ObdTransport;
import pt.ipacediag.core.Recorder;

/**
 * Ligação por Bluetooth Low Energy (BLE/GATT) a adaptadores ELM327 BLE,
 * como o OBDLink CX, o Veepeak BLE ou o vLinker na variante BLE 4.0.
 *
 * Os adaptadores BLE não usam todos as mesmas características GATT, por isso a
 * app deteta-as: procura uma característica que escreva e outra que notifique
 * (ou uma só que faça as duas, como o FFE1). Pede um MTU grande para as
 * respostas longas da bateria (multi-trama) e junta tudo até ao prompt '>'.
 */
public final class BleTransport implements ObdTransport {

    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");
    private static final UUID[] HINTS = {
            UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),   // muitos clones (FFF1/FFF2)
            UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),   // Veepeak, vLinker (FFE1)
            UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")    // Nordic UART
    };

    private final BluetoothGatt gatt;
    private volatile BluetoothGattCharacteristic writeChar, notifyChar;
    private final LinkedBlockingQueue<Byte> rx = new LinkedBlockingQueue<>();
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile boolean open = true;
    private volatile String error;
    private volatile int mtuPayload = 20;

    @SuppressLint("MissingPermission")
    public BleTransport(Context ctx, BluetoothDevice dev) throws IOException {
        gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE);
        if (gatt == null) throw new IOException("Não foi possível iniciar a ligação BLE.");
        try {
            if (!ready.await(15, TimeUnit.SECONDS)) { close(); throw new IOException(error != null ? error : "Tempo esgotado a ligar por BLE."); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            throw new IOException("Ligação BLE interrompida.");
        }
        if (error != null) { close(); throw new IOException(error); }
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!g.requestMtu(517)) g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                open = false;
                if (ready.getCount() > 0) { error = "O adaptador BLE desligou-se durante a ligação."; ready.countDown(); }
            }
        }

        @SuppressLint("MissingPermission")
        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            mtuPayload = Math.max(20, mtu - 3);
            g.discoverServices();
        }

        @SuppressLint("MissingPermission")
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) { error = "Falha a descobrir os serviços BLE."; ready.countDown(); return; }
            pick(g);
            if (writeChar == null || notifyChar == null) {
                error = "Este adaptador BLE não expõe as características esperadas (escrever/notificar).";
                ready.countDown();
                return;
            }
            g.setCharacteristicNotification(notifyChar, true);
            BluetoothGattDescriptor d = notifyChar.getDescriptor(CCCD);
            if (d == null) { ready.countDown(); return; }
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            else { d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); g.writeDescriptor(d); }
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (ready.getCount() > 0) ready.countDown();
        }

        // API 33+
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            for (byte b : value) rx.offer(b);
        }

        // API < 33 (depreciada, mas é a que o sistema chama nessas versões)
        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            if (v != null) for (byte b : v) rx.offer(b);
        }
    };

    private void pick(BluetoothGatt g) {
        List<BluetoothGattService> services = g.getServices();
        for (UUID h : HINTS) {
            for (BluetoothGattService s : services) if (s.getUuid().equals(h) && tryService(s)) return;
        }
        for (BluetoothGattService s : services) {
            String u = s.getUuid().toString();
            if (u.startsWith("00001800") || u.startsWith("00001801") || u.startsWith("0000180a")) continue; // serviços genéricos
            if (tryService(s)) return;
        }
    }

    private boolean tryService(BluetoothGattService s) {
        BluetoothGattCharacteristic w = null, n = null, both = null;
        for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
            int p = c.getProperties();
            boolean canWrite = (p & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0;
            boolean canNotify = (p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0;
            if (canWrite && canNotify && both == null) both = c;
            if (canWrite && w == null) w = c;
            if (canNotify && n == null) n = c;
        }
        if (both != null) { writeChar = both; notifyChar = both; return true; }
        if (w != null && n != null) { writeChar = w; notifyChar = n; return true; }
        return false;
    }

    @SuppressLint("MissingPermission")
    private void write(byte[] data) throws IOException {
        int off = 0;
        boolean noResp = (writeChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
        int type = noResp ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        while (off < data.length) {
            int len = Math.min(mtuPayload, data.length - off);
            byte[] chunk = Arrays.copyOfRange(data, off, off + len);
            boolean ok;
            if (Build.VERSION.SDK_INT >= 33) {
                ok = gatt.writeCharacteristic(writeChar, chunk, type) == BluetoothGatt.GATT_SUCCESS;
            } else {
                writeChar.setValue(chunk);
                writeChar.setWriteType(type);
                ok = gatt.writeCharacteristic(writeChar);
            }
            if (!ok) throw new IOException("Falha a escrever no adaptador BLE.");
            off += len;
            sleep(8);
        }
    }

    @Override
    public synchronized String command(String cmd, long timeoutMs) throws IOException {
        if (!open) throw new IOException("Ligação BLE fechada.");
        rx.clear();
        write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
        StringBuilder sb = new StringBuilder();
        long end = System.currentTimeMillis() + timeoutMs;
        try {
            while (System.currentTimeMillis() < end) {
                Byte b = rx.poll(20, TimeUnit.MILLISECONDS);
                if (b == null) { if (!open) throw new IOException("Ligação BLE fechada."); continue; }
                char c = (char) (b & 0xFF);
                if (c == '>') return clean(sb.toString(), cmd);
                if (c != 0) sb.append(c);
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return clean(sb.toString(), cmd);
    }

    @Override
    public synchronized void monitor(String cmd, Recorder.Sink sink, BooleanSupplier stop) throws IOException {
        rx.clear();
        write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
        StringBuilder line = new StringBuilder();
        try {
            while (!stop.getAsBoolean() && open) {
                Byte b = rx.poll(20, TimeUnit.MILLISECONDS);
                if (b == null) continue;
                char c = (char) (b & 0xFF);
                if (c == '\r' || c == '\n') { if (line.length() > 0) { sink.line(line.toString()); line.setLength(0); } }
                else if (c != '>' && c != 0) line.append(c);
            }
            write("\r".getBytes(StandardCharsets.US_ASCII)); // interrompe o ATMA
            long end = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < end) {
                Byte b = rx.poll(20, TimeUnit.MILLISECONDS);
                if (b != null && (b & 0xFF) == '>') break;
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String clean(String s, String cmd) {
        String r = s.replace("\r\n", "\r").trim();
        if (r.startsWith(cmd)) r = r.substring(cmd.length()).trim();
        return r;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @SuppressLint("MissingPermission")
    @Override public void close() {
        open = false;
        try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) { }
    }

    @Override public boolean isOpen() { return open; }
}
