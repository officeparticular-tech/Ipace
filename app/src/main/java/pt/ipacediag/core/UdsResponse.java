package pt.ipacediag.core;

/** Resultado de um pedido UDS a um módulo. */
public final class UdsResponse {
    public enum Kind { POSITIVE, NEGATIVE, NO_RESPONSE, ADAPTER_ERROR }

    public final Kind kind;
    /** Mensagem completa (inclui o byte de serviço, ex.: 62 49 10 ...). */
    public final byte[] payload;
    public final int nrc;
    public final String raw;
    public final String error;

    private UdsResponse(Kind k, byte[] p, int nrc, String raw, String err) {
        this.kind = k; this.payload = p; this.nrc = nrc; this.raw = raw; this.error = err;
    }

    public static UdsResponse positive(byte[] p, String raw) { return new UdsResponse(Kind.POSITIVE, p, 0, raw, null); }
    public static UdsResponse negative(int nrc, byte[] p, String raw) { return new UdsResponse(Kind.NEGATIVE, p, nrc, raw, null); }
    public static UdsResponse none(String raw) { return new UdsResponse(Kind.NO_RESPONSE, null, 0, raw, null); }
    public static UdsResponse adapterError(String err, String raw) { return new UdsResponse(Kind.ADAPTER_ERROR, null, 0, raw, err); }

    public boolean ok() { return kind == Kind.POSITIVE; }

    /** Dados depois do eco do pedido (ex.: para 22 49 10 devolve os bytes após 62 49 10). */
    public byte[] dataAfter(int skip) {
        if (payload == null || payload.length < skip) return new byte[0];
        byte[] d = new byte[payload.length - skip];
        System.arraycopy(payload, skip, d, 0, d.length);
        return d;
    }

    public String describe() {
        switch (kind) {
            case POSITIVE: return "OK " + Hex.of(payload);
            case NEGATIVE: return "Recusado: " + Nrc.text(nrc) + " (0x" + Hex.h2(nrc) + ")";
            case NO_RESPONSE: return "Sem resposta";
            default: return "Erro do adaptador: " + error;
        }
    }
}
