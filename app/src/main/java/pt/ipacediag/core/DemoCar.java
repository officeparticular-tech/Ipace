package pt.ipacediag.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * I-Pace SIMULADO para o modo demonstração. Responde como um OBDLink MX+
 * ligado a um I-Pace, com estados que o utilizador pode mudar:
 * adormecido, acordado, pronto (DC/DC a carregar a 12V) e carregamento rápido DC
 * — este último reproduz uma falha: queda da 12V e erro de isolamento ao fim de ~20 s.
 * Todos os valores são fictícios.
 */
public final class DemoCar implements ObdTransport {

    public enum State {
        ASLEEP("Adormecido (trancado)"), AWAKE("Acordado (portas abertas)"),
        READY("Pronto a conduzir"), DC_CHARGING("Carregamento rápido DC");
        public final String label;
        State(String l) { label = l; }
    }

    private volatile State state = State.ASLEEP;
    private long stateSince = System.currentTimeMillis();
    private int tx = 0x7DF;
    private boolean open = true;
    private final Set<Integer> extended = new HashSet<>();
    private final Map<Integer, List<int[]>> dtcs = new LinkedHashMap<>();
    private double soc = 63.4;
    private double v12Rest = 12.42;
    private boolean faultInjected = false;
    private final Set<Integer> absent = new HashSet<>();
    private final java.util.Random rnd = new java.util.Random(7);

    public DemoCar() {
        // erros iniciais (fictícios) — [b1, b2, ftb, estado]
        add(IpaceModules.BECM, 0x0A, 0xA6, 0x00, 0x08);   // P0AA6-00 confirmado (histórico)
        add(IpaceModules.BCM, 0xF0, 0x03, 0x16, 0x08);    // U3003-16
        add(IpaceModules.GWM, 0xF0, 0x03, 0x16, 0x08);    // U3003-16
        add(IpaceModules.HVAC, 0x9D, 0xC5, 0x93, 0x08);   // B1DC5-93
        absent.add(0x7B2); absent.add(0x7C4); absent.add(0x7C6); absent.add(0x785);
    }

    private void add(int tx, int a, int b, int c, int st) {
        dtcs.computeIfAbsent(tx, k -> new ArrayList<>()).add(new int[]{a, b, c, st});
    }

    public State state() { return state; }

    public synchronized void setState(State s) {
        if (s == state) return;
        state = s;
        stateSince = System.currentTimeMillis();
        if (s != State.DC_CHARGING) faultInjected = false;
    }

    private double secs() { return (System.currentTimeMillis() - stateSince) / 1000.0; }

    /** Tensão 12V conforme o estado (inclui a queda ao acordar e a falha em DC). */
    public double v12() {
        double t = secs();
        double noise = (rnd.nextDouble() - 0.5) * 0.04;
        switch (state) {
            case ASLEEP:
                return Math.max(11.9, v12Rest - t / 3600.0 * 0.03) + noise / 2;
            case AWAKE:
                return 12.32 + noise;
            case READY:
                if (t < 0.6) return 12.35 - t * 2 + noise;
                if (t < 1.6) return 10.95 + (t - 0.6) * 0.9 + noise;   // queda ao ligar contactores/bombas
                if (t < 2.8) return 12.1 + noise;
                return 14.15 + noise;                                    // DC/DC ativo
            case DC_CHARGING:
                if (t < 18) return 14.05 + noise;
                if (t < 24) return 11.35 + noise;                        // queda da 12V durante o handshake
                return 12.25 + noise;                                    // DC/DC desligado após a falha
            default:
                return 12.4;
        }
    }

    private boolean charging() { return state == State.DC_CHARGING && secs() < 18; }

    private void maybeInjectFault() {
        if (state == State.DC_CHARGING && secs() >= 20 && !faultInjected) {
            faultInjected = true;
            add(IpaceModules.BECM, 0x0A, 0xA6, 0x00, 0x2F);   // P0AA6-00 ativo agora
            add(IpaceModules.BCCM, 0xF0, 0x03, 0x16, 0x09);   // U3003-16
            add(IpaceModules.DCDC, 0xF0, 0x03, 0x16, 0x09);
        }
    }

    private static String frames(int rx, byte[] p) {
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

    private static byte[] cat(int... parts) {
        byte[] b = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) b[i] = (byte) parts[i];
        return b;
    }

    private static byte[] cat(byte[] head, byte[] tail) {
        byte[] b = new byte[head.length + tail.length];
        System.arraycopy(head, 0, b, 0, head.length);
        System.arraycopy(tail, 0, b, head.length, tail.length);
        return b;
    }

