package pt.ipacediag.core;

import java.io.IOException;

/** Canal de texto para o adaptador (Bluetooth no telemóvel, simulador nos testes). */
public interface ObdTransport {
    /** Envia um comando (sem \r) e devolve o texto até ao prompt '>' (sem o prompt). */
    String command(String cmd, long timeoutMs) throws IOException;
    void close();

    /**
     * Modo escuta: envia o comando (ex.: ATMA) e entrega cada linha recebida ao sink
     * até stop devolver true; depois interrompe o adaptador e espera pelo prompt.
     */
    default void monitor(String cmd, Recorder.Sink sink, java.util.function.BooleanSupplier stop) throws IOException {
        throw new IOException("Este adaptador não suporta o modo escuta.");
    }
    boolean isOpen();
}
