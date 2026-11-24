package pt.ipacediag.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parâmetros conhecidos do I-Pace (pedido 0x22 + DID).
 * "confirmado" = fórmula usada no Open Vehicle Monitoring System (MIT).
 * "provisório" = endereço referido pela comunidade, fórmula por confirmar.
 */
public final class IpaceParams {
    private IpaceParams() {}

    public interface Decoder { double decode(byte[] d); }

    public static final class Param {
        public final String key, name, unit, group;
        public final int tx, did, decimals;
        public final boolean confirmed;
        public final Decoder dec;
        Param(String key, String name, String unit, int tx, int did, int decimals, boolean confirmed, Decoder dec, String group) {
            this.key = key; this.name = name; this.unit = unit; this.tx = tx; this.did = did;
            this.decimals = decimals; this.confirmed = confirmed; this.dec = dec; this.group = group == null ? "" : group;
        }
        public String format(double v) {
            if (Double.isNaN(v)) return "—";
            return String.format(java.util.Locale.US, "%." + decimals + "f", v) + (unit.isEmpty() ? "" : " " + unit);
        }
        public double decodeSafe(byte[] d) {
            try { return d == null ? Double.NaN : dec.decode(d); } catch (Exception e) { return Double.NaN; }
        }
    }

    private static final List<Param> ALL = new ArrayList<>();

    private static void p(String key, String name, String unit, int tx, int did, int dec, boolean conf, Decoder d) {
        ALL.add(new Param(key, name, unit, tx, did, dec, conf, d, ""));
    }

    static final int BECM = IpaceModules.BECM;

    static {
        p("soc", "Estado de carga (média)", "%", BECM, 0x4910, 1, true, d -> Hex.u16(d, 0) / 100.0);
        p("soc_min", "Estado de carga (mín.)", "%", BECM, 0x4911, 1, false, d -> Hex.u16(d, 0) / 100.0);
        p("soc_max", "Estado de carga (máx.)", "%", BECM, 0x4914, 1, false, d -> Hex.u16(d, 0) / 100.0);
        p("soh_cap", "Saúde da bateria AT — capacidade (média)", "%", BECM, 0x4918, 1, true, d -> Hex.u8(d, 0) / 2.0);
        p("soh_cap_min", "Saúde — capacidade (mín.)", "%", BECM, 0x4919, 1, true, d -> Hex.u8(d, 0) / 2.0);
        p("soh_cap_max", "Saúde — capacidade (máx.)", "%", BECM, 0x491A, 1, true, d -> Hex.u8(d, 0) / 2.0);
        p("soh_pow", "Saúde da bateria AT — potência (média)", "%", BECM, 0x4915, 1, true, d -> Hex.u8(d, 0) / 2.0);
        p("soh_pow_min", "Saúde — potência (mín.)", "%", BECM, 0x4916, 1, true, d -> Hex.u8(d, 0) / 2.0);
        p("soh_pow_max", "Saúde — potência (máx.)", "%", BECM, 0x4917, 1, true, d -> Hex.u8(d, 0) / 2.0);
        p("pack_v", "Tensão da bateria AT", "V", BECM, 0x490F, 2, true, d -> Hex.u16(d, 0) / 100.0);
        p("pack_i", "Corrente da bateria AT", "A", BECM, 0x490C, 1, true, d -> (Hex.u16(d, 0) - 0x8000) / 40.0);
        p("cell_max", "Célula mais alta", "V", BECM, 0x4903, 3, true, d -> Hex.u16(d, 0) / 1000.0);
        p("cell_min", "Célula mais baixa", "V", BECM, 0x4904, 3, true, d -> Hex.u16(d, 0) / 1000.0);
        p("t_max", "Temperatura bateria (máx.)", "°C", BECM, 0x4905, 1, true, d -> Hex.u8(d, 0) / 2.0 - 40);
        p("t_min", "Temperatura bateria (mín.)", "°C", BECM, 0x4906, 1, true, d -> Hex.u8(d, 0) / 2.0 - 40);
        p("t_avg", "Temperatura bateria (média)", "°C", BECM, 0x4907, 1, true, d -> Hex.u8(d, 0) / 2.0 - 40);
        p("regen_max", "Regeneração máxima permitida", "kW", BECM, 0x4913, 1, true, d -> Hex.u16(d, 0) / 100.0);
        p("cool_out", "Líquido refrigeração bateria — saída", "°C", BECM, 0x491B, 1, false, d -> Hex.u8(d, 0) / 2.0 - 40);
        p("cool_in", "Líquido refrigeração bateria — entrada", "°C", BECM, 0x491C, 1, false, d -> Hex.u8(d, 0) / 2.0 - 40);
        for (int i = 0; i < 6; i++) {
            final int n = i + 1;
            p("plate_t" + n, "Temperatura placa " + n, "°C", BECM, 0x492B + i, 1, false, d -> Hex.u8(d, 0) / 2.0 - 40);
        }
        // Introduzidos com o software do recall H441 (comunidade I-Pace): vetor de 36 valores, um por módulo; 0 = saudável
        p("mod_flags", "Módulos sinalizados pelo BECM (de 36)", "", BECM, 0x4A0B, 0, false, d -> {
            int n = 0;
            for (byte b : d) if (b != 0) n++;
            return n;
        });
        p("odo", "Conta-quilómetros", "km", BECM, 0xDD01, 0, true, d -> Hex.u24(d, 0));
        p("cabin_t", "Temperatura habitáculo", "°C", BECM, 0xDD04, 0, true, d -> Hex.u8(d, 0) - 40);
        p("ambient_t", "Temperatura exterior", "°C", IpaceModules.HVAC, 0x9924, 1, true, d -> Hex.u8(d, 0) / 2.0 - 40);
        p("speed", "Velocidade", "km/h", IpaceModules.BCM, 0xDD09, 0, true, d -> Hex.u8(d, 0));
        String[] pos = {"FE", "FD", "TE", "TD"};
        for (int i = 0; i < 4; i++) {
            p("tp_" + pos[i], "Pneu " + pos[i] + " — pressão (bruto)", "", IpaceModules.TPMS, 0x2076 + i, 0, false, d -> Hex.u16(d, 0));
            p("tt_" + pos[i], "Pneu " + pos[i] + " — temperatura", "°C", IpaceModules.TPMS, 0x2A0A + i, 0, true, d -> Hex.u8(d, 0) - 50);
        }
    }

    public static synchronized List<Param> all() { return Collections.unmodifiableList(new ArrayList<>(ALL)); }

    /** Acrescenta ou substitui (pela chave) um parâmetro vindo de um pacote de definições. */
    public static synchronized void register(String key, String name, String unit, int tx, int did, int decimals, boolean confirmed, Decoder d, String group) {
        ALL.removeIf(x -> x.key.equals(key));
        ALL.add(new Param(key, name, unit, tx, did, decimals, confirmed, d, group));
    }

    /** Parâmetros acrescentados por pacotes, de um grupo (ex.: "temp"). */
    public static synchronized List<Param> byGroup(String group) {
        List<Param> out = new ArrayList<>();
        for (Param p : ALL) if (group.equals(p.group)) out.add(p);
        return out;
    }

    public static synchronized Param get(String key) {
        for (Param p : ALL) if (p.key.equals(key)) return p;
        return null;
    }

    /** Conjunto usado no registo de carregamento (rápido de ler). */
    public static String[] chargeSet() {
        return new String[]{"soc", "pack_v", "pack_i", "cell_max", "cell_min", "t_max", "t_min"};
    }
}
