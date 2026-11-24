package pt.ipacediag.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * Ligação DoIP por TCP a uma entidade de diagnóstico (gateway do carro).
 * Faz a ativação de rota e envia/recebe mensagens UDS pela porta 13400.
 *
 * Nota honesta: o endereçamento é por ENDEREÇO LÓGICO (não por ID de CAN como no
 * resto da app). Como não há tabela pública de endereços lógicos do I-Pace, esta
 * ligação fala com UM módulo — o endereço de destino configurado. Os pedidos da app
 * que trazem um ID de CAN são encaminhados todos para esse destino.
 */
public final class DoIpLink {

    private final String host;
    private final int port;
    private final int source;   // endereço do tester (ex.: 0x0E80)
    private final int target;   // endereço lógico do módulo/gateway

    private Socket sock;
    private InputStream in;
    private OutputStream out;
    private final long perReadMs = 3000;
    private String label = "";

    public DoIpLink(String host, int port, int source, int target) {
        this.host = host;
        this.port = port <= 0 ? DoIp.PORT : port;
        this.source = source;
        this.target = target;
    }

    /** Liga o socket e faz a ativação de rota. Devolve uma etiqueta para mostrar. */
    public synchronized String activate() throws IOException {
        sock = new Socket();
        sock.connect(new InetSocketAddress(host, port), 4000);
        sock.setSoTimeout((int) perReadMs);
        sock.setTcpNoDelay(true);
        in = sock.getInputStream();
        out = sock.getOutputStream();

        out.write(DoIp.routingActivation(source));
        out.flush();

        DoIp.Frame f = readFrame();
        if (f == null)
            throw new IOException("A entidade DoIP não respondeu à ativação de rota (verifique o cabo/endereços).");
        if (f.payloadType != DoIp.PT_ROUTING_ACT_RESP)
            throw new IOException("Resposta DoIP inesperada (0x" + Integer.toHexString(f.payloadType) + ").");
        int code = f.code1(4); // [tester(2)][entidade(2)][código(1)]
        if (code != DoIp.ROUTING_OK)
            throw new IOException("Ativação de rota recusada (código 0x" + Integer.toHexString(code) + ").");
        int entity = f.u16(2);
        label = "DoIP " + host + " · módulo 0x" + Integer.toHexString(target).toUpperCase()
                + (entity > 0 ? " (gw 0x" + Integer.toHexString(entity).toUpperCase() + ")" : "");
        return label;
    }

    /** Envia UDS e espera a resposta de diagnóstico. tx/rx (IDs de CAN) são ignorados. */
    public synchronized UdsResponse request(int tx, int rx, byte[] req, int pendingRetries) throws IOException {
        if (out == null) return UdsResponse.adapterError("DoIP não ligado", "");
        out.write(DoIp.diag(source, target, req));
        out.flush();

        int sid = req[0] & 0xFF;
        int pend = 0;
        long deadline = System.currentTimeMillis() + perReadMs * (pendingRetries + 2L);

        while (System.currentTimeMillis() < deadline) {
            DoIp.Frame f;
            try {
                f = readFrame();
            } catch (SocketTimeoutException e) {
                return UdsResponse.none("timeout DoIP");
            }
            if (f == null) return UdsResponse.none("ligação DoIP fechada");

            switch (f.payloadType) {
                case DoIp.PT_DIAG_ACK:
                    continue; // confirmação de transporte — continua à espera do UDS
                case DoIp.PT_DIAG_NACK:
                    return UdsResponse.adapterError("NACK DoIP 0x" + Integer.toHexString(f.code1(4)), "");
                case DoIp.PT_ALIVE_REQ:
                    out.write(DoIp.aliveResponse(source));
                    out.flush();
                    continue;
                case DoIp.PT_DIAG: {
                    byte[] uds = f.uds();
                    if (uds.length >= 3 && (uds[0] & 0xFF) == 0x7F && (uds[1] & 0xFF) == sid) {
                        int nrc = uds[2] & 0xFF;
                        if (nrc == Nrc.RESPONSE_PENDING) {
                            if (pend++ < pendingRetries) continue;
                            return UdsResponse.negative(nrc, uds, "");
                        }
                        return UdsResponse.negative(nrc, uds, "");
                    }
                    if (uds.length >= 1 && (uds[0] & 0xFF) == sid + 0x40)
                        return UdsResponse.positive(uds, "");
                    continue; // não é para este pedido — continua
                }
                default:
                    continue;
            }
        }
        return UdsResponse.none("timeout DoIP");
    }

    public double voltage() { return Double.NaN; } // o cabo DoIP não mede a tensão da tomada

    public boolean isOpen() { return sock != null && sock.isConnected() && !sock.isClosed(); }

    public void close() {
        try { if (sock != null) sock.close(); } catch (IOException ignored) {}
        sock = null; in = null; out = null;
    }

    // -------- leitura de tramas --------

    private DoIp.Frame readFrame() throws IOException {
        byte[] h = readN(8);
        if (h == null) return null;
        if ((h[0] & 0xFF) != DoIp.PROTO) throw new IOException("Cabeçalho DoIP inválido.");
        int pt = DoIp.payloadType(h);
        long len = DoIp.payloadLen(h);
        if (len < 0 || len > 1 << 20) throw new IOException("Carga DoIP com tamanho inválido.");
        byte[] p = len == 0 ? new byte[0] : readN((int) len);
        if (p == null) return null;
        return new DoIp.Frame(pt, p);
    }

    private byte[] readN(int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) return null;
            off += r;
        }
        return b;
    }
}
