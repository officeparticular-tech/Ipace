package pt.ipacediag.core;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Gravações contínuas: carregamento, 12V em repouso, acordar, DC/DC. */
public final class Recorder {

    public interface Sink { void line(String csvLine); }

    public interface Listener {
        void live(String summary);
        void event(String msg);
    }

    /** DID extra para gravar em bruto (ex.: encontrado pela descoberta). */
    public static final class Extra {
        public final int tx, did;
        public final String formula; // pode ser null -> grava em bruto
        public Extra(int tx, int did, String formula) { this.tx = tx; this.did = did; this.formula = formula; }
        public String header() { return Hex.h3(tx) + "_" + Hex.h4(did); }
    }

    private final ElmSession s;
    private final Diagnostics diag;
    private static final SimpleDateFormat TS = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    public Recorder(ElmSession s) { this.s = s; this.diag = new Diagnostics(s); }

    private static String now() { synchronized (TS) { return TS.format(new Date()); } }
    private static String n(double v, int dec) {
        return Double.isNaN(v) ? "" : String.format(Locale.US, "%." + dec + "f", v);
    }
    private static void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private double param(String key) {
        IpaceParams.Param p = IpaceParams.get(key);
        try { return p.decodeSafe(s.readDid(p.tx, p.did)); } catch (IOException e) { return Double.NaN; }
    }

    /**
     * Registo durante um carregamento (ou tentativa): 12V + bateria AT a ~1 Hz e
     * erros do BECM, BCCM e DC/DC a cada 10 s, marcando o instante em que aparecem.
     */
    public void chargeLog(List<Extra> extras, Sink sink, Listener l, Diagnostics.Progress p) throws IOException {
        StringBuilder h = new StringBuilder("hora,segundos,v12,soc_pct,pack_v,pack_a,potencia_kw,celula_max_v,celula_min_v,delta_mv,temp_max_c,temp_min_c");
        for (Extra e : extras) h.append(',').append(e.header());
        h.append(",evento");
        sink.line(h.toString());

        int[] dtcModules = {IpaceModules.BECM, IpaceModules.BCCM, IpaceModules.DCDC};
        Set<String> known = new HashSet<>();
        for (int tx : dtcModules) {
            try {
                for (Dtc d : diag.readDtcs(tx)) known.add(d.moduleName + ":" + d.code());
            } catch (IOException ignored) { }
        }
        if (!known.isEmpty()) l.event("Erros já presentes no início: " + String.join(", ", known));

        long t0 = System.currentTimeMillis();
        long lastDtc = t0;
        double minV12 = Double.MAX_VALUE;
        while (p == null || !p.cancelled()) {
            long cycle = System.currentTimeMillis();
            double v12 = s.voltage();
            double soc = param("soc"), pv = param("pack_v"), pa = param("pack_i");
            double cmax = param("cell_max"), cmin = param("cell_min");
            double tmax = param("t_max"), tmin = param("t_min");
            double kw = (Double.isNaN(pv) || Double.isNaN(pa)) ? Double.NaN : pv * pa / 1000.0;
            double delta = (Double.isNaN(cmax) || Double.isNaN(cmin)) ? Double.NaN : (cmax - cmin) * 1000.0;
            StringBuilder row = new StringBuilder();
            row.append(now()).append(',').append(n((cycle - t0) / 1000.0, 1)).append(',')
                    .append(n(v12, 2)).append(',').append(n(soc, 1)).append(',').append(n(pv, 1)).append(',')
                    .append(n(pa, 1)).append(',').append(n(kw, 1)).append(',').append(n(cmax, 3)).append(',')
                    .append(n(cmin, 3)).append(',').append(n(delta, 0)).append(',').append(n(tmax, 1)).append(',').append(n(tmin, 1));
            for (Extra e : extras) {
                byte[] d = s.readDid(e.tx, e.did);
                row.append(',');
                if (d != null) row.append(e.formula != null ? n(Discovery.apply(e.formula, d), 3) : Hex.of(d));
            }
            String event = "";
            if (!Double.isNaN(v12)) {
                if (v12 < minV12) minV12 = v12;
                if (v12 < 12.0) event = "12V BAIXA " + n(v12, 2) + " V";
            }
            if (cycle - lastDtc >= 10_000) {
                lastDtc = cycle;
                for (int tx : dtcModules) {
                    try {
                        for (Dtc d : diag.readDtcs(tx)) {
                            String k = d.moduleName + ":" + d.code();
                            if (known.add(k)) {
                                String msg = "NOVO ERRO " + k + " — " + DtcCatalog.describe(d);
                                event = event.isEmpty() ? msg : event + " | " + msg;
                                l.event(now() + " " + msg + String.format(Locale.US, " (12V nesse momento: %s V)", n(v12, 2)));
                            }
                        }
                    } catch (IOException ignored) { }
                }
            }
            row.append(',').append(event.replace(',', ';'));
            sink.line(row.toString());
            l.live(String.format(Locale.US,
                    "12V: %s V (mín. %s)\nSoC: %s %%   AT: %s V  %s A  %s kW\nCélulas: %s–%s V (Δ %s mV)\nTemp.: %s–%s °C",
                    n(v12, 2), n(minV12 == Double.MAX_VALUE ? Double.NaN : minV12, 2), n(soc, 1), n(pv, 1), n(pa, 1), n(kw, 1),
                    n(cmin, 3), n(cmax, 3), n(delta, 0), n(tmin, 1), n(tmax, 1)));
            sleep(1000 - (System.currentTimeMillis() - cycle));
        }
    }

    /** Tensão 12V a intervalos (repouso / noite inteira). Só usa ATRV: não acorda a rede do carro. */
    public List<TwelveVolt.Sample> voltageLog(long intervalMs, long maxDurationMs, Sink sink, Listener l, Diagnostics.Progress p) throws IOException {
        List<TwelveVolt.Sample> out = new ArrayList<>();
        if (sink != null) sink.line("hora,segundos,v12");
        long t0 = System.currentTimeMillis();
        while ((p == null || !p.cancelled()) && System.currentTimeMillis() - t0 < maxDurationMs) {
            long c = System.currentTimeMillis();
            double v = s.voltage();
            if (!Double.isNaN(v)) {
                out.add(new TwelveVolt.Sample(c, v));
                if (sink != null) sink.line(now() + "," + n((c - t0) / 1000.0, 2) + "," + n(v, 2));
                if (l != null) l.live(String.format(Locale.US, "%.2f V  (%d leituras, %.0f s)", v, out.size(), (c - t0) / 1000.0));
            }
            long until = c + intervalMs;
            while ((p == null || !p.cancelled()) && System.currentTimeMillis() < until) {
                sleep(Math.min(200, until - System.currentTimeMillis()));
            }
        }
        return out;
    }
}
