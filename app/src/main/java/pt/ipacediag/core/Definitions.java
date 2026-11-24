package pt.ipacediag.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pacotes de definições: permitem acrescentar à app, sem a recompilar,
 * módulos, parâmetros, descrições de erros e procedimentos guiados.
 *
 * Formato (JSON):
 * { "format": 1, "name": "...", "version": "2026.11.22", "min_app": 2,
 *   "modules":    [ {"tx":"7E4","code":"BECM","name":"..."} ],
 *   "params":     [ {"key":"x","name":"...","unit":"V","tx":"7E4","did":"4920","type":"u16",
 *                    "offset":0,"scale":0.001,"add":0,"decimals":3,"confirmed":false} ],
 *   "dtcs":       { "P0AA6": "descrição" },
 *   "procedures": [ { "id":"...", "title":"...", "steps":[ ... ] } ] }
 */
public final class Definitions {

    public static final int APP_FORMAT = 1;
    public static final int APP_VERSION = 2;

    public static final class Pack {
        public String name = "", version = "", source = "";
        public int params, dtcs, modules, procedures;
        public String summary() {
            return name + (version.isEmpty() ? "" : " v" + version) + ": " + params + " parâmetros, " + dtcs + " descrições de erros, "
                    + modules + " módulos, " + procedures + " procedimentos";
        }
    }

    private final Map<String, Procedure> procedures = new LinkedHashMap<>();
    private final List<Pack> packs = new ArrayList<>();

    public synchronized List<Pack> packs() { return new ArrayList<>(packs); }

    public synchronized List<Procedure> procedures() { return new ArrayList<>(procedures.values()); }

    public synchronized Procedure procedure(String id) { return procedures.get(id); }

    public synchronized void putProcedure(Procedure p) { procedures.put(p.id, p); }

    /** Valida e aplica um pacote. Lança IllegalArgumentException com o motivo se for inválido. */
    public synchronized Pack apply(String json, String source) {
        Map<String, Object> root = MiniJson.obj(MiniJson.parse(json));
        if (root.isEmpty()) throw new IllegalArgumentException("o ficheiro não é um pacote de definições");
        int format = (int) MiniJson.num(root, "format", 1);
        if (format > APP_FORMAT) throw new IllegalArgumentException("pacote em formato " + format + ": atualize a app");
        int minApp = (int) MiniJson.num(root, "min_app", 1);
        if (minApp > APP_VERSION) throw new IllegalArgumentException("este pacote precisa da versão " + minApp + " da app (tem a " + APP_VERSION + ")");

        // validar tudo antes de aplicar
        List<Procedure> procs = new ArrayList<>();
        for (Object o : MiniJson.list(root.get("procedures"))) procs.add(Procedure.fromJson(MiniJson.obj(o)));
        List<Map<String, Object>> params = new ArrayList<>();
        for (Object o : MiniJson.list(root.get("params"))) {
            Map<String, Object> m = MiniJson.obj(o);
            if (MiniJson.str(m, "key", "").isEmpty() || MiniJson.hex(m, "tx", -1) < 0 || MiniJson.hex(m, "did", -1) < 0)
                throw new IllegalArgumentException("parâmetro sem key/tx/did");
            decoder(m); // valida o tipo
            params.add(m);
        }

        Pack pk = new Pack();
        pk.name = MiniJson.str(root, "name", "pacote");
        pk.version = MiniJson.str(root, "version", "");
        pk.source = source;
        for (Object o : MiniJson.list(root.get("modules"))) {
            Map<String, Object> m = MiniJson.obj(o);
            int tx = MiniJson.hex(m, "tx", -1);
            if (tx < 0) continue;
            IpaceModules.register(tx, MiniJson.str(m, "code", Hex.h3(tx)), MiniJson.str(m, "name", ""));
            pk.modules++;
        }
        for (Map<String, Object> m : params) {
            IpaceParams.register(MiniJson.str(m, "key", ""), MiniJson.str(m, "name", ""), MiniJson.str(m, "unit", ""),
                    MiniJson.hex(m, "tx", 0), MiniJson.hex(m, "did", 0), (int) MiniJson.num(m, "decimals", 2),
                    MiniJson.bool(m, "confirmed", false), decoder(m), MiniJson.str(m, "group", ""));
            pk.params++;
        }
        for (Map.Entry<String, Object> e : MiniJson.obj(root.get("dtcs")).entrySet()) {
            if (e.getValue() == null) continue;
            DtcCatalog.addBase(e.getKey(), e.getValue().toString());
            pk.dtcs++;
        }
        for (Procedure p : procs) { procedures.put(p.id, p); pk.procedures++; }
        packs.add(pk);
        return pk;
    }

    static IpaceParams.Decoder decoder(Map<String, Object> m) {
        String type = MiniJson.str(m, "type", "u8");
        final int off = (int) MiniJson.num(m, "offset", 0);
        final double scale = MiniJson.num(m, "scale", 1);
        final double add = MiniJson.num(m, "add", 0);
        switch (type) {
            case "u8": return d -> Hex.u8(d, off) * scale + add;
            case "s8": return d -> ((byte) d[off]) * scale + add;
            case "u16": return d -> Hex.u16(d, off) * scale + add;
            case "s16": return d -> ((short) Hex.u16(d, off)) * scale + add;
            case "u24": return d -> Hex.u24(d, off) * scale + add;
            case "u32": return d -> (((long) Hex.u16(d, off) << 16) | Hex.u16(d, off + 2)) * scale + add;
            case "count_nonzero": return d -> { int n = 0; for (int i = off; i < d.length; i++) if (d[i] != 0) n++; return n; };
            case "length": return d -> d.length;
            default: throw new IllegalArgumentException("tipo de parâmetro desconhecido: " + type);
        }
    }

    /** Pacote JSON só com procedimentos (para guardar capturas ou exportar). */
    public static String packJson(String name, String version, List<Procedure> procs) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", APP_FORMAT);
        root.put("name", name);
        root.put("version", version);
        root.put("min_app", APP_VERSION);
        List<Object> l = new ArrayList<>();
        for (Procedure p : procs) l.add(p.toJson());
        root.put("procedures", l);
        return MiniJson.write(root);
    }
}
