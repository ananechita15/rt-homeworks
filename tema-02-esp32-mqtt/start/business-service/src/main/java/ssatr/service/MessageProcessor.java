package ssatr.service;

import java.util.concurrent.BlockingQueue;

/**
 * Preia mesajele de un anumit tip ("telemetry", "alarm" sau "status") din coada
 * inbox, le salvează și actualizează dashboard-ul.
 *
 * Formatul mesajelor (câmpuri separate prin ";"):
 *   telemetry:  deviceId;timestamp;temperatura
 *   alarm:      deviceId;timestamp;temperatura;prag
 *   status:     deviceId;timestamp;text
 */
public class MessageProcessor implements Runnable {

    private final String kind;
    private final BlockingQueue<String> inbox;
    private final CsvStorage storage;
    private final Dashboard dashboard;

    public MessageProcessor(String kind, BlockingQueue<String> inbox, CsvStorage storage, Dashboard dashboard) {
        this.kind = kind;
        this.inbox = inbox;
        this.storage = storage;
        this.dashboard = dashboard;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            String message;
            try {
                message = inbox.take();   // așteaptă un mesaj
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            try {
                process(message);
            } catch (Exception e) {
                // un mesaj greșit nu oprește thread-ul
                System.out.println("[" + kind + "] mesaj invalid ignorat: \"" + message + "\" (" + e + ")");
            }
        }
    }

    private void process(String message) {
        // 1. salvăm mesajul exact cum a sosit
        storage.append(kind, message);

        // 2. îl despărțim în câmpuri (-1 păstrează și câmpurile goale de la final)
        String[] f = message.split(";", -1);

        // 3. actualizăm dashboard-ul, în funcție de tip
        switch (kind) {
            case "telemetry":
                // deviceId;timestamp;temperatura
                dashboard.updateTelemetry(f[0], Double.parseDouble(f[2]));
                break;

            case "alarm":
                // deviceId;timestamp;temperatura;prag
                dashboard.updateAlarm(f[0], Double.parseDouble(f[2]));
                System.out.println("!!! ALARMA " + f[0] + ": temperatura " + f[2] + " a depășit pragul " + f[3]);
                break;

            case "status":
                // deviceId;timestamp;text
                dashboard.updateStatus(f[0]);
                System.out.println(">>> STATUS " + f[0] + ": " + f[2]);
                break;

            default:
                System.out.println("Tip de mesaj necunoscut: " + kind);
        }
    }
}