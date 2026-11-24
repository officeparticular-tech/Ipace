package pt.ipacediag.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Descoberta de parâmetros: percorre uma gama de identificadores (DID) com o
 * serviço 0x22 (só leitura) e regista os que respondem. Serve para encontrar
 * valores ainda não documentados (ex.: resistência de isolamento, tensão 12V
 * vista por cada módulo, dados por célula).
 */
public final class Discovery {

    public static final class Found {
        public final int tx, did;
        public final byte[] data;
        public Found(int tx, int did, byte[] data) { this.tx = tx; this.did = did; this.data = data; }
        public String csv() {
            return Hex.h3(tx) + "," + Hex.h4(did) + "," + data.length + "," + Hex.of(data);
        }
    }

    public static final class Candidate {
        public final Found f;
        public final String formula;
        public final double value;
        Candidate(Found f, String formula, double value) { this.f = f; this.formula = formula; this.value = value; }
        public String describe() {
            return String.format(Locale.US, "%s DID %s: %.2f V (fórmula %s, bruto %s)",
                    IpaceModules.byTx(f.tx).label(), Hex.h4(f.did), value, formula, Hex.of(f.data));
        }
    }

    private final ElmSession s;
    public Discovery(ElmSession s) { this.s = s; }

    public List<Found> sweep(int tx, int from, int to, Diagnostics.Progress p) throws IOException {
        List<Found> out = new ArrayList<>();
        int misses = 0;
        for (int did = from; did <= to; did++) {
            if (p != null && p.cancelled()) break;
            if (p != null && (did & 0x0F) == 0) p.step(IpaceModules.byTx(tx).label() + " DID " + Hex.h4(did) + " — " + out.size() + " encontrados");
            UdsResponse r = s.readDidRaw(tx, did);
            if (r.ok() && r.payload.length >= 3 && Hex.u16(r.payload, 1) == did) {
                out.add(new Found(tx, did, r.dataAfter(3)));
                misses = 0;
            } else if (r.kind == UdsResponse.Kind.NO_RESPONSE) {
                // módulo não responde: parar cedo para não perder tempo
                if (++misses >= 8 && out.isEmpty()) break;
            } else {
                misses = 0;
            }
        }
        return out;
    }

    /** Interpretações habituais de 1–2 bytes como tensão. */
    public static List<Candidate> voltageCandidates(List<Found> found, double refV, double tol) {
        List<Candidate> out = new ArrayList<>();
        for (Found f : found) {
            byte[] d = f.data;
            if (d.length == 0 || d.length > 4) continue;
            List<Object[]> tries = new ArrayList<>();
            int a = Hex.u8(d, 0);
            tries.add(new Object[]{"A/10", a / 10.0});
            tries.add(new Object[]{"A/16", a / 16.0});
            tries.add(new Object[]{"A*0.1+3", a * 0.1 + 3});
            tries.add(new Object[]{"A/8", a / 8.0});
            if (d.length >= 2) {
                int ab = Hex.u16(d, 0);
                tries.add(new Object[]{"AB/1000", ab / 1000.0});
                tries.add(new Object[]{"AB/100", ab / 100.0});
                tries.add(new Object[]{"AB/256", ab / 256.0});
                tries.add(new Object[]{"AB/512", ab / 512.0});
                tries.add(new Object[]{"AB/1024", ab / 1024.0});
                tries.add(new Object[]{"AB*0.025", ab * 0.025});
            }
            for (Object[] t : tries) {
                double v = (Double) t[1];
                if (Math.abs(v - refV) <= tol) { out.add(new Candidate(f, (String) t[0], v)); break; }
            }
        }
        return out;
    }

    public static double apply(String formula, byte[] d) {
        if (d == null || d.length == 0) return Double.NaN;
        int a = Hex.u8(d, 0);
        int ab = d.length >= 2 ? Hex.u16(d, 0) : -1;
        switch (formula) {
            case "A/10": return a / 10.0;
            case "A/16": return a / 16.0;
            case "A*0.1+3": return a * 0.1 + 3;
            case "A/8": return a / 8.0;
            case "AB/1000": return ab < 0 ? Double.NaN : ab / 1000.0;
            case "AB/100": return ab < 0 ? Double.NaN : ab / 100.0;
            case "AB/256": return ab < 0 ? Double.NaN : ab / 256.0;
            case "AB/512": return ab < 0 ? Double.NaN : ab / 512.0;
            case "AB/1024": return ab < 0 ? Double.NaN : ab / 1024.0;
            case "AB*0.025": return ab < 0 ? Double.NaN : ab * 0.025;
            default: return Double.NaN;
        }
    }
}
