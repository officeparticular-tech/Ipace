package pt.ipacediag.core;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Possíveis origens e possíveis soluções por código de erro (pelo código base).
 * São orientações GERAIS e indicativas, não um diagnóstico: servem para saber por
 * onde começar e para documentar. Baseadas em informação pública e na experiência
 * da comunidade do I-Pace; confirmar sempre com medição ou com uma oficina.
 */
public final class DtcAdvice {
    private DtcAdvice() {}

    public static final class Advice {
        public final List<String> causes, solutions;
        Advice(String[] c, String[] s) { this.causes = Arrays.asList(c); this.solutions = Arrays.asList(s); }
    }

    private static final Map<String, Advice> MAP = new HashMap<>();

    private static void a(String code, String[] causes, String[] solutions) { MAP.put(code, new Advice(causes, solutions)); }

    static {
        a("P0AA6", new String[]{
                "Entrada de água/humidade em conectores ou componentes de alta tensão (incluindo a tomada de carregamento)",
                "Degradação do isolamento de um cabo ou componente de alta tensão (aquecedor, compressor do AC, carregador)",
                "Falha interna de isolamento num módulo ou na própria bateria",
                "Falha que surge sobretudo durante o carregamento rápido (como no seu caso)"},
                new String[]{
                "Inspecionar e secar a tomada de carregamento e os conectores de alta tensão; procurar sinais de água",
                "Medir a resistência de isolamento por componente (requer TOPIx/Pathfinder) para localizar a origem",
                "Verificar o aquecedor de alta tensão (HVCH) e o compressor do AC, origens frequentes",
                "DOCUMENTAR dentro do prazo de garantia antes de apagar — é prova do defeito"});
        a("U3003", new String[]{
                "Bateria de 12V (principal ou auxiliar) fraca ou a perder capacidade",
                "Conversor DC/DC a não carregar corretamente",
                "Massas (ligações à terra) ou terminais com mau contacto"},
                new String[]{
                "Testar as DUAS baterias de 12V com um testador de condutância (não só a tensão)",
                "Verificar a tensão de saída do DC/DC com o carro ligado (13,2–15,2 V)",
                "Limpar e apertar massas e terminais"});
        a("P0A7F", new String[]{"Envelhecimento das células", "Perda de capacidade do pack ao longo do tempo"},
                new String[]{"Fazer uma leitura de saúde da bateria (SOH)", "Se dentro do prazo, acionar a garantia da bateria"});
        a("P0A80", new String[]{"Degradação acentuada do pack", "Uma ou mais células/módulos no limite"},
                new String[]{"Diagnóstico da bateria numa oficina", "Verificar cobertura de garantia/recall"});
        a("P0A94", new String[]{"Falha ou sobreaquecimento do conversor DC/DC", "Ligações do DC/DC"},
                new String[]{"Verificar o DC/DC e o seu arrefecimento", "Confirmar a tensão de carga da 12V"});
        a("P1B48", new String[]{"Desequilíbrio entre células", "Um módulo mais fraco"},
                new String[]{"Ler as tensões de célula e a diferença (delta)", "Deixar equilibrar com carga lenta e reavaliar"});
        a("P0AA1", new String[]{"Contactor positivo colado/soldado", "Falha do relé de potência"},
                new String[]{"Inspeção do BECM e dos contactores numa oficina", "Não conduzir até avaliar — risco de segurança"});
        a("P0AA4", new String[]{"Contactor negativo colado/soldado", "Falha do relé de potência"},
                new String[]{"Inspeção do BECM e dos contactores numa oficina", "Não conduzir até avaliar — risco de segurança"});
        a("B1DC5", new String[]{"Falha do aquecedor de alta tensão do líquido de refrigeração (HVCH)"},
                new String[]{"Verificar a ação de serviço H525 junto da Jaguar"});
        String[] commsC = {"Módulo sem alimentação (tipicamente 12V baixa)", "Problema na rede de comunicação (CAN)", "Avaria do próprio módulo"};
        String[] commsS = {"Verificar primeiro a 12V e as massas", "Inspecionar a cablagem CAN e os conectores", "Reavaliar após resolver a 12V — muitos U-códigos desaparecem"};
        for (String u : new String[]{"U0100", "U0111", "U0140", "U0146", "U0155", "U0198", "U0293"}) a(u, commsC, commsS);
    }

    public static Advice forDtc(Dtc d) {
        Advice ad = MAP.get(d.base());
        if (ad != null) return ad;
        if (d.base().startsWith("U0") || d.base().startsWith("U3"))
            return MAP.get("U0100");
        return new Advice(
                new String[]{"Falha possivelmente intermitente", "Sensor, cablagem ou conector relacionado", "Alimentação 12V instável no momento da falha"},
                new String[]{"Confirmar a 12V e as ligações", "Apagar e reavaliar se o erro volta", "Se persistir, diagnóstico profissional do módulo"});
    }
}
