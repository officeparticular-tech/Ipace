package pt.ipacediag.core;

import java.io.IOException;
import java.util.List;

/**
 * Sessão com o adaptador ELM327/STN em CAN 11 bits 500 kbit/s (ISO 15765-4),
 * que é o barramento de diagnóstico do I-Pace na tomada OBD.
 *
 * Esta classe só envia os serviços UDS que a app usa:
 *  0x22 ler dados, 0x19 ler erros, 0x14 apagar erros, 0x10 sessão, 0x3E presença.
 * Não há escrita de configuração (0x2E), rotinas (0x31) nem acesso de segurança (0x27).
 */
public final class ElmSession {

    public interface TrafficLog { void onTraffic(String cmd, String resp); }

    private final ObdTransport t;
    private final DoIpLink doip;
    private TrafficLog log;
    private int currentTx = -1;
    private String adapterId = "";
    private long cmdTimeoutMs = 2500;

    public ElmSession(ObdTransport t) { this.t = t; this.doip = null; }

    /** Sessão por DoIP (Ethernet). Não usa ELM327 nem comandos AT. */
    public ElmSession(DoIpLink link) { this.t = null; this.doip = link; }

    public boolean isDoip() { return doip != null; }

    public void setTrafficLog(TrafficLog l) { this.log = l; }
    public String adapterId() { return adapterId; }

    private synchronized String cmd(String c, long timeout) throws IOException {
        String r = t.command(c, timeout);
        if (log != null) log.onTraffic(c, r);
        return r == null ? "" : r;
    }

    /** Inicializa o adaptador. Devolve a identificação (ex.: "ELM327 v1.4b" / "STN2255"). */
    public synchronized String init() throws IOException {
        currentTx = -1;
        currentRx = -1;
        if (doip != null) { adapterId = doip.activate(); return adapterId; }
        String z = cmd("ATZ", 4000);
        cmd("ATE0", 1500);
        cmd("ATL0", 1500);
        cmd("ATS1", 1500);
        cmd("ATH1", 1500);
        cmd("ATSP6", 1500);
        cmd("ATCAF1", 1500);
        cmd("ATAT1", 1500);
        cmd("ATST64", 1500); // 0x64 x 4 ms = 400 ms de espera por resposta CAN
        String sti = cmd("STI", 1500);
        adapterId = (ElmParser.errorOf(sti) == null && sti.trim().length() > 0 ? sti.trim() : z.trim())
                .replace("\r", " ").replace("\n", " ").trim();
        return adapterId;
    }

    /** Tensão na tomada OBD medida pelo próprio adaptador (não acorda a rede do carro). */
    public double voltage() throws IOException {
        if (doip != null) return Double.NaN; // o cabo DoIP não mede a tensão da tomada
        return ElmParser.voltage(cmd("ATRV", 1500));
    }

    public static int rxFor(int tx) { return tx + 8; }

    private int currentRx = -1;

    private void target(int tx, int rx) throws IOException {
        if (tx == currentTx && rx == currentRx) return;
        cmd("ATSH" + Hex.h3(tx), 1500);
        cmd("ATCRA" + Hex.h3(rx), 1500);
        currentTx = tx;
        currentRx = rx;
    }

    /** Ajusta o tempo de espera do ELM (unidades de 4 ms, 1..255). */
    public synchronized void setCanTimeout(int units4ms) throws IOException {
        if (doip != null) return;
        int u = Math.max(1, Math.min(255, units4ms));
        cmd("ATST" + Hex.h2(u), 1500);
    }

    public UdsResponse request(int tx, byte[] req, int pendingRetries) throws IOException {
        return requestEx(tx, rxFor(tx), req, pendingRetries);
    }

    /**
     * Pedido com endereço de resposta explícito. No I-Pace (UDS) a resposta é tx+8,
     * mas noutros carros não: no Nissan Leaf pergunta-se a 0x79B e responde em 0x7BB.
     */
    public synchronized UdsResponse requestEx(int tx, int rx, byte[] req, int pendingRetries) throws IOException {
        if (doip != null) return doip.request(tx, rx, req, pendingRetries);
        target(tx, rx);
        String hex = Hex.of(req).replace(" ", "");
        int sid = req[0] & 0xFF;
        String raw = "";
        for (int attempt = 0; attempt <= pendingRetries; attempt++) {
            raw = cmd(hex, cmdTimeoutMs);
            List<byte[]> msgs = ElmParser.messages(raw, rx);
            boolean pending = false;
            UdsResponse last = null;
            for (byte[] m : msgs) {
                if (m.length >= 3 && (m[0] & 0xFF) == 0x7F && (m[1] & 0xFF) == sid) {
                    int nrc = m[2] & 0xFF;
                    if (nrc == Nrc.RESPONSE_PENDING) { pending = true; continue; }
                    last = UdsResponse.negative(nrc, m, raw);
                } else if (m.length >= 1 && (m[0] & 0xFF) == sid + 0x40) {
                    last = UdsResponse.positive(m, raw);
                }
            }
            if (last != null) {
                if (last.kind == UdsResponse.Kind.NEGATIVE && last.nrc == 0x21 && attempt < pendingRetries) {
                    sleep(200);
                    continue;
                }
                return last;
            }
            if (pending && attempt < pendingRetries) { sleep(400); continue; }
            String err = ElmParser.errorOf(raw);
            if (err != null && !err.equals("NO DATA")) return UdsResponse.adapterError(err, raw);
            if (!pending) return UdsResponse.none(raw);
        }
        return UdsResponse.none(raw);
    }

    public UdsResponse request(int tx, byte[] req) throws IOException { return request(tx, req, 3); }

    /** 0x22 ReadDataByIdentifier. Devolve só os dados (sem 62 + DID) ou null. */
    public byte[] readDid(int tx, int did) throws IOException {
        UdsResponse r = request(tx, new byte[]{0x22, (byte) (did >> 8), (byte) did});
        if (!r.ok() || r.payload.length < 3) return null;
        if ((Hex.u16(r.payload, 1)) != did) return null;
        return r.dataAfter(3);
    }

    public UdsResponse readDidRaw(int tx, int did) throws IOException {
        return request(tx, new byte[]{0x22, (byte) (did >> 8), (byte) did}, 1);
    }

    public UdsResponse testerPresent(int tx) throws IOException {
        return request(tx, new byte[]{0x3E, 0x00}, 0);
    }

    public UdsResponse session(int tx, int type) throws IOException {
        return request(tx, new byte[]{0x10, (byte) type});
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /**
     * Modo escuta silencioso (o adaptador não confirma tramas nem transmite):
     * grava o tráfego de diagnóstico 0x700–0x7FF em bruto. No fim reinicia o adaptador.
     */
    public synchronized void monitor(Recorder.Sink sink, java.util.function.BooleanSupplier stop) throws IOException {
        if (doip != null) throw new IOException("O modo escuta (sniffer) não está disponível por DoIP.");
        cmd("ATCAF0", 1500);
        cmd("ATCSM1", 1500);
        cmd("ATCF700", 1500);
        cmd("ATCM700", 1500);
        try {
            t.monitor("ATMA", sink, stop);
        } finally {
            init();
        }
    }

    public void close() { if (doip != null) doip.close(); else t.close(); }
    public boolean isOpen() { return doip != null ? doip.isOpen() : t.isOpen(); }
}
