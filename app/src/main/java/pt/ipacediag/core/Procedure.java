package pt.ipacediag.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Procedimento guiado, definido em JSON (pacote de definições).
 * Tipos de passo: info, confirm, voltage, dcdc, dtc_scan, save_report, uds, wait, clear_dtcs, run.
 */
public final class Procedure {

    public static final class Step {
        public String type = "info", text = "", label = "";
        public int tx = -1;
        public String req = "", expect = "";
        public double min = Double.NaN, max = Double.NaN, seconds = 0;
        public String target = "";      // clear_dtcs: "all_except_hv" | run: id de outro procedimento
        public boolean required = false;

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type);
            if (!text.isEmpty()) m.put("text", text);
            if (!label.isEmpty()) m.put("label", label);
            if (tx >= 0) m.put("tx", Hex.h3(tx));
            if (!req.isEmpty()) m.put("req", req);
            if (!expect.isEmpty()) m.put("expect", expect);
            if (!Double.isNaN(min)) m.put("min", min);
            if (!Double.isNaN(max)) m.put("max", max);
            if (seconds > 0) m.put("seconds", seconds);
            if (!target.isEmpty()) m.put("target", target);
            if (required) m.put("required", true);
            return m;
        }
    }

    public String id = "", title = "", description = "", category = "", source = "", note = "";
    public boolean available = true;
    /** true = rotina confirmada por fonte fiável para o I-Pace. */
    public boolean verified = false;
    /** O procedimento só corre nestes VIN (ex.: rotina capturada no próprio carro). Vazio = qualquer. */
    public List<String> vins = new ArrayList<>();
    /** A ferramenta original usou acesso de segurança (0x27): não é reproduzível por esta app. */
    public boolean needsSecurity = false;
    public final List<Step> steps = new ArrayList<>();

    public boolean writesToCar() {
        for (Step s : steps) {
            if (!s.type.equals("uds") || s.req.isEmpty()) continue;
            int sid = Hex.parse(s.req)[0] & 0xFF;
            if (sid == 0x31 || sid == 0x14) return true;
        }
        return false;
    }

    public boolean hasRoutine() {
        for (Step s : steps) if (s.type.equals("uds") && !s.req.isEmpty() && (Hex.parse(s.req)[0] & 0xFF) == 0x31) return true;
        return false;
    }

    /** Pode correr neste carro? Devolve null se sim, ou o motivo. */
    public String blockedReason(String vin) {
        if (!available) return note.isEmpty() ? "Procedimento ainda não disponível." : note;
        if (needsSecurity) return "A ferramenta original usou acesso de segurança (serviço 0x27). Esta app não faz acesso de segurança, por isso não pode reproduzir esta rotina.";
        if (!vins.isEmpty()) {
            boolean ok = false;
            for (String v : vins) if (v.equalsIgnoreCase(vin == null ? "" : vin.trim())) ok = true;
            if (!ok) return "Este procedimento foi definido para outro veículo (VIN " + String.join(", ", vins) + ").";
        } else if (hasRoutine() && !verified) {
            return "Rotina não verificada e sem VIN associado: bloqueada por segurança.";
        }
        return null;
    }

    public static Procedure fromJson(Map<String, Object> m) {
        Procedure p = new Procedure();
        p.id = MiniJson.str(m, "id", "");
        p.title = MiniJson.str(m, "title", p.id);
        p.description = MiniJson.str(m, "description", "");
        p.category = MiniJson.str(m, "category", "");
        p.source = MiniJson.str(m, "source", "");
        p.note = MiniJson.str(m, "note", "");
        p.available = MiniJson.bool(m, "available", true);
        p.verified = MiniJson.bool(m, "verified", false);
        p.needsSecurity = MiniJson.bool(m, "needs_security", false);
        for (Object v : MiniJson.list(m.get("vins"))) if (v != null) p.vins.add(v.toString().trim());
        for (Object o : MiniJson.list(m.get("steps"))) {
            Map<String, Object> sm = MiniJson.obj(o);
            Step s = new Step();
            s.type = MiniJson.str(sm, "type", "info");
            s.text = MiniJson.str(sm, "text", "");
            s.label = MiniJson.str(sm, "label", "");
            s.tx = MiniJson.hex(sm, "tx", -1);
            s.req = MiniJson.str(sm, "req", "").toUpperCase().trim();
            s.expect = MiniJson.str(sm, "expect", "").toUpperCase().trim();
            s.min = MiniJson.num(sm, "min", Double.NaN);
            s.max = MiniJson.num(sm, "max", Double.NaN);
            s.seconds = MiniJson.num(sm, "seconds", 0);
            s.target = MiniJson.str(sm, "target", "");
            s.required = MiniJson.bool(sm, "required", false);
            p.steps.add(s);
        }
        if (p.id.isEmpty()) throw new IllegalArgumentException("procedimento sem \"id\"");
        for (Step s : p.steps) {
            if (!s.type.equals("uds")) continue;
            if (s.tx < 0 || s.req.isEmpty()) throw new IllegalArgumentException(p.id + ": passo uds sem tx/req");
            Hex.parse(s.req);
            String why = ProcedureRunner.forbidden(Hex.parse(s.req));
            if (why != null) throw new IllegalArgumentException(p.id + ": " + why);
        }
        return p;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        if (!description.isEmpty()) m.put("description", description);
        if (!category.isEmpty()) m.put("category", category);
        if (!source.isEmpty()) m.put("source", source);
        if (!note.isEmpty()) m.put("note", note);
        m.put("available", available);
        m.put("verified", verified);
        if (needsSecurity) m.put("needs_security", true);
        List<Object> v = new ArrayList<>(vins);
        m.put("vins", v);
        List<Object> st = new ArrayList<>();
        for (Step s : steps) st.add(s.toJson());
        m.put("steps", st);
        return m;
    }
}
