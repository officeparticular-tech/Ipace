package pt.ipacediag.core;

import java.util.Random;

/**
 * Nissan Leaf SIMULADO para o modo demonstração.
 * Responde como um ELM327 ligado ao LBC do Leaf: pergunta a 0x79B, responde em 0x7BB,
 * com o serviço 0x21 por grupos. Os valores são fictícios mas plausíveis para um
 * Leaf de 2015 com alguma degradação (SOH ~86%, AHr ~56). Coerente com o LeafBattery.
 */
public final class DemoLeaf implements ObdTransport {

    private int tx = 0x7DF, rx = 0x7E8;
    private boolean open = true;
    private final Random rnd = new Random(11);

    public boolean isOpen() { return open; }
    public void close() { open = false; }

    private static byte[] cat(int... parts) {
        byte[] b = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) b[i] = (byte) parts[i];
        return b;
    }

    private static byte[] u16(double v) { int x = (int) Math.round(v) & 0xFFFF; return cat(x >> 8, x & 0xFF); }

    private String frames(byte[] p) {
        if (p.length <= 7) return Hex.h3(rx) + " " + Hex.h2(p.length) + " " + Hex.of(p);
        StringBuilder sb = new StringBuilder();
        sb.append(Hex.h3(rx)).append(" 1").append(Integer.toHexString((p.length >> 8) & 0xF).toUpperCase())
                .append(' ').append(Hex.h2(p.length)).append(' ').append(Hex.of(p, 0, 6)).append('\r');
        int idx = 6, seq = 1;
        while (idx < p.length) {
            int end = Math.min(p.length, idx + 7);
            sb.append(Hex.h3(rx)).append(" 2").append(Integer.toHexString(seq & 0xF).toUpperCase()).append(' ').append(Hex.of(p, idx, end));
            for (int k = end - idx; k < 7; k++) sb.append(" AA");
            sb.append('\r');
            idx = end; seq++;
        }
        return sb.toString();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] c = new byte[a.length + b.length];
        System.arraycopy(a, 0, c, 0, a.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }

    private byte[] group1() {
        // 61 01 + tensão, corrente, SOH, Hx, AHr, SOC  (layout coerente com LeafBattery)
        byte[] p = cat(0x61, 0x01);
        p = concat(p, u16(36420));   // 364.20 V
        p = concat(p, u16(-480 & 0xFFFF)); // -4.80 A
        p = concat(p, u16(8640));    // SOH 86.40 %
        p = concat(p, u16(8820));    // Hx 88.20 %
        p = concat(p, u16(5612));    // AHr 56.12 Ah
        p = concat(p, u16(7350));    // SOC 73.50 %
        p = concat(p, cat(0x00, 0x00)); // reserva
        return p;
    }

    private byte[] group3() {
        // 61 03 + contagem + 96 pares de célula (mV), à volta de 3.9 V com pequena dispersão
        byte[] head = cat(0x61, 0x03, 0x60); // 0x60 = 96
        byte[] cells = new byte[96 * 2];
        for (int i = 0; i < 96; i++) {
            int mv = 3900 + rnd.nextInt(24) - 12;
            cells[i * 2] = (byte) (mv >> 8);
            cells[i * 2 + 1] = (byte) mv;
        }
        return concat(head, cells);
    }

    private byte[] group4() {
        // 61 04 + duas temperaturas (raw = °C + 40): ~19 e ~22
        return cat(0x61, 0x04, 59, 62, 0x00, 0x00);
    }

    @Override
    public synchronized String command(String c, long timeoutMs) {
        try { Thread.sleep(12 + rnd.nextInt(16)); } catch (InterruptedException ignored) { }
        if (c.equals("ATZ")) return "ELM327 v1.5";
        if (c.equals("STI")) return "Simulador Leaf (modo demonstração)";
        if (c.equals("ATRV")) return "12.5V";
        if (c.startsWith("ATSH")) { tx = Integer.parseInt(c.substring(4), 16); return "OK"; }
        if (c.startsWith("ATCRA")) { rx = Integer.parseInt(c.substring(5), 16); return "OK"; }
        if (c.startsWith("AT")) return "OK";
        byte[] req;
        try { req = Hex.parse(c); } catch (Exception e) { return "?"; }
        if (tx == LeafBattery.LBC_TX && req.length >= 2 && (req[0] & 0xFF) == 0x21) {
            switch (req[1] & 0xFF) {
                case 0x01: return frames(group1());
                case 0x03: return frames(group3());
                case 0x04: return frames(group4());
                default: return frames(cat(0x7F, 0x21, 0x31));
            }
        }
        if (req.length >= 1 && (req[0] & 0xFF) == 0x3E) return frames(cat(0x7E, 0x00));
        return "NO DATA";
    }
}
