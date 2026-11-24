package pt.ipacediag.core;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Relatório em texto (também convertido em PDF pela app). */
public final class Report {
    private final StringBuilder sb = new StringBuilder();

    public Report(String title) {
        sb.append(title).append('\n');
        sb.append("Gerado em ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append('\n');
        sb.append("App: i-Pace — leitura UDS pela tomada OBD; não altera o carro, exceto ao apagar erros a pedido do utilizador\n\n");
    }

    public Report section(String t) {
        sb.append("\n== ").append(t).append(" ==\n");
        return this;
    }

    public Report line(String s) { sb.append(s).append('\n'); return this; }

    public Report modules(List<Diagnostics.ModuleInfo> mods) {
        section("Módulos e erros");
        int present = 0, withDtc = 0, total = 0;
        for (Diagnostics.ModuleInfo mi : mods) {
            if (!mi.responded) continue;
            present++;
            sb.append(mi.module.label()).append(" — ").append(mi.module.name);
            if (!mi.software.isEmpty()) sb.append(" | software ").append(mi.software);
            if (!mi.serial.isEmpty()) sb.append(" | série ").append(mi.serial);
            if (!mi.vin.isEmpty()) sb.append(" | VIN ").append(mi.vin);
            sb.append('\n');
            if (mi.dtcError != null) sb.append("   (não foi possível ler erros: ").append(mi.dtcError).append(")\n");
            else if (mi.dtcs.isEmpty()) sb.append("   sem erros\n");
            else {
                withDtc++;
                for (Dtc d : mi.dtcs) {
                    total++;
                    sb.append("   ").append(d.describe().replace("\n", "\n   ")).append('\n');
                }
            }
        }
        sb.append(String.format(Locale.US, "\nResumo: %d módulos responderam, %d com erros, %d erros no total.\n", present, withDtc, total));
        int lowV = 0;
        for (Diagnostics.ModuleInfo mi : mods) for (Dtc d : mi.dtcs) if (DtcCatalog.isLowVoltageEvent(d)) lowV++;
        if (lowV >= 2) sb.append("ALERTA 12V: ").append(lowV)
                .append(" módulos registaram tensão de alimentação anormal (U3003). Isto é típico de uma queda da 12V — ")
                .append("a JLR indica que os problemas do circuito 12V devem ser resolvidos antes de diagnosticar a bateria de alta tensão (SSM74274).\n");
        return this;
    }

    public Report values(Map<String, String> values) {
        for (Map.Entry<String, String> e : values.entrySet()) sb.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        return this;
    }

    public String text() { return sb.toString(); }
}
