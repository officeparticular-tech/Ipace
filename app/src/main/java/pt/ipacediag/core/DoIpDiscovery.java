package pt.ipacediag.core;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

/**
 * Descoberta de veículos DoIP por UDP (broadcast na porta 13400). Devolve o VIN e o
 * endereço lógico de cada entidade que responder. Serve para preencher automaticamente
 * os campos de ligação sem o utilizador adivinhar endereços.
 */
public final class DoIpDiscovery {
    private DoIpDiscovery() {}

    public static final class Found {
        public final String host;
        public final String vin;
        public final int logicalAddress;
        Found(String host, String vin, int addr) { this.host = host; this.vin = vin; this.logicalAddress = addr; }
    }

    /** Envia o pedido de identificação e recolhe respostas durante timeoutMs. */
    public static List<Found> discover(int timeoutMs) throws IOException {
        List<Found> out = new ArrayList<>();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(Math.max(300, timeoutMs));
            byte[] req = DoIp.vehicleIdRequest();
            s.send(new DatagramPacket(req, req.length, InetAddress.getByName("255.255.255.255"), DoIp.PORT));

            long end = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[256];
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (SocketTimeoutException e) {
                    break;
                }
                if (p.getLength() < 8) continue;
                byte[] data = new byte[p.getLength()];
                System.arraycopy(p.getData(), 0, data, 0, p.getLength());
                if (DoIp.payloadType(data) != DoIp.PT_VEHICLE_ID_RESP) continue;
                byte[] payload = new byte[data.length - 8];
                System.arraycopy(data, 8, payload, 0, payload.length);
                DoIp.Announcement a = DoIp.parseAnnouncement(payload);
                if (a != null) out.add(new Found(p.getAddress().getHostAddress(), a.vin, a.logicalAddress));
            }
        }
        return out;
    }
}
