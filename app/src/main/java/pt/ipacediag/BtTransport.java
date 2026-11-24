package pt.ipacediag;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import pt.ipacediag.core.ObdTransport;

/** Ligação Bluetooth clássica (perfil série SPP) ao OBDLink MX+ / ELM327. */
public final class BtTransport implements ObdTransport {
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final BluetoothSocket socket;
    private final InputStream in;
    private final OutputStream out;

    @SuppressLint("MissingPermission")
    public BtTransport(BluetoothDevice dev) throws IOException {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        try { if (ad != null) ad.cancelDiscovery(); } catch (SecurityException ignored) { } // exige BLUETOOTH_SCAN; não é essencial
        BluetoothSocket s;
        try {
            s = dev.createRfcommSocketToServiceRecord(SPP);
            s.connect();
        } catch (IOException first) {
            // Alguns adaptadores só aceitam o canal 1 direto
            try {
                Method m = dev.getClass().getMethod("createRfcommSocket", int.class);
                s = (BluetoothSocket) m.invoke(dev, 1);
                s.connect();
            } catch (Exception e) {
                throw new IOException("Não foi possível ligar ao adaptador: " + first.getMessage());
            }
        }
        socket = s;
        in = s.getInputStream();
        out = s.getOutputStream();
    }

    @Override
    public synchronized String command(String cmd, long timeoutMs) throws IOException {
        // descartar lixo pendente
        while (in.available() > 0) in.read();
        out.write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        StringBuilder sb = new StringBuilder();
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (in.available() > 0) {
                int c = in.read();
                if (c < 0) throw new IOException("Ligação fechada");
                if (c == '>') return clean(sb.toString(), cmd);
                if (c != 0) sb.append((char) c);
            } else {
                try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        return clean(sb.toString(), cmd); // tempo esgotado: devolve o que veio
    }

    @Override
    public synchronized void monitor(String cmd, pt.ipacediag.core.Recorder.Sink sink, java.util.function.BooleanSupplier stop) throws IOException {
        while (in.available() > 0) in.read();
        out.write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        StringBuilder line = new StringBuilder();
        while (!stop.getAsBoolean()) {
            if (in.available() > 0) {
                int c = in.read();
                if (c < 0) throw new IOException("Ligação fechada");
                if (c == '\r' || c == '\n') {
                    if (line.length() > 0) { sink.line(line.toString()); line.setLength(0); }
                } else if (c != '>' && c != 0) line.append((char) c);
            } else {
                try { Thread.sleep(3); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        // qualquer carácter interrompe o modo escuta do ELM/STN
        out.write("\r".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        long end = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < end) {
            if (in.available() > 0) { if (in.read() == '>') break; }
            else { try { Thread.sleep(5); } catch (InterruptedException e) { break; } }
        }
    }

    private static String clean(String s, String cmd) {
        String r = s.replace("\r\n", "\r").trim();
        if (r.startsWith(cmd)) r = r.substring(cmd.length()).trim(); // eco, caso ATE0 ainda não ativo
        return r;
    }

    @Override public void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }

    @Override public boolean isOpen() { return socket.isConnected(); }
}
