package pt.ipacediag.core;

/** Utilitários hexadecimais. */
public final class Hex {
    private Hex() {}

    public static String of(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", b[i] & 0xFF));
        }
        return sb.toString();
    }

    public static String of(byte[] b, int from, int to) {
        if (b == null || from >= to) return "";
        byte[] c = new byte[to - from];
        System.arraycopy(b, from, c, 0, c.length);
        return of(c);
    }

    public static String h2(int v) { return String.format("%02X", v & 0xFF); }
    public static String h3(int v) { return String.format("%03X", v & 0xFFF); }
    public static String h4(int v) { return String.format("%04X", v & 0xFFFF); }

    public static boolean isHex(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return false;
        }
        return true;
    }

    /** "22 49 0F" ou "22490F" -> bytes */
    public static byte[] parse(String s) {
        String t = s.replace(" ", "");
        if (t.length() % 2 != 0) throw new IllegalArgumentException("hex ímpar: " + s);
        byte[] out = new byte[t.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(t.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static int u8(byte[] b, int i) { return b[i] & 0xFF; }
    public static int u16(byte[] b, int i) { return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF); }
    public static int u24(byte[] b, int i) { return ((b[i] & 0xFF) << 16) | ((b[i + 1] & 0xFF) << 8) | (b[i + 2] & 0xFF); }

    /** Texto ASCII imprimível (para VIN, números de peça). */
    public static String ascii(byte[] b, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < b.length; i++) {
            int c = b[i] & 0xFF;
            if (c >= 0x20 && c < 0x7F) sb.append((char) c);
        }
        return sb.toString().trim();
    }
}
