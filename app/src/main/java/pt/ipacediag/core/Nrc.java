package pt.ipacediag.core;

/** Códigos de resposta negativa UDS (ISO 14229-1). */
public final class Nrc {
    private Nrc() {}
    public static final int RESPONSE_PENDING = 0x78;

    public static String text(int nrc) {
        switch (nrc) {
            case 0x10: return "recusa geral";
            case 0x11: return "serviço não suportado";
            case 0x12: return "subfunção não suportada";
            case 0x13: return "comprimento/formato inválido";
            case 0x14: return "resposta demasiado longa";
            case 0x21: return "ocupado, repetir pedido";
            case 0x22: return "condições não corretas";
            case 0x24: return "sequência de pedidos errada";
            case 0x31: return "identificador fora de alcance / inexistente";
            case 0x33: return "acesso de segurança negado";
            case 0x35: return "chave inválida";
            case 0x36: return "tentativas excedidas";
            case 0x37: return "tempo de espera não cumprido";
            case 0x72: return "falha geral de programação";
            case 0x78: return "resposta pendente";
            case 0x7E: return "subfunção não suportada nesta sessão";
            case 0x7F: return "serviço não suportado nesta sessão";
            default: return "código desconhecido";
        }
    }
}