    private static byte[] u16(double v) { int x = (int) Math.round(v) & 0xFFFF; return cat(x >> 8, x & 0xFF); }

    /** Valor de um DID simulado, ou null se o módulo não o tiver. */
    private byte[] did(int tx, int did) {
        boolean awake = state != State.ASLEEP;
        double v12 = v12();
        if (did == 0xF188) return ("DEMO-" + Hex.h3(tx) + "-SW01").getBytes();
        if (did == 0xF18C) return ("SIM" + Hex.h3(tx) + "000123").getBytes();
        if (did == 0xF190 && (tx == IpaceModules.BECM || tx == IpaceModules.BCM)) return "SADHA2B1XK1DEMO00".getBytes();
        if (did == 0xDD02 && (tx == IpaceModules.BCM || tx == IpaceModules.GWM)) return cat((int) Math.round(v12 * 10));
        if (did == 0xD111 && tx == IpaceModules.DCDC) return u16(v12 * 1000);
        if (tx == IpaceModules.BECM) {
            double current = charging() ? 165 + rnd.nextInt(10) : (state == State.READY ? -4 : -0.3);
            if (charging()) soc = Math.min(100, soc + 0.02);
            double packV = 360 + soc * 0.6 + current * 0.04;
            double cellMax = packV / 108 + 0.006, cellMin = packV / 108 - 0.012;
            double tMax = charging() ? 31.5 : 21.0, tMin = charging() ? 26.0 : 19.0;
            switch (did) {
                case 0x4910: return u16(soc * 100);
                case 0x4911: return u16((soc - 0.6) * 100);
                case 0x4914: return u16((soc + 0.5) * 100);
                case 0x4918: return cat(172);   // 86.0 %
                case 0x4919: return cat(166);
                case 0x491A: return cat(178);
                case 0x4915: return cat(180);
                case 0x4916: return cat(174);
                case 0x4917: return cat(186);
                case 0x490F: return u16(packV * 100);
                case 0x490C: return u16(current * 40 + 0x8000);
                case 0x4903: return u16(cellMax * 1000);
                case 0x4904: return u16(cellMin * 1000);
                case 0x4905: return cat((int) ((tMax + 40) * 2));
                case 0x4906: return cat((int) ((tMin + 40) * 2));
                case 0x4907: return cat((int) (((tMax + tMin) / 2 + 40) * 2));
                case 0x4913: return u16(6000);
                case 0x491B: return cat((int) ((tMin + 2 + 40) * 2));
                case 0x491C: return cat((int) ((tMin + 40) * 2));
                case 0x4A0B: { byte[] v = new byte[36]; v[13] = 1; return v; }
                case 0x4D01: return cat((int) ((46.0 + 40) * 2)); // motor diant. (simulado)
                case 0x4D02: return cat((int) ((48.5 + 40) * 2)); // motor tras. (simulado)
                case 0x4D03: return cat((int) ((53.0 + 40) * 2)); // DC/DC (simulado)
                case 0xDD01: return cat(0x02, 0x1B, 0x2C); // 138 028 km
                case 0xDD04: return cat(awake ? 59 : 54);
                default:
                    if (did >= 0x492B && did <= 0x4930) return cat((int) ((tMin + (did - 0x492B) * 0.5 + 40) * 2));
                    return null;
            }
        }
        if (tx == IpaceModules.HVAC && did == 0x9924) return cat((int) ((13.5 + 40) * 2));
        if (tx == IpaceModules.BCM && did == 0xDD09) return cat(0);
        if (tx == IpaceModules.TPMS) {
            if (did >= 0x2076 && did <= 0x2079) return u16(260 + (did - 0x2076) * 3);
            if (did >= 0x2A0A && did <= 0x2A0D) return cat(50 + 14);
        }
        return null;
    }

