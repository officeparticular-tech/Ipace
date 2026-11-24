package pt.ipacediag.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Executa um procedimento passo a passo.
 * Salvaguardas:
 *  - só serviços UDS da lista permitida (sem acesso de segurança, escrita de dados, programação ou reset de módulos);
 *  - rotinas (0x31) só em procedimentos verificados ou associados ao VIN deste carro;
 *  - cada passo que altera o carro pede confirmação explícita ao utilizador.
 */
public final class ProcedureRunner {

    public static final int INFO = 0, OK = 1, WARN = 2, BAD = 3;

    public interface Ui {
        void log(String text, int level);
        /** Bloqueia até o utilizador responder. */
        boolean confirm(String title, String text);
        void saveReport(String label) throws Exception;
        void onScan(List<Diagnostics.ModuleInfo> mods);
        boolean cancelled();
    }

    public interface Lookup { Procedure get(String id); }

    private final ElmSession s;
    private final Ui ui;
    private final Lookup lookup;
    private String vin = "";
    private List<Diagnostics.ModuleInfo> scan;
    private int depth = 0;

    public ProcedureRunner(ElmSession s, Ui ui, Lookup lookup) { this.s = s; this.ui = ui; this.lookup = lookup; }

    /** Motivo pelo qual um pedido UDS é proibido nesta app, ou null se permitido. */
    public static String forbidden(byte[] req) {
        if (req.length == 0) return "pedido vazio";
        int sid = req[0] & 0xFF;
        switch (sid) {
            case 0x22: case 0x19: case 0x3E: case 0x14: return null;
            case 0x10: {
                int t = req.length > 1 ? req[1] & 0x7F : 0;
                return (t == 0x01 || t == 0x03) ? null : "sessão 0x" + Hex.h2(t) + " não permitida (só normal e estendida)";
            }
            case 0x31: {
                int sub = req.length > 1 ? req[1] & 0x7F : 0;
                return (sub == 0x01 || sub == 0x03) ? null : "subfunção de rotina não permitida";
            }
            case 0x27: return "acesso de segurança (0x27) não é permitido nesta app";
            case 0x2E: return "escrita de dados (0x2E) não é permitida nesta app";
            case 0x2F: return "controlo de atuadores (0x2F) não é permitido nesta app";
            case 0x11: return "reinício de módulos (0x11) não é permitido nesta app";
            case 0x28: return "controlo de comunicações (0x28) não é permitido nesta app";
            case 0x85: return "controlo de registo de erros (0x85) não é permitido nesta app";
            case 0x34: case 0x35: case 0x36: case 0x37: case 0x3D:
                return "programação de módulos não é permitida nesta app";
            default: return "serviço 0x" + Hex.h2(sid) + " não permitido nesta app";
        }
    }

    public String vin() { return vin; }

    public boolean run(Procedure p) {
        try {
            if (depth == 0) {
                byte[] v = s.readDid(IpaceModules.BECM, 0xF190);
                if (v == null) v = s.readDid(IpaceModules.BCM, 0xF190);
                vin = v == null ? "" : Hex.ascii(v, 0);
                ui.log("VIN lido: " + (vin.isEmpty() ? "não disponível" : vin), INFO);
            }
            String blocked = p.blockedReason(vin);
            if (blocked != null) { ui.log(p.title + ": " + blocked, WARN); return false; }
            ui.log("▶ " + p.title, INFO);
            for (int i = 0; i < p.steps.size(); i++) {
                if (ui.cancelled()) { ui.log("Interrompido pelo utilizador.", WARN); return false; }
                Procedure.Step st = p.steps.get(i);
                boolean ok = step(p, st, i + 1);
                if (!ok) {
                    if (st.required) { ui.log("Passo obrigatório falhou — procedimento terminado.", BAD); return false; }
                    if (st.type.equals("uds")) { ui.log("Passo falhou — procedimento terminado por segurança.", BAD); return false; }
                }
            }
            ui.log("✔ " + p.title + " concluído.", OK);
            return true;
        } catch (Exception e) {
            ui.log("Erro: " + e.getMessage(), BAD);
            return false;
        }
    }

