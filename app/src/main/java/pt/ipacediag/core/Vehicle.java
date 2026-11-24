package pt.ipacediag.core;

/** Carros suportados pela app. Todos usam CAN 11 bits a 500 kbit/s (ISO 15765-4) por um ELM327. */
public enum Vehicle {
    IPACE("ipace", "Jaguar I-Pace", "2018–2024 · elétrico (X590)",
            "Diagnóstico completo: ~40 módulos, erros, bateria de alta tensão, 12V, registo de carregamento, procedimentos. "
            + "Validado sobretudo no software 2018–2020 (menos protegido); carros mais recentes leem bem, mas alguns parâmetros/endereços podem diferir."),
    LEAF("leaf", "Nissan Leaf", "2013–2017 · 24/30 kWh (AZE0)",
            "Núcleo da bateria ao estilo LeafSpy: SOH, Hx, AHr, tensões de célula e temperaturas. "
            + "Base no AZE0; afinado para o 24 kWh de 2015. Valores por confirmar no carro.");

    public final String id, name, subtitle, description;

    Vehicle(String id, String name, String subtitle, String description) {
        this.id = id; this.name = name; this.subtitle = subtitle; this.description = description;
    }

    public static Vehicle byId(String id) {
        for (Vehicle v : values()) if (v.id.equals(id)) return v;
        return IPACE;
    }
}
