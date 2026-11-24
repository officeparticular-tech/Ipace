package pt.ipacediag.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Procura de módulos, leitura e apagamento de erros. */
public final class Diagnostics {

    public interface Progress {
        void step(String msg);
        boolean cancelled();
    }

    public static final class ModuleInfo {
        public final IpaceModules.Module module;
        public boolean responded;
        public String vin = "", software = "", serial = "";
        public List<Dtc> dtcs = new ArrayList<>();
        public String dtcError = null;
        ModuleInfo(IpaceModules.Module m) { this.module = m; }
    }

    private final ElmSession s;

    public Diagnostics(ElmSession s) { this.s = s; }

    /** Pergunta a cada módulo conhecido se está presente (pedidos só de leitura). */
    public List<ModuleInfo> scanModules(Progress p) throws IOException {
        List<ModuleInfo> out = new ArrayList<>();
        for (IpaceModules.Module m : IpaceModules.all()) {
            if (p != null && p.cancelled()) break;
            if (p != null) p.step("A procurar " + m.label() + "…");
            ModuleInfo mi = new ModuleInfo(m);
            UdsResponse r = s.testerPresent(m.tx);
            mi.responded = r.kind == UdsResponse.Kind.POSITIVE || r.kind == UdsResponse.Kind.NEGATIVE;
            if (!mi.responded) {
                byte[] sw = s.readDid(m.tx, 0xF188);
                if (sw != null) { mi.responded = true; mi.software = Hex.ascii(sw, 0); }
            }
            if (mi.responded) {
                if (mi.software.isEmpty()) {
                    byte[] sw = s.readDid(m.tx, 0xF188);
                    if (sw != null) mi.software = Hex.ascii(sw, 0);
                }
                byte[] sn = s.readDid(m.tx, 0xF18C);
                if (sn != null) mi.serial = Hex.ascii(sn, 0);
                if (m.tx == IpaceModules.BECM || m.tx == IpaceModules.BCM) {
                    byte[] vin = s.readDid(m.tx, 0xF190);
                    if (vin != null) mi.vin = Hex.ascii(vin, 0);
                }
            }
            out.add(mi);
        }
        return out;
    }

    /** 0x19 02: lista de erros por máscara de estado. */
    public List<Dtc> readDtcs(int tx) throws IOException {
        int[] masks = {0xFF, 0x2F, 0x0F};
        UdsResponse last = null;
        for (int mask : masks) {
            UdsResponse r = s.request(tx, new byte[]{0x19, 0x02, (byte) mask});
            last = r;
            if (r.ok()) {
                List<Dtc> l = Dtc.parseList(r.payload);
                IpaceModules.Module m = IpaceModules.byTx(tx);
                for (Dtc d : l) { d.moduleTx = tx; d.moduleName = m.code; }
                return l;
            }
            if (r.kind != UdsResponse.Kind.NEGATIVE) break;
        }
        throw new IOException(last == null ? "sem resposta" : last.describe());
    }

    public void readAllDtcs(List<ModuleInfo> mods, Progress p) {
        for (ModuleInfo mi : mods) {
            if (!mi.responded) continue;
            if (p != null && p.cancelled()) break;
            if (p != null) p.step("A ler erros de " + mi.module.label() + "…");
            try {
                mi.dtcs = readDtcs(mi.module.tx);
                mi.dtcError = null;
            } catch (IOException e) {
                mi.dtcError = e.getMessage();
            }
        }
    }

    /** Dados registados no momento do erro (snapshot 19 04) e dados estendidos (19 06), em bruto. */
    public Map<String, String> readDtcDetail(Dtc d) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        UdsResponse snap = s.request(d.moduleTx, new byte[]{0x19, 0x04, (byte) d.b1, (byte) d.b2, (byte) d.ftb, (byte) 0xFF});
        out.put("snapshot", snap.ok() ? Hex.of(snap.payload) : snap.describe());
        UdsResponse ext = s.request(d.moduleTx, new byte[]{0x19, 0x06, (byte) d.b1, (byte) d.b2, (byte) d.ftb, (byte) 0xFF});
        out.put("extended", ext.ok() ? Hex.of(ext.payload) : ext.describe());
        return out;
    }

    /**
     * 0x14 FF FF FF: apaga todos os erros de um módulo.
     * Se o módulo recusar na sessão normal, tenta em sessão estendida (0x10 03)
     * e volta à sessão normal no fim.
     */
    public UdsResponse clearDtcs(int tx) throws IOException {
        byte[] clr = {0x14, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        s.setCanTimeout(0xFA);
        try {
            UdsResponse r = s.request(tx, clr, 10);
            if (r.ok()) return r;
            if (r.kind == UdsResponse.Kind.NEGATIVE && (r.nrc == 0x7F || r.nrc == 0x22 || r.nrc == 0x11)) {
                UdsResponse ses = s.session(tx, 0x03);
                if (ses.ok()) {
                    UdsResponse r2 = s.request(tx, clr, 10);
                    s.session(tx, 0x01);
                    return r2;
                }
            }
            return r;
        } finally {
            s.setCanTimeout(0x64);
        }
    }
}