    @Override
    public synchronized String command(String c, long timeoutMs) {
        try { Thread.sleep(12 + rnd.nextInt(18)); } catch (InterruptedException ignored) { }
        maybeInjectFault();
        if (c.equals("ATZ")) return "ELM327 v1.4b";
        if (c.equals("STI")) return "Simulador OBDLink (modo demonstração)";
        if (c.equals("ATRV")) return String.format(Locale.US, "%.1fV", v12());
        if (c.startsWith("ATSH")) { tx = Integer.parseInt(c.substring(4), 16); return "OK"; }
        if (c.startsWith("AT")) return "OK";
        int rx = tx + 8;
        boolean known = false;
        for (IpaceModules.Module m : IpaceModules.all()) if (m.tx == tx) known = true;
        if (!known || absent.contains(tx)) return "NO DATA";
        byte[] req;
        try { req = Hex.parse(c); } catch (Exception e) { return "?"; }
        int sid = req[0] & 0xFF;
        switch (sid) {
            case 0x3E: return frames(rx, cat(0x7E, 0x00));
            case 0x10: {
                int t = req.length > 1 ? req[1] & 0xFF : 1;
                if (t == 3) extended.add(tx); else extended.remove(tx);
                return frames(rx, cat(0x50, t, 0x00, 0x32, 0x01, 0xF4));
            }
            case 0x22: {
                if (req.length < 3) return frames(rx, cat(0x7F, 0x22, 0x13));
                int did = Hex.u16(req, 1);
                byte[] d = did(tx, did);
                if (d == null) return frames(rx, cat(0x7F, 0x22, 0x31));
                return frames(rx, cat(cat(0x62, did >> 8, did & 0xFF), d));
            }
            case 0x19: {
                int sub = req.length > 1 ? req[1] & 0xFF : 0;
                if (sub == 0x02) {
                    List<int[]> l = dtcs.getOrDefault(tx, new ArrayList<>());
                    byte[] p = cat(0x59, 0x02, 0xFF);
                    for (int[] d : l) p = cat(p, cat(d[0], d[1], d[2], d[3]));
                    return frames(rx, p);
                }
                if (sub == 0x04) {
                    // snapshot fictício: DID DD01 (km) + DD02 (12V)
                    byte[] p = cat(0x59, 0x04, req[2] & 0xFF, req[3] & 0xFF, req[4] & 0xFF, 0x08, 0x01, 0x02,
                            0xDD, 0x01, 0x02, 0x1B, 0x20, 0xDD, 0x02, 0x71);
                    return frames(rx, p);
                }
                if (sub == 0x06) return frames(rx, cat(0x59, 0x06, req[2] & 0xFF, req[3] & 0xFF, req[4] & 0xFF, 0x08, 0x01, 0x05));
                return frames(rx, cat(0x7F, 0x19, 0x12));
            }
            case 0x31: {
                if (req.length < 4) return frames(rx, cat(0x7F, 0x31, 0x13));
                int rid = Hex.u16(req, 2);
                if (tx == IpaceModules.BCM && rid == 0xDE40) {
                    if (!extended.contains(tx)) return frames(rx, cat(0x7F, 0x31, 0x7F));
                    return frames(rx, cat(0x71, req[1] & 0xFF, 0xDE, 0x40, 0x00));
                }
                return frames(rx, cat(0x7F, 0x31, 0x31));
            }
            case 0x14: {
                if (tx == IpaceModules.BECM && !extended.contains(tx)) return frames(rx, cat(0x7F, 0x14, 0x22));
                dtcs.remove(tx);
                return frames(rx, cat(0x54));
            }
            default:
                return frames(rx, cat(0x7F, sid, 0x11));
        }
    }

    /** Modo escuta simulado: uma "máquina de oficina" faz o registo da bateria no BCM. */
    @Override
    public void monitor(String cmd, Recorder.Sink sink, java.util.function.BooleanSupplier stop) {
        String[] seq = {
                "7E4 02 3E 00 00 00 00 00 00", "7EC 02 7E 00 00 00 00 00 00",
                "726 03 22 F1 90 00 00 00 00", "72E 10 14 62 F1 90 53 41 44",
                "72E 21 48 41 32 42 31 58 4B", "72E 22 31 44 45 4D 4F 30 30",
                "726 03 19 02 FF 00 00 00 00", "72E 03 59 02 FF 00 00 00 00",
                "726 02 10 03 00 00 00 00 00", "72E 06 50 03 00 32 01 F4 00",
                "726 04 31 01 DE 40 00 00 00", "72E 03 7F 31 78 00 00 00 00",
                "72E 05 71 01 DE 40 00 00 00",
                "726 02 10 01 00 00 00 00 00", "72E 06 50 01 00 32 01 F4 00"
        };
        int i = 0;
        while (!stop.getAsBoolean()) {
            try { Thread.sleep(i < seq.length ? 250 : 400); } catch (InterruptedException e) { break; }
            if (i < seq.length) sink.line(seq[i++]);
            else sink.line("7E4 02 3E 00 00 00 00 00 00");
        }
    }

    @Override public void close() { open = false; }
    @Override public boolean isOpen() { return open; }
}
