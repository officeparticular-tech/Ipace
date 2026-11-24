package pt.ipacediag.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Módulos do Jaguar I-Pace (X590) acessíveis pela tomada OBD.
 * Endereços de pedido segundo o projeto Open Vehicle Monitoring System
 * (componente vehicle_jaguaripace, licença MIT, © 2021 Didier Ernotte)
 * e a lista comunitária de módulos do I-Pace. Resposta = pedido + 8.
 */
public final class IpaceModules {
    private IpaceModules() {}

    public static final int BCM = 0x726, GWM = 0x716, DCDC = 0x753, BECM = 0x7E4, BCCM = 0x7E5,
            PCM = 0x7E0, HVAC = 0x733, TPMS = 0x751, TCU = 0x754, EPICB = 0x747, EPICC = 0x746, BBM = 0x7E2;

    public static final class Module {
        public final int tx;
        public final String code, name;
        Module(int tx, String code, String name) { this.tx = tx; this.code = code; this.name = name; }
        public String label() { return code + " (" + Hex.h3(tx) + ")"; }
    }

    private static final List<Module> ALL = new ArrayList<>();

    private static void m(int tx, String code, String name) { ALL.add(new Module(tx, code, name)); }

    static {
        m(0x7E4, "BECM", "Gestão da bateria de alta tensão");
        m(0x7E5, "BCCM", "Controlo do carregador");
        m(0x753, "DCDC", "Conversor DC/DC (carrega as baterias 12V)");
        m(0x726, "BCM", "Módulo de controlo da carroçaria");
        m(0x716, "GWM", "Gateway");
        m(0x7E0, "PCM", "Controlo do grupo motopropulsor");
        m(0x747, "EPICB", "Inversor/motor dianteiro");
        m(0x746, "EPICC", "Inversor/motor traseiro");
        m(0x7E2, "BBM", "Servofreio elétrico");
        m(0x7E6, "ABS", "ABS / travagem");
        m(0x710, "CHCM", "Controlo do chassis");
        m(0x730, "PSCM", "Direção assistida");
        m(0x797, "SASM", "Sensor de ângulo de direção");
        m(0x737, "RCM", "Airbags");
        m(0x720, "IPC", "Quadrante de instrumentos");
        m(0x731, "RFA", "Atuador de funções remotas (chave)");
        m(0x732, "GSM", "Seletor de marchas");
        m(0x733, "HVAC", "Climatização");
        m(0x785, "RHVAC", "Climatização traseira");
        m(0x734, "HCM", "Faróis A");
        m(0x7C3, "HCMB", "Faróis B");
        m(0x736, "PAM", "Assistência ao estacionamento");
        m(0x740, "DDM", "Porta do condutor");
        m(0x741, "PDM", "Porta do passageiro");
        m(0x742, "DRDM", "Porta traseira esquerda");
        m(0x743, "PRDM", "Porta traseira direita");
        m(0x744, "DSM", "Banco do condutor");
        m(0x7A3, "PSM", "Banco do passageiro");
        m(0x751, "TPMS", "Pressão dos pneus");
        m(0x752, "OMM", "Deteção de ocupantes");
        m(0x754, "TCU", "Telemática");
        m(0x764, "ASCM", "Cruise control adaptativo");
        m(0x775, "RGTM", "Mala elétrica");
        m(0x792, "ATCM", "Comandos de condução");
        m(0x706, "IPMA", "Câmara / processamento de imagem");
        m(0x7A2, "IDMA", "Ecrã interativo");
        m(0x7A4, "AAM", "Amplificador de áudio");
        m(0x7B1, "CMR", "Câmara traseira");
        m(0x7B2, "HUD", "Head-up display");
        m(0x7B3, "IMC", "Infotainment");
        m(0x7C4, "SODL", "Ângulo morto esquerdo");
        m(0x7C6, "SODR", "Ângulo morto direito");
    }

    public static synchronized List<Module> all() { return Collections.unmodifiableList(new ArrayList<>(ALL)); }

    /** Acrescenta ou renomeia um módulo vindo de um pacote de definições. */
    public static synchronized void register(int tx, String code, String name) {
        ALL.removeIf(m -> m.tx == tx);
        ALL.add(new Module(tx, code, name));
    }

    public static synchronized Module byTx(int tx) {
        for (Module m : ALL) if (m.tx == tx) return m;
        return new Module(tx, "?", "Módulo " + Hex.h3(tx));
    }

    /** Módulos onde faz sentido procurar a tensão 12V. */
    public static int[] twelveVoltCandidates() { return new int[]{BCM, GWM, DCDC, BECM, BCCM, PCM}; }
}
