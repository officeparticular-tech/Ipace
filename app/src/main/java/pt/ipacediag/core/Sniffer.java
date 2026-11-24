package pt.ipacediag.core;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Análise de uma captura em Modo Escuta: o adaptador ouve em silêncio
 * (sem confirmar tramas) o diálogo entre a máquina de uma oficina e os módulos,
 * e aqui reconstrói-se cada pedido UDS e a respetiva resposta.
 */
public final class Sniffer {

    public static final class Line {
        public final long t;
        public final String text;
        public Line(long t, String text) { this.t = t; this.text = text; }
    }

    public static final class Msg {
        public final long t;
        public final int id;
        public final byte[] data;
        Msg(long t, int id, byte[] data) { this.t = t; this.id = id; this.data = data; }
    }

    public static final class Event {
        public final long t;
        public final int tx;
        public final byte[] req;
        public byte[] resp;
        Event(long t, int tx, byte[] req) { this.t = t; this.tx = tx; this.req = req; }
        public int sid() { return req[0] & 0xFF; }
        public boolean positive() { return resp != null && resp.length > 0 && (resp[0] & 0xFF) == sid() + 0x40; }
        public String describe() {
            String r = resp == null ? "sem resposta" : positive() ? "OK " + Hex.of(resp)
                    : (resp.length >= 3 && (resp[0] & 0xFF) == 0x7F ? "recusado: " + Nrc.text(resp[2] & 0xFF) : Hex.of(resp));
            return IpaceModules.byTx(tx).code + " ← " + Hex.of(req) + "  →  " + r;
        }
    }

    /** Reconstrói as mensagens ISO-TP de cada identificador CAN (tramas em bruto, ATCAF0). */
    public static List<Msg> messages(List<Line> lines) {
        List<Msg> out = new ArrayList<>();
        Map<Integer, ByteArrayOutputStream> cur = new HashMap<>();
        Map<Integer, Integer> expected = new HashMap<>();
        Map<Integer, Long> started = new HashMap<>();
        for (Line l : lines) {
            for (ElmParser.Frame f : ElmParser.frames(l.text)) {
                if (f.data.length == 0) continue;
                int pci = (f.data[0] & 0xF0) >> 4;
                if (pci == 0) {
                    int len = f.data[0] & 0x0F;
                    if (len == 0 || len > f.data.length - 1) continue;
                    byte[] m = new byte[len];
                    System.arraycopy(f.data, 1, m, 0, len);
                    out.add(new Msg(l.t, f.id, m));
                } else if (pci == 1 && f.data.length >= 2) {
                    int len = ((f.data[0] & 0x0F) << 8) | (f.data[1] & 0xFF);
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    b.write(f.data, 2, f.data.length - 2);
                    cur.put(f.id, b);
                    expected.put(f.id, len);
                    started.put(f.id, l.t);
                } else if (pci == 2 && cur.containsKey(f.id)) {
                    ByteArrayOutputStream b = cur.get(f.id);
                    b.write(f.data, 1, f.data.length - 1);
                    int len = expected.get(f.id);
                    if (b.size() >= len) {
                        byte[] all = b.toByteArray();
                        byte[] m = new byte[len];
                        System.arraycopy(all, 0, m, 0, len);
                        out.add(new Msg(started.get(f.id), f.id, m));
                        cur.remove(f.id);
                    }
                }
            }
        }
        return out;
    }

    /** Emparelha pedidos (ID par, bit 3 a zero) com a resposta seguinte no ID + 8. */
    public static List<Event> events(List<Msg> msgs) {
        List<Event> out = new ArrayList<>();
        Map<Integer, Event> waiting = new HashMap<>();
        for (Msg m : msgs) {
            if (m.data.length == 0) continue;
            if (m.id >= 0x700 && m.id <= 0x7FF && m.id != 0x7DF && (m.id & 0x8) == 0) {
                Event e = new Event(m.t, m.id, m.data);
                out.add(e);
                waiting.put(m.id, e);
            } else if ((m.id & 0x8) != 0) {
                Event e = waiting.get(m.id - 8);
                if (e == null) continue;
                if (m.data.length >= 3 && (m.data[0] & 0xFF) == 0x7F && (m.data[2] & 0xFF) == Nrc.RESPONSE_PENDING) continue;
                e.resp = m.data;
                waiting.remove(m.id - 8);
            }
        }
        return out;
    }

