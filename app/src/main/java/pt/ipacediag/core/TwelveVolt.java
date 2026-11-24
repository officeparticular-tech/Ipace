package pt.ipacediag.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Avaliação das baterias de 12V do I-Pace.
 *
 * O I-Pace tem duas baterias de 12V (principal/arranque e auxiliar), carregadas
 * pelo conversor DC/DC a partir da bateria de alta tensão. A tomada OBD mede a
 * tensão do circuito de 12V principal. Critérios usados:
 *  - Repouso: só é válido com o carro trancado e as redes adormecidas (a JLR
 *    indica tipicamente 20 min — SSM74274). Tabela aproximada para baterias AGM.
 *  - DC/DC ativo: > 13,5 V; desligado: < 13 V; em ignição/pronto deve estar
 *    entre 13,2 V e 15,2 V (SSM74458).
 * Valores indicativos: a avaliação oficial da JLR usa um testador de condutância
 * (Midtronics), que mede a capacidade real — a tensão sozinha mede sobretudo a carga.
 */
public final class TwelveVolt {
    private TwelveVolt() {}

    public static final class Sample {
        public final long tMs;
        public final double v;
        public Sample(long tMs, double v) { this.tMs = tMs; this.v = v; }
    }

    public static String f(double v) { return String.format(Locale.US, "%.2f V", v); }

    /** Carga aproximada de uma bateria AGM em repouso (≥ 20 min sem carga, ~20 °C). */
    public static int restSocPercent(double v) {
        double[][] t = {{11.80, 0}, {12.00, 25}, {12.30, 50}, {12.60, 75}, {12.80, 100}};
        if (v <= t[0][0]) return 0;
        if (v >= t[t.length - 1][0]) return 100;
        for (int i = 1; i < t.length; i++) {
            if (v <= t[i][0]) {
                double k = (v - t[i - 1][0]) / (t[i][0] - t[i - 1][0]);
                return (int) Math.round(t[i - 1][1] + k * (t[i][1] - t[i - 1][1]));
            }
        }
        return 100;
    }

    public static String restVerdict(double v) {
        if (Double.isNaN(v)) return "Sem leitura.";
        if (v >= 13.3) return "Tensão de " + f(v) + ": o DC/DC ainda está a carregar ou o carro não adormeceu. "
                + "Tranque o carro, espere 20–30 min e repita.";
        int soc = restSocPercent(v);
        String base = "Repouso " + f(v) + " ≈ " + soc + "% de carga. ";
        if (v >= 12.6) return base + "BOM.";
        if (v >= 12.4) return base + "ACEITÁVEL — carga parcial.";
        if (v >= 12.2) return base + "FRACO — bateria descarregada ou a perder capacidade.";
        return base + "MAU — bateria muito descarregada ou danificada; risco de o carro não acordar.";
    }

    public static final class WakeResult {
        public double baseline, min, settled;
        public double secondsBelow12 = 0, secondsToDcdc = -1;
        public String verdict;
    }

    /**
     * Teste de acordar: amostras desde o carro adormecido até ficar "pronto".
     * Mede a queda de tensão quando os módulos e os contactores ligam, antes do DC/DC entrar.
     */
    public static WakeResult analyzeWake(List<Sample> s) {
        WakeResult r = new WakeResult();
        if (s == null || s.size() < 5) { r.verdict = "Poucas amostras."; return r; }
        long t0 = s.get(0).tMs;
        double sum = 0; int n = 0;
        for (Sample x : s) { if (x.tMs - t0 <= 1000) { sum += x.v; n++; } }
        r.baseline = n > 0 ? sum / n : s.get(0).v;
        r.min = Double.MAX_VALUE;
        Sample prev = null;
        for (Sample x : s) {
            if (x.v < r.min) r.min = x.v;
            if (prev != null && prev.v < 12.0) r.secondsBelow12 += (x.tMs - prev.tMs) / 1000.0;
            if (r.secondsToDcdc < 0 && x.v >= 13.5) r.secondsToDcdc = (x.tMs - t0) / 1000.0;
            prev = x;
        }
        r.settled = s.get(s.size() - 1).v;
        double dip = r.baseline - r.min;
        StringBuilder v = new StringBuilder();
        v.append(String.format(Locale.US, "Repouso %.2f V → mínimo %.2f V (queda %.2f V)", r.baseline, r.min, dip));
        if (r.secondsToDcdc >= 0) v.append(String.format(Locale.US, "; DC/DC ligou ao fim de %.1f s", r.secondsToDcdc));
        else v.append("; o DC/DC NÃO chegou a ligar (nunca passou 13,5 V)");
        v.append(". ");
        if (r.min >= 11.5) v.append("BOM: a bateria aguenta bem o arranque dos sistemas.");
        else if (r.min >= 10.5) v.append("ATENÇÃO: queda acentuada — bateria fraca ou com pouca carga.");
        else v.append("MAU: tensão muito baixa ao acordar — módulos podem reiniciar e gerar erros de comunicação.");
        if (r.secondsToDcdc < 0) v.append(" Sem DC/DC as baterias 12V não estão a ser carregadas — verificar conversor DC/DC.");
        r.verdict = v.toString();
        return r;
    }

