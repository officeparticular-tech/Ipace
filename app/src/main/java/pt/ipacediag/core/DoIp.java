package pt.ipacediag.core;

/**
 * Enquadramento DoIP (Diagnostics over IP, ISO 13400-2). O DoIP transporta as MESMAS
 * mensagens UDS que já usamos por CAN — muda só a "embalagem": em vez de tramas CAN,
 * cada mensagem UDS vai dentro de um pacote TCP com um cabeçalho de 8 bytes.
 *
 * IMPORTANTE: o DoIP NÃO contorna o acesso de segurança da JLR (0x27). Continua a ser
 * só leitura, apagar erros e as mesmas rotinas não-protegidas. É apenas outro meio físico.
 *
 * Esta classe é lógica pura (sem sockets), para poder ser testada sem hardware.
 */
public final class DoIp {
    private DoIp() {}

    public static final int PORT = 13400;
    public static final int PROTO = 0x02; // ISO 13400-2:2012

    // Tipos de carga (payload types)
    public static final int PT_VEHICLE_ID_REQ   = 0x0001;
    public static final int PT_VEHICLE_ID_RESP  = 0x0004;
    public static final int PT_ROUTING_ACT_REQ  = 0x0005;
    public static final int PT_ROUTING_ACT_RESP = 0x0006;
    public static final int PT_ALIVE_REQ        = 0x0007;
    public static final int PT_ALIVE_RESP       = 0x0008;
    public static final int PT_DIAG             = 0x8001;
    public static final int PT_DIAG_ACK         = 0x8002;
    public static final int PT_DIAG_NACK        = 0x8003;

    public static final int ROUTING_OK = 0x10; // rota ativada com sucesso

    /** Cabeçalho DoIP + carga. */
    public static byte[] frame(int payloadType, byte[] payload) {
        int n = payload == null ? 0 : payload.length;
        byte[] h = new byte[8 + n];
        h[0] = (byte) PROTO;
        h[1] = (byte) ~PROTO;
        h[2] = (byte) (payloadType >> 8);
        h[3] = (byte) payloadType;
        h[4] = (byte) (n >> 24);
        h[5] = (byte) (n >> 16);
        h[6] = (byte) (n >> 8);
        h[7] = (byte) n;
        if (n > 0) System.arraycopy(payload, 0, h, 8, n);
        return h;
    }

    /** Pedido de ativação de rota: [origem(2)][tipo=00][reservado(4)]. */
    public static byte[] routingActivation(int source) {
        byte[] p = new byte[7];
        p[0] = (byte) (source >> 8);
        p[1] = (byte) source;
        // p[2] = 0x00 tipo de ativação por omissão; restantes 0
        return frame(PT_ROUTING_ACT_REQ, p);
    }

    /** Pedido de identificação do veículo (UDP, descoberta). */
    public static byte[] vehicleIdRequest() {
        return frame(PT_VEHICLE_ID_REQ, new byte[0]);
    }

    /** Mensagem de diagnóstico: [origem(2)][destino(2)][UDS...]. */
    public static byte[] diag(int source, int target, byte[] uds) {
        byte[] p = new byte[4 + uds.length];
        p[0] = (byte) (source >> 8);
        p[1] = (byte) source;
        p[2] = (byte) (target >> 8);
        p[3] = (byte) target;
        System.arraycopy(uds, 0, p, 4, uds.length);
        return frame(PT_DIAG, p);
    }

    public static byte[] aliveResponse(int source) {
        byte[] p = new byte[]{(byte) (source >> 8), (byte) source};
        return frame(PT_ALIVE_RESP, p);
    }

    /** Um pacote DoIP recebido, já separado em tipo + carga. */
    public static final class Frame {
        public final int payloadType;
        public final byte[] payload;
        public Frame(int t, byte[] p) { payloadType = t; payload = p == null ? new byte[0] : p; }

        public int code1(int at) { return at < payload.length ? (payload[at] & 0xFF) : -1; }
        public int u16(int at) { return (at + 1 < payload.length) ? (((payload[at] & 0xFF) << 8) | (payload[at + 1] & 0xFF)) : -1; }

        /** UDS dentro de uma mensagem de diagnóstico (a seguir aos 4 bytes de endereços). */
        public byte[] uds() {
            if (payloadType != PT_DIAG || payload.length <= 4) return new byte[0];
            byte[] u = new byte[payload.length - 4];
            System.arraycopy(payload, 4, u, 0, u.length);
            return u;
        }
    }

    /** Lê o tipo de carga de um cabeçalho (para parsing fora desta classe). */
    public static int payloadType(byte[] header8) {
        return ((header8[2] & 0xFF) << 8) | (header8[3] & 0xFF);
    }

    public static long payloadLen(byte[] header8) {
        return ((long) (header8[4] & 0xFF) << 24) | ((header8[5] & 0xFF) << 16)
                | ((header8[6] & 0xFF) << 8) | (header8[7] & 0xFF);
    }

    /** Resultado da descoberta de um veículo (resposta 0x0004). */
    public static final class Announcement {
        public final String vin;
        public final int logicalAddress;
        public Announcement(String vin, int addr) { this.vin = vin; this.logicalAddress = addr; }
    }

    /** Descodifica uma resposta de identificação: VIN(17) + endereço lógico(2). */
    public static Announcement parseAnnouncement(byte[] payload) {
        if (payload.length < 19) return null;
        StringBuilder vin = new StringBuilder();
        for (int i = 0; i < 17; i++) {
            char c = (char) (payload[i] & 0xFF);
            vin.append(c >= 32 && c < 127 ? c : '?');
        }
        int addr = ((payload[17] & 0xFF) << 8) | (payload[18] & 0xFF);
        return new Announcement(vin.toString().trim(), addr);
    }
}
