package pt.ipacediag.core;

import java.io.IOException;
import java.util.Locale;

/**
 * Leitura da bateria do Nissan Leaf (2015, 24 kWh, AZE0) ao estilo do LeafSpy.
 *
 * O controlador da bateria (LBC) responde à tomada OBD por CAN 11 bits 500k:
 * pergunta-se ao endereço 0x79B e lê-se a resposta em 0x7BB, com o serviço 0x21
 * por grupos (0x21 01 = saúde, 0x21 03 = tensões de célula, 0x21 04 = temperaturas).
 * As respostas vêm em várias tramas (ISO-TP), que o ElmSession já remonta.
 *
 * IMPORTANTE: as POSIÇÕES e FÓRMULAS exatas de cada campo no Leaf não estão
 * totalmente normalizadas em fontes públicas. As usadas aqui são provisórias e
 * DEVEM ser confirmadas no carro (ou por um pacote de definições). O que está
 * sólido é a parte de comunicação (perguntar a 0x79B, ler 0x7BB, remontar as
 * tramas) e as tensões de célula. Por isso os valores aparecem marcados como
 * "por confirmar".
 */
public final class LeafBattery {

    public static final int LBC_TX = 0x79B, LBC_RX = 0x7BB;

    public boolean ok;
    public String error = "";
    public double packV = Double.NaN, packA = Double.NaN;
    public double soh = Double.NaN, hx = Double.NaN, ahr = Double.NaN, soc = Double.NaN;
    public double tMin = Double.NaN, tMax = Double.NaN;
    public int cellCount;
    public final double[] cells = new double[96];
    public byte[] rawGroup1, rawGroup3, rawGroup4;

    /** Lê um grupo (serviço 0x21 + nº do grupo). Devolve o payload completo (61 0g …) ou null. */
    public static byte[] group(ElmSession s, int g) throws IOException {
        UdsResponse r = s.requestEx(LBC_TX, LBC_RX, new byte[]{0x21, (byte) g}, 3);
        return r.ok() ? r.payload : null;
    }

    public LeafBattery read(ElmSession s) throws IOException {
        rawGroup1 = group(s, 0x01);
        rawGroup3 = group(s, 0x03);
        rawGroup4 = group(s, 0x04);
        if (rawGroup1 == null && rawGroup3 == null) {
            error = "O controlador da bateria (LBC) não respondeu. Confirme que o carro está ligado (contacto).";
            return this;
        }
        decodeGroup1(rawGroup1);
        decodeCells(rawGroup3);
        decodeTemps(rawGroup4);
        ok = true;
        return this;
    }

    // --- descodificação (layout provisório, a validar no carro) ---

    private void decodeGroup1(byte[] p) {
        if (p == null || p.length < 14) return;
        // p[0]=0x61, p[1]=0x01, depois os campos:
        packV = Hex.u16(p, 2) / 100.0;                        // tensão do pack
        packA = ((short) Hex.u16(p, 4)) / 100.0;              // corrente (com sinal)
        soh = Hex.u16(p, 6) / 100.0;                          // saúde (%)
        hx = Hex.u16(p, 8) / 100.0;                           // Hx (%)
        ahr = Hex.u16(p, 10) / 100.0;                         // capacidade real (Ah)
        soc = Hex.u16(p, 12) / 100.0;                         // estado de carga (%)
    }

    private void decodeCells(byte[] p) {
        if (p == null || p.length < 4) return;
        int count = Hex.u8(p, 2);                             // nº de pares de célula
        if (count <= 0 || count > 96) count = 96;
        int n = 0;
        for (int i = 0; i < count && 3 + i * 2 + 1 < p.length; i++) {
            cells[i] = Hex.u16(p, 3 + i * 2) / 1000.0;        // milivolts → volts
            n++;
        }
        cellCount = n;
    }

    private void decodeTemps(byte[] p) {
        if (p == null || p.length < 4) return;
        double a = Hex.u8(p, 2) - 40.0, b = Hex.u8(p, 3) - 40.0;
        tMin = Math.min(a, b);
        tMax = Math.max(a, b);
    }

    public double cellMin() { double m = Double.MAX_VALUE; for (int i = 0; i < cellCount; i++) m = Math.min(m, cells[i]); return cellCount == 0 ? Double.NaN : m; }
    public double cellMax() { double m = -1; for (int i = 0; i < cellCount; i++) m = Math.max(m, cells[i]); return cellCount == 0 ? Double.NaN : m; }
    public double cellAvg() { double s = 0; for (int i = 0; i < cellCount; i++) s += cells[i]; return cellCount == 0 ? Double.NaN : s / cellCount; }
    public double cellDeltaMv() { return cellCount == 0 ? Double.NaN : (cellMax() - cellMin()) * 1000.0; }

    public String sohVerdict() {
        if (Double.isNaN(soh)) return "";
        if (soh >= 85) return "BOM";
        if (soh >= 75) return "ACEITÁVEL";
        if (soh >= 65) return "FRACO — perto de perder barra de capacidade";
        return "MAU";
    }

    public static String f(double v, int dec, String unit) {
        return Double.isNaN(v) ? "—" : String.format(Locale.US, "%." + dec + "f", v) + (unit.isEmpty() ? "" : " " + unit);
    }
}