    private boolean step(Procedure p, Procedure.Step st, int n) throws Exception {
        String pre = n + ". ";
        switch (st.type) {
            case "info":
                ui.log(pre + st.text, INFO);
                return true;
            case "confirm":
                if (ui.confirm(st.label.isEmpty() ? "Confirmar" : st.label, st.text)) { ui.log(pre + "Confirmado: " + firstLine(st.text), OK); return true; }
                ui.log(pre + "Não confirmado.", WARN);
                return false;
            case "wait": {
                ui.log(pre + "A aguardar " + (int) st.seconds + " s…", INFO);
                long end = System.currentTimeMillis() + (long) (st.seconds * 1000);
                while (System.currentTimeMillis() < end && !ui.cancelled()) Thread.sleep(200);
                return true;
            }
            case "voltage": {
                double sum = 0; int k = 0;
                for (int i = 0; i < 3; i++) { double v = s.voltage(); if (!Double.isNaN(v)) { sum += v; k++; } Thread.sleep(300); }
                double v = k == 0 ? Double.NaN : sum / k;
                boolean ok = !Double.isNaN(v) && (Double.isNaN(st.min) || v >= st.min) && (Double.isNaN(st.max) || v <= st.max);
                String range = (Double.isNaN(st.min) ? "" : String.format(Locale.US, "≥ %.1f V", st.min))
                        + (Double.isNaN(st.max) ? "" : String.format(Locale.US, " e ≤ %.1f V", st.max));
                ui.log(pre + (st.label.isEmpty() ? "Tensão 12V" : st.label) + String.format(Locale.US, ": %.1f V ", v)
                        + (range.isEmpty() ? "" : "(esperado " + range.trim() + ") ") + (ok ? "OK" : "FORA DO ESPERADO"), ok ? OK : WARN);
                return ok;
            }
            case "dcdc": {
                List<TwelveVolt.Sample> l = new ArrayList<>();
                long end = System.currentTimeMillis() + (long) ((st.seconds > 0 ? st.seconds : 15) * 1000);
                while (System.currentTimeMillis() < end && !ui.cancelled()) {
                    double v = s.voltage();
                    if (!Double.isNaN(v)) l.add(new TwelveVolt.Sample(System.currentTimeMillis(), v));
                    Thread.sleep(500);
                }
                String verdict = TwelveVolt.dcdcVerdict(l);
                boolean ok = verdict.contains("OK");
                ui.log(pre + verdict, ok ? OK : WARN);
                return ok;
            }
            case "dtc_scan": {
                ui.log(pre + "A ler erros de todos os módulos…", INFO);
                Diagnostics d = new Diagnostics(s);
                Diagnostics.Progress pr = new Diagnostics.Progress() {
                    public void step(String m) { }
                    public boolean cancelled() { return ui.cancelled(); }
                };
                scan = d.scanModules(pr);
                d.readAllDtcs(scan, pr);
                int n2 = 0, mods = 0, lowV = 0;
                for (Diagnostics.ModuleInfo mi : scan) {
                    if (!mi.responded) continue;
                    mods++;
                    n2 += mi.dtcs.size();
                    for (Dtc x : mi.dtcs) if (DtcCatalog.isLowVoltageEvent(x)) lowV++;
                }
                ui.onScan(scan);
                ui.log(pre + mods + " módulos, " + n2 + " erro(s)" + (lowV > 0 ? ", " + lowV + " de tensão 12V (U3003)" : "") + ".", n2 == 0 ? OK : WARN);
                return true;
            }
            case "save_report":
                ui.saveReport(st.label.isEmpty() ? p.id : st.label);
                ui.log(pre + "Relatório guardado (" + (st.label.isEmpty() ? p.id : st.label) + ").", OK);
                return true;
            case "clear_dtcs": {
                if (scan == null) { ui.log(pre + "Sem leitura de erros anterior — passo ignorado.", WARN); return true; }
                List<Diagnostics.ModuleInfo> targets = new ArrayList<>();
                List<String> kept = new ArrayList<>();
                for (Diagnostics.ModuleInfo mi : scan) {
                    if (mi.dtcs.isEmpty()) continue;
                    boolean hv = false;
                    for (Dtc x : mi.dtcs) if (DtcCatalog.isHighVoltage(x)) hv = true;
                    if (hv && st.target.equals("all_except_hv")) kept.add(mi.module.code); else targets.add(mi);
                }
                if (targets.isEmpty()) { ui.log(pre + "Nada para apagar" + (kept.isEmpty() ? "." : " (mantidos: " + String.join(", ", kept) + ")."), OK); return true; }
                StringBuilder sb = new StringBuilder();
                for (Diagnostics.ModuleInfo mi : targets) sb.append(mi.module.code).append(' ');
                String msg = (st.text.isEmpty() ? "Apagar os erros destes módulos?" : st.text) + "\n\n" + sb.toString().trim()
                        + (kept.isEmpty() ? "" : "\n\nNÃO serão apagados (erros de alta tensão, prova para garantia): " + String.join(", ", kept));
                if (!ui.confirm("Apagar erros", msg)) { ui.log(pre + "Apagar erros: cancelado.", WARN); return true; }
                Diagnostics d = new Diagnostics(s);
                for (Diagnostics.ModuleInfo mi : targets) {
                    UdsResponse r = d.clearDtcs(mi.module.tx);
                    ui.log("   " + mi.module.code + ": " + (r.ok() ? "apagados" : r.describe()), r.ok() ? OK : WARN);
                }
                return true;
            }
            case "run": {
                Procedure sub = lookup == null ? null : lookup.get(st.target);
                if (sub == null) { ui.log(pre + "Procedimento \"" + st.target + "\" não existe nesta versão das definições.", WARN); return true; }
                String why = sub.blockedReason(vin);
                if (why != null) { ui.log(pre + sub.title + ": " + why, WARN); return true; }
                if (depth > 2) { ui.log(pre + "Demasiados procedimentos encadeados.", BAD); return false; }
                depth++;
                try { return run(sub); } finally { depth--; }
            }
            case "uds": {
                byte[] req = Hex.parse(st.req);
                String why = forbidden(req);
                if (why != null) { ui.log(pre + "Bloqueado: " + why, BAD); return false; }
                int sid = req[0] & 0xFF;
                if (sid == 0x31 && p.blockedReason(vin) != null) { ui.log(pre + "Bloqueado: " + p.blockedReason(vin), BAD); return false; }
                if (sid == 0x31 || sid == 0x14) {
                    String what = st.label.isEmpty() ? (sid == 0x31 ? "Executar rotina" : "Apagar erros") : st.label;
                    if (!ui.confirm(what, what + "\nMódulo " + IpaceModules.byTx(st.tx).label() + ", pedido " + st.req
                            + "\n\nEsta ação altera o estado do módulo. Continuar?")) {
                        ui.log(pre + what + ": cancelado pelo utilizador.", WARN);
                        return false;
                    }
                }
                if (sid == 0x31 || sid == 0x14) s.setCanTimeout(0xFA);
                UdsResponse r;
                try { r = s.request(st.tx, req, 20); } finally { if (sid == 0x31 || sid == 0x14) s.setCanTimeout(0x64); }
                boolean ok = r.ok();
                if (ok && !st.expect.isEmpty()) ok = Hex.of(r.payload).replace(" ", "").startsWith(st.expect.replace(" ", ""));
                ui.log(pre + (st.label.isEmpty() ? "Pedido " + st.req : st.label) + " → " + r.describe(), ok ? OK : BAD);
                return ok;
            }
            default:
                ui.log(pre + "Tipo de passo desconhecido \"" + st.type + "\" (precisa de versão mais recente da app).", WARN);
                return true;
        }
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}
