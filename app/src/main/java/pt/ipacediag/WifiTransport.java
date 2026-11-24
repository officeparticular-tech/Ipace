package pt.ipacediag;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import pt.ipacediag.core.ObdTransport;
import pt.ipacediag.core.Recorder;

/**
 * Ligação a um adaptador OBD WiFi (ELM327 WiFi). Estes adaptadores criam uma
 * rede WiFi própria e abrem um socket TCP, tipicamente em 192.168.0.10:35000.
 * O telemóvel tem de estar ligado à WiFi do adaptador.
 */
public final class WifiTransport implements ObdTransport {

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    public WifiTransport(String host, int port) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 6000);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(50);
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    @Override
    public synchronized String command(String cmd, long timeoutMs) throws IOException {
        drain();
        out.write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        StringBuilder sb = new StringBuilder();
        long end = System.currentTimeMillis() + timeoutMs;
        byte[] buf = new byte[256];
        while (System.currentTimeMillis() < end) {
            int n;
            try { n = in.read(buf); } catch (java.net.SocketTimeoutException e) { continue; }
            if (n < 0) throw new IOException("Ligação WiFi fechada.");
            for (int i = 0; i < n; i++) {
                char c = (char) (buf[i] & 0xFF);
                if (c == '>') return clean(sb.toString(), cmd);
                if (c != 0) sb.append(c);
            }
        }
        return clean(sb.toString(), cmd);
    }

    @Override
    public synchronized void monitor(String cmd, Recorder.Sink sink, java.util.function.BooleanSupplier stop) throws IOException {
        drain();
        out.write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        StringBuilder line = new StringBuilder();
        byte[] buf = new byte[256];
        while (!stop.getAsBoolean()) {
            int n;
            try { n = in.read(buf); } catch (java.net.SocketTimeoutException e) { continue; }
            if (n < 0) break;
            for (int i = 0; i < n; i++) {
                char c = (char) (buf[i] & 0xFF);
                if (c == '\r' || c == '\n') { if (line.length() > 0) { sink.line(line.toString()); line.setLength(0); } }
                else if (c != '>' && c != 0) line.append(c);
            }
        }
        out.write("\r".getBytes(StandardCharsets.US_ASCII)); // interrompe o ATMA
        out.flush();
        long end = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < end) {
            int n;
            try { n = in.read(buf); } catch (java.net.SocketTimeoutException e) { continue; }
            if (n < 0) break;
            for (int i = 0; i < n; i++) if ((buf[i] & 0xFF) == '>') return;
        }
    }

    private void drain() throws IOException {
        byte[] buf = new byte[256];
        try { while (in.read(buf) > 0) { } } catch (java.net.SocketTimeoutException ignored) { }
    }

    private static String clean(String s, String cmd) {
        String r = s.replace("\r\n", "\r").trim();
        if (r.startsWith(cmd)) r = r.substring(cmd.length()).trim();
        return r;
    }

    @Override public void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }

    @Override public boolean isOpen() { return socket.isConnected() && !socket.isClosed(); }
}
