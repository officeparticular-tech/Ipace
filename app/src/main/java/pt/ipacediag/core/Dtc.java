package pt.ipacediag.core;

import java.util.ArrayList;
import java.util.List;

/** Código de erro UDS (3 bytes) + byte de estado (ISO 14229-1). */
public final class Dtc {
    public final int b1, b2, ftb, status;
    public String moduleName = "";
    public int moduleTx;

    public Dtc(int b1, int b2, int ftb, int status) {
        this.b1 = b1 & 0xFF; this.b2 = b2 & 0xFF; this.ftb = ftb & 0xFF; this.status = status & 0xFF;
    }

    /** Ex.: 0A A6 00 -> "P0AA6-00" (formato usado pela JLR). */
    public String code() {
        char sys = "PCBU".charAt((b1 >> 6) & 0x3);
        int d1 = (b1 >> 4) & 0x3;
        return String.format("%c%d%X%02X-%02X", sys, d1, b1 & 0xF, b2, ftb);
    }

    /** Código sem o tipo de falha, ex.: "P0AA6". */
    public String base() { return code().substring(0, 5); }

    public boolean testFailed() { return (status & 0x01) != 0; }
    public boolean pending() { return (status & 0x04) != 0; }
    public boolean confirmed() { return (status & 0x08) != 0; }
    public boolean warningLamp() { return (status & 0x80) != 0; }

    public String statusText() {
        List<String> s = new ArrayList<>();
        if ((status & 0x01) != 0) s.add("ativo agora");
        if ((status & 0x02) != 0) s.add("falhou neste ciclo");
        if ((status & 0x04) != 0) s.add("pendente");
        if ((status & 0x08) != 0) s.add("confirmado");
        if ((status & 0x20) != 0) s.add("falhou desde o último apagar");
        if ((status & 0x80) != 0) s.add("pede luz de aviso");
        if (s.isEmpty()) s.add("histórico");
        return String.join(", ", s) + " (estado 0x" + Hex.h2(status) + ")";
    }

    public String describe() {
        String d = DtcCatalog.describe(this);
        return code() + "  " + d + "\n    " + statusText();
    }

    /** Lê a resposta de 19 02 (59 02 máscara [DTC1 DTC2 DTC3 estado]...). */
    public static List<Dtc> parseList(byte[] payload) {
        List<Dtc> out = new ArrayList<>();
        if (payload == null || payload.length < 3) return out;
        for (int i = 3; i + 3 < payload.length; i += 4) {
            int a = payload[i] & 0xFF, b = payload[i + 1] & 0xFF, c = payload[i + 2] & 0xFF;
            if (a == 0 && b == 0 && c == 0) continue;
            out.add(new Dtc(a, b, c, payload[i + 3]));
        }
        return out;
    }
}
