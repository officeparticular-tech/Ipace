package pt.ipacediag.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Leitor/escritor JSON mínimo (sem dependências), para os pacotes de definições. */
public final class MiniJson {
    private final String s;
    private int i;

    private MiniJson(String s) { this.s = s; }

    public static Object parse(String text) {
        MiniJson p = new MiniJson(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.err("texto a mais no fim");
        return v;
    }

    private IllegalArgumentException err(String m) {
        return new IllegalArgumentException("JSON inválido (posição " + i + "): " + m);
    }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

    private Object value() {
        if (i >= s.length()) throw err("fim inesperado");
        char c = s.charAt(i);
        switch (c) {
            case '{': return obj();
            case '[': return arr();
            case '"': return str();
            case 't': lit("true"); return Boolean.TRUE;
            case 'f': lit("false"); return Boolean.FALSE;
            case 'n': lit("null"); return null;
            default: return num();
        }
    }

    private void lit(String w) {
        if (!s.startsWith(w, i)) throw err("esperado " + w);
        i += w.length();
    }

    private Map<String, Object> obj() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') throw err("esperada chave");
            String k = str();
            ws();
            if (i >= s.length() || s.charAt(i) != ':') throw err("esperado ':'");
            i++; ws();
            m.put(k, value());
            ws();
            if (i >= s.length()) throw err("objeto por fechar");
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw err("esperado ',' ou '}'");
        }
    }

    private List<Object> arr() {
        List<Object> l = new ArrayList<>();
        i++; ws();
        if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            if (i >= s.length()) throw err("lista por fechar");
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw err("esperado ',' ou ']'");
        }
    }

    private String str() {
        StringBuilder b = new StringBuilder();
        i++;
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c == '\\') {
                if (i >= s.length()) break;
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw err("\\u incompleto");
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: b.append(e);
                }
            } else b.append(c);
        }
        throw err("texto por fechar");
    }

    private Double num() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (st == i) throw err("valor inesperado '" + s.charAt(i) + "'");
        return Double.parseDouble(s.substring(st, i));
    }

    // ---------- escrita ----------
    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v, 0);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder b, Object v, int ind) {
        if (v == null) b.append("null");
        else if (v instanceof String) quote(b, (String) v);
        else if (v instanceof Boolean) b.append(v);
        else if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d); else b.append(d);
        } else if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            b.append("{");
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) b.append(",");
                first = false;
                b.append("\n").append(pad(ind + 1));
                quote(b, e.getKey());
                b.append(": ");
                write(b, e.getValue(), ind + 1);
            }
            if (!m.isEmpty()) b.append("\n").append(pad(ind));
            b.append("}");
        } else if (v instanceof List) {
            List<Object> l = (List<Object>) v;
            b.append("[");
            for (int k = 0; k < l.size(); k++) {
                if (k > 0) b.append(",");
                b.append("\n").append(pad(ind + 1));
                write(b, l.get(k), ind + 1);
            }
            if (!l.isEmpty()) b.append("\n").append(pad(ind));
            b.append("]");
        } else quote(b, v.toString());
    }

    private static String pad(int n) {
        StringBuilder p = new StringBuilder();
        for (int k = 0; k < n; k++) p.append("  ");
        return p.toString();
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        b.append('"');
    }

    // ---------- acesso cómodo ----------
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) { return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>(); }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) { return o instanceof List ? (List<Object>) o : new ArrayList<>(); }

    public static String str(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        return v == null ? def : v.toString();
    }

    public static double num(Map<String, Object> m, String k, double def) {
        Object v = m.get(k);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            String t = ((String) v).trim();
            try { return t.startsWith("0x") || t.startsWith("0X") ? Integer.parseInt(t.substring(2), 16) : Double.parseDouble(t); }
            catch (Exception e) { return def; }
        }
        return def;
    }

    /** Número que pode vir como "7E4" (hex) ou 2020. */
    public static int hex(Map<String, Object> m, String k, int def) {
        Object v = m.get(k);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            String t = ((String) v).trim().replace("0x", "").replace("0X", "");
            try { return Integer.parseInt(t, 16); } catch (Exception e) { return def; }
        }
        return def;
    }

    public static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object v = m.get(k);
        return v instanceof Boolean ? (Boolean) v : def;
    }
}