    public static String summary(List<Event> ev) {
        if (ev.isEmpty()) return "Nenhum pedido de diagnóstico captado. Confirme o cabo em Y e que a máquina da oficina estava a comunicar.";
        Map<String, Integer> count = new TreeMap<>();
        for (Event e : ev) {
            String k = IpaceModules.byTx(e.tx).code + " serviço 0x" + Hex.h2(e.sid()) + " (" + serviceName(e.sid()) + ")";
            count.merge(k, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder(ev.size() + " pedidos captados:\n");
        for (Map.Entry<String, Integer> e : count.entrySet()) sb.append("• ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        List<Event> r = routines(ev);
        if (r.isEmpty()) sb.append("\nNenhuma rotina (0x31) executada com sucesso.");
        else {
            sb.append("\nRotinas executadas:\n");
            for (Event e : r) sb.append("★ ").append(e.describe()).append(securityBefore(ev, e) ? "  [usou acesso de segurança]" : "").append('\n');
        }
        return sb.toString();
    }

    public static String serviceName(int sid) {
        switch (sid) {
            case 0x10: return "sessão";
            case 0x11: return "reinício do módulo";
            case 0x14: return "apagar erros";
            case 0x19: return "ler erros";
            case 0x22: return "ler dados";
            case 0x27: return "acesso de segurança";
            case 0x2E: return "escrever dados";
            case 0x2F: return "controlar atuador";
            case 0x31: return "rotina";
            case 0x3E: return "presença";
            case 0x34: case 0x36: case 0x37: return "programação";
            default: return "outro";
        }
    }

    public static List<Event> routines(List<Event> ev) {
        List<Event> r = new ArrayList<>();
        for (Event e : ev) if (e.sid() == 0x31 && e.req.length >= 4 && (e.req[1] & 0x7F) == 0x01 && e.positive()) r.add(e);
        return r;
    }

    static boolean securityBefore(List<Event> ev, Event routine) {
        for (Event e : ev) {
            if (e == routine) break;
            if (e.tx == routine.tx && e.sid() == 0x27) return true;
        }
        return false;
    }

    /** Cria um procedimento (não verificado, preso ao VIN) que reproduz a rotina captada. */
    public static Procedure toProcedure(List<Event> ev, Event routine, String id, String title, String vin, String origin) {
        Procedure p = new Procedure();
        p.id = id;
        p.title = title;
        p.category = "capturado";
        p.source = origin;
        p.verified = false;
        p.available = true;
        if (vin != null && !vin.isEmpty()) p.vins.add(vin);
        p.needsSecurity = securityBefore(ev, routine);
        Event session = null;
        for (Event e : ev) {
            if (e == routine) break;
            if (e.tx == routine.tx && e.sid() == 0x10 && e.positive()) session = e;
        }
        if (session != null) {
            int st = session.req[1] & 0x7F;
            if (st != 0x01 && st != 0x03) {
                p.available = false;
                p.note = "A máquina da oficina usou uma sessão de diagnóstico não permitida nesta app (0x" + Hex.h2(st) + ").";
                session = null;
            }
        }
        if (session != null && (session.req[1] & 0x7F) != 0x01) {
            p.steps.add(udsStep(routine.tx, session.req, session.resp, 2, "Sessão de diagnóstico"));
        }
        p.steps.add(udsStep(routine.tx, routine.req, routine.resp, 4, "Rotina " + Hex.of(routine.req)));
        if (session != null && (session.req[1] & 0x7F) != 0x01) {
            Procedure.Step back = new Procedure.Step();
            back.type = "uds"; back.tx = routine.tx; back.req = "1001"; back.expect = "5001"; back.label = "Voltar à sessão normal";
            p.steps.add(back);
        }
        p.description = String.format(Locale.US, "Capturado: %s. %s", Hex.of(routine.req),
                p.needsSecurity ? "A ferramenta original usou acesso de segurança — não reproduzível." : "Sem acesso de segurança.");
        return p;
    }

    private static Procedure.Step udsStep(int tx, byte[] req, byte[] resp, int expectLen, String label) {
        Procedure.Step s = new Procedure.Step();
        s.type = "uds";
        s.tx = tx;
        s.req = Hex.of(req).replace(" ", "");
        s.expect = resp == null ? "" : Hex.of(resp, 0, Math.min(expectLen, resp.length)).replace(" ", "");
        s.label = label;
        return s;
    }

    public static String csv(List<Line> lines) {
        StringBuilder sb = new StringBuilder("ms,trama\n");
        long t0 = lines.isEmpty() ? 0 : lines.get(0).t;
        for (Line l : lines) sb.append(l.t - t0).append(',').append(l.text.replace(',', ' ')).append('\n');
        return sb.toString();
    }

    /** Agrupa as rotinas por módulo para mostrar ao utilizador. */
    public static Map<String, Event> routineChoices(List<Event> ev) {
        Map<String, Event> m = new LinkedHashMap<>();
        for (Event e : routines(ev)) m.put(IpaceModules.byTx(e.tx).code + " " + Hex.of(e.req), e);
        return m;
    }
}