    public static String dcdcVerdict(List<Sample> s) {
        if (s == null || s.isEmpty()) return "Sem amostras.";
        double min = Double.MAX_VALUE, max = -1, sum = 0;
        for (Sample x : s) { min = Math.min(min, x.v); max = Math.max(max, x.v); sum += x.v; }
        double avg = sum / s.size();
        String base = String.format(Locale.US, "Com o carro ligado: média %.2f V (mín. %.2f, máx. %.2f). ", avg, min, max);
        if (avg >= 13.2 && avg <= 15.2 && min >= 12.8) return base + "OK — o DC/DC está a carregar dentro da gama JLR (13,2–15,2 V).";
        if (avg < 13.2) return base + "BAIXO — o DC/DC não está a carregar corretamente (ou o carro não estava em pronto).";
        return base + "ALTO ou instável — verificar DC/DC e ligações das baterias.";
    }

    public static final class SleepResult {
        public int topUps = 0;
        public double minV = Double.NaN;
        public final List<Double> slopesPerHour = new ArrayList<>();
        public String verdict;
    }

    /**
     * Monitorização com o carro estacionado: deteta recargas automáticas do DC/DC
     * (subidas ≥ 0,5 V acima de 13,3 V) e mede a descida de tensão entre elas.
     * Ignora a primeira meia hora após cada recarga (carga superficial).
     */
    public static SleepResult analyzeSleep(List<Sample> s) {
        SleepResult r = new SleepResult();
        if (s == null || s.size() < 10) { r.verdict = "Registo demasiado curto (mínimo ~10 leituras)."; return r; }
        double min = Double.MAX_VALUE;
        List<Sample> seg = new ArrayList<>();
        long lastTop = s.get(0).v > 13.0 ? s.get(0).tMs : s.get(0).tMs - 3_600_000L;
        for (int i = 0; i < s.size(); i++) {
            Sample x = s.get(i);
            min = Math.min(min, x.v);
            boolean top = x.v > 13.3 && i > 0 && x.v - s.get(i - 1).v >= 0.5;
            if (top) {
                r.topUps++;
                addSlope(r, seg);
                seg.clear();
                lastTop = x.tMs;
                continue;
            }
            if (x.v < 13.0 && x.tMs - lastTop >= 30 * 60_000L) seg.add(x);
        }
        addSlope(r, seg);
        r.minV = min;
        StringBuilder v = new StringBuilder();
        long durMin = (s.get(s.size() - 1).tMs - s.get(0).tMs) / 60_000L;
        v.append(String.format(Locale.US, "Registo de %d min, mínimo %.2f V, %d recarga(s) automática(s) do DC/DC. ", durMin, min, r.topUps));
        if (!r.slopesPerHour.isEmpty()) {
            double worst = 0;
            for (double d : r.slopesPerHour) worst = Math.min(worst, d);
            v.append(String.format(Locale.US, "Descida em repouso: %.3f V/h no pior troço. ", worst));
            if (worst < -0.05) v.append("ELEVADA — consumo parasita ou bateria sem capacidade (a JLR documenta consumos por módulos que não adormecem, ex. TCU — SSM74458).");
            else if (worst < -0.02) v.append("MODERADA — vigiar.");
            else v.append("NORMAL.");
        } else {
            v.append("Sem troço estável suficiente para medir a descida (precisa de ≥ 30 min sem recargas).");
        }
        if (r.topUps >= 4 && durMin < 12 * 60) v.append(" Muitas recargas em pouco tempo: a 12V perde carga depressa.");
        r.verdict = v.toString();
        return r;
    }

    private static void addSlope(SleepResult r, List<Sample> seg) {
        if (seg.size() < 5) return;
        long span = seg.get(seg.size() - 1).tMs - seg.get(0).tMs;
        if (span < 20 * 60_000L) return;
        double n = seg.size(), sx = 0, sy = 0, sxx = 0, sxy = 0;
        long t0 = seg.get(0).tMs;
        for (Sample x : seg) {
            double h = (x.tMs - t0) / 3_600_000.0;
            sx += h; sy += x.v; sxx += h * h; sxy += h * x.v;
        }
        double den = n * sxx - sx * sx;
        if (den == 0) return;
        r.slopesPerHour.add((n * sxy - sx * sy) / den);
    }

    /** Leitura manual (multímetro) da bateria auxiliar, com o carro adormecido. */
    public static String auxManualVerdict(double v) {
        return "Bateria auxiliar (multímetro): " + restVerdict(v);
    }
}
