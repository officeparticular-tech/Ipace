package pt.ipacediag.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Descrições de erros. Só inclui códigos normalizados (SAE J2012) ou já
 * documentados publicamente para o I-Pace. Os restantes aparecem só com o
 * código — a base de dados completa da JLR não é pública.
 */
public final class DtcCatalog {
    private DtcCatalog() {}

    private static final Map<String, String> BASE = new HashMap<>();
    private static final Map<Integer, String> FTB = new HashMap<>();

    static {
        BASE.put("P0AA6", "Sistema de tensão da bateria AT: falha de isolamento");
        BASE.put("P0AA1", "Contactor positivo da bateria AT: colado fechado");
        BASE.put("P0AA4", "Contactor negativo da bateria AT: colado fechado");
        BASE.put("P0A7F", "Bateria AT: degradação do pack");
        BASE.put("P0A80", "Bateria AT: substituir pack");
        BASE.put("P0A94", "Conversor DC/DC: desempenho");
        BASE.put("P1B48", "Bateria AT: desvio de tensão (específico JLR)");
        BASE.put("U3000", "Módulo de controlo");
        BASE.put("U3003", "Tensão da bateria 12V (alimentação do módulo)");
        BASE.put("U0100", "Perda de comunicação com o PCM");
        BASE.put("U0111", "Perda de comunicação com o BECM (gestão da bateria AT)");
        BASE.put("U0140", "Perda de comunicação com o BCM");
        BASE.put("U0146", "Perda de comunicação com o Gateway");
        BASE.put("U0155", "Perda de comunicação com o quadrante (IPC)");
        BASE.put("U0198", "Perda de comunicação com a telemática (TCU)");
        BASE.put("U0293", "Perda de comunicação com o controlo do grupo motopropulsor híbrido/elétrico");

        FTB.put(0x00, "sem subtipo");
        FTB.put(0x01, "falha elétrica geral");
        FTB.put(0x04, "falha interna do sistema");
        FTB.put(0x11, "curto-circuito à massa");
        FTB.put(0x12, "curto-circuito à bateria");
        FTB.put(0x13, "circuito aberto");
        FTB.put(0x16, "tensão abaixo do limite");
        FTB.put(0x17, "tensão acima do limite");
        FTB.put(0x1C, "tensão fora de gama");
        FTB.put(0x1A, "resistência abaixo do limite");
        FTB.put(0x1B, "resistência acima do limite");
        FTB.put(0x29, "sinal inválido");
        FTB.put(0x31, "sem sinal");
        FTB.put(0x47, "falha de supervisão (watchdog)");
        FTB.put(0x49, "falha eletrónica interna");
        FTB.put(0x4B, "sobreaquecimento");
        FTB.put(0x55, "não configurado");
        FTB.put(0x56, "configuração inválida");
        FTB.put(0x62, "falha na comparação de sinais");
        FTB.put(0x64, "falha de plausibilidade do sinal");
        FTB.put(0x68, "registo de evento");
        FTB.put(0x71, "atuador bloqueado");
        FTB.put(0x72, "atuador bloqueado aberto");
        FTB.put(0x73, "atuador bloqueado fechado");
        FTB.put(0x81, "dados série inválidos recebidos");
        FTB.put(0x82, "contador de mensagens incorreto");
        FTB.put(0x83, "valor de proteção da mensagem incorreto");
        FTB.put(0x86, "sinal inválido recebido");
        FTB.put(0x87, "mensagem em falta");
        FTB.put(0x88, "barramento desligado (bus off)");
        FTB.put(0x92, "desempenho ou funcionamento incorreto");
        FTB.put(0x96, "falha interna de componente");
        FTB.put(0x98, "sobretemperatura do componente");
    }

    /** Acrescenta descrições vindas de um pacote de definições (código base, ex.: "P0AA6"). */
    public static synchronized void addBase(String code, String text) { BASE.put(code.toUpperCase(), text); }

    public static String ftbText(int ftb) {
        String s = FTB.get(ftb & 0xFF);
        return s != null ? s : "tipo 0x" + Hex.h2(ftb);
    }

    public static String describe(Dtc d) {
        String b = BASE.get(d.base());
        String f = ftbText(d.ftb);
        if (b == null) {
            if (d.base().startsWith("U0")) b = "Perda de comunicação entre módulos";
            else b = "(descrição JLR não pública)";
        }
        return b + " — " + f;
    }

    /** Erro relacionado com alta tensão / isolamento. */
    public static boolean isHighVoltage(Dtc d) {
        String c = d.base();
        return c.startsWith("P0AA") || c.startsWith("P0A7") || c.startsWith("P0A8")
                || c.startsWith("P1B4") || c.startsWith("P0A9")
                || d.moduleTx == IpaceModules.BECM || d.moduleTx == IpaceModules.BCCM;
    }

    /** Erro que indica tensão 12V baixa/alta num módulo (sintoma típico de 12V fraca). */
    public static boolean isLowVoltageEvent(Dtc d) {
        return d.base().equals("U3003");
    }
}
