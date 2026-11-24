package pt.ipacediag.core;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Interpreta o texto devolvido por um adaptador ELM327/STN (OBDLink MX+)
 * configurado com ATH1 (cabeçalhos) e ATCAF1 (formatação automática).
 * Cada linha útil tem a forma "7EC 10 14 62 49 10 ..." (ID + PCI + dados).
 * A remontagem ISO-TP (tramas múltiplas) é feita aqui.
 */
public final class ElmParser {
    private ElmParser() {}

    public static final class Frame {
        public final int id;
        public final byte[] data;
        Frame(int id, byte[] data) { this.id = id; this.data = data; }
    }

    /** Erros de texto conhecidos do ELM/STN. */
    public static String errorOf(String raw) {
        String u = raw.toUpperCase();
        String[] errs = {"NO DATA", "CAN ERROR", "BUS ERROR", "BUS INIT", "UNABLE TO CONNECT",
                "BUFFER FULL", "STOPPED", "DATA ERROR", "FB ERROR", "LV RESET", "ACT ALERT", "?"};
        for (String e : errs) {
            if (e.equals("?")) {
                if (u.trim().equals("?")) return "?";
            } else if (u.contains(e)) return e;
        }
        return null;
    }

    public static List<Frame> frames(String raw) {
        List<Frame> out = new ArrayList<>();
        if (raw == null) return out;
        for (String line0 : raw.split("[\\r\\n]+")) {
            String line = line0.trim();
            if (line.isEmpty() || line.equals(">")) continue;
            String[] tok = line.split("\\s+");
            String idTok;
            List<String> bytes = new ArrayList<>();
            if (tok.length >= 2 && tok[0].length() == 3 && Hex.isHex(tok[0])) {
                idTok = tok[0];
                boolean ok = true;
                for (int i = 1; i < tok.length; i++) {
                    if (tok[i].length() != 2 || !Hex.isHex(tok[i])) { ok = false; break; }
                    bytes.add(tok[i]);
                }
                if (!ok) continue;
            } else if (tok.length == 1 && tok[0].length() >= 5 && tok[0].length() % 2 == 1 && Hex.isHex(tok[0])) {
                // Espaços desligados (ATS0): "7EC0462490F1234"
                idTok = tok[0].substring(0, 3);
                String rest = tok[0].substring(3);
                for (int i = 0; i + 1 < rest.length(); i += 2) bytes.add(rest.substring(i, i + 2));
            } else {
                continue;
            }
            byte[] d = new byte[bytes.size()];
            for (int i = 0; i < d.length; i++) d[i] = (byte) Integer.parseInt(bytes.get(i), 16);
            out.add(new Frame(Integer.parseInt(idTok, 16), d));
        }
        return out;
    }

    /**
     * Remonta as mensagens ISO-TP vindas de rxId. Pode haver várias mensagens
     * na mesma resposta (ex.: "resposta pendente" 7F xx 78 seguida da resposta final).
     */
    public static List<byte[]> messages(String raw, int rxId) {
        List<byte[]> msgs = new ArrayList<>();
        ByteArrayOutputStream cur = null;
        int expected = 0;
        for (Frame f : frames(raw)) {
            if (f.id != rxId || f.data.length == 0) continue;
            int pci = (f.data[0] & 0xF0) >> 4;
            switch (pci) {
                case 0: { // trama única
                    int len = f.data[0] & 0x0F;
                    if (len == 0 || len > f.data.length - 1) len = f.data.length - 1;
                    byte[] m = new byte[len];
                    System.arraycopy(f.data, 1, m, 0, len);
                    msgs.add(m);
                    cur = null;
                    break;
                }
                case 1: { // primeira trama
                    if (f.data.length < 2) break;
                    expected = ((f.data[0] & 0x0F) << 8) | (f.data[1] & 0xFF);
                    cur = new ByteArrayOutputStream();
                    cur.write(f.data, 2, f.data.length - 2);
                    if (cur.size() >= expected) { msgs.add(trim(cur, expected)); cur = null; }
                    break;
                }
                case 2: { // trama consecutiva
                    if (cur == null) break;
                    cur.write(f.data, 1, f.data.length - 1);
                    if (cur.size() >= expected) { msgs.add(trim(cur, expected)); cur = null; }
                    break;
                }
                default: // controlo de fluxo (3) ou desconhecido: ignorar
                    break;
            }
        }
        if (cur != null && cur.size() > 0) msgs.add(cur.toByteArray()); // incompleta, melhor que nada
        return msgs;
    }

    private static byte[] trim(ByteArrayOutputStream b, int len) {
        byte[] all = b.toByteArray();
        if (all.length == len) return all;
        byte[] m = new byte[len];
        System.arraycopy(all, 0, m, 0, len);
        return m;
    }

    /** Lê a tensão de "ATRV" (ex.: "12.6V"). Devolve NaN se não perceber. */
    public static double voltage(String raw) {
        if (raw == null) return Double.NaN;
        String s = raw.replace(">", "").trim();
        StringBuilder num = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isDigit(c) || c == '.') num.append(c);
            else if (num.length() > 0) break;
        }
        try { return Double.parseDouble(num.toString()); } catch (Exception e) { return Double.NaN; }
    }
}
