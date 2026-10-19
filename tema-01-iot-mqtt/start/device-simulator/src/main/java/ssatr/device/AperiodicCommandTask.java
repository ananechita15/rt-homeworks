package ssatr.device;

import java.util.concurrent.BlockingQueue;

/**
 * TASK APERIODIC: execută comenzile primite de la business service.
 *
 * Comenzile sosesc oricând, pe MQTT, fără un interval minim între ele, și sunt
 * puse în coada commands. Fiecare comandă eliberează un job al taskului.
 * Comenzi suportate:
 *
 *   SET_INTERVAL <ms>       schimbă perioada T a taskului periodic
 *   SET_THRESHOLD <valoare> schimbă pragul de alarmă
 *   RESET                   readuce senzorul la valoarea inițială
 *   STATUS                  raportează perioada și pragul curente
 *
 * După executarea fiecărei comenzi, device-ul publică pe Config.statusTopic(deviceId)
 * o confirmare: "deviceId;timestamp;text".
 */
public class AperiodicCommandTask implements Runnable {

    private final String deviceId;
    private final TemperatureSensor sensor;
    private final MqttConnection mqtt;
    private final BlockingQueue<String> commands;
    private final PeriodicTelemetryTask periodic;

    public AperiodicCommandTask(String deviceId, TemperatureSensor sensor, MqttConnection mqtt,
                                BlockingQueue<String> commands, PeriodicTelemetryTask periodic) {
        this.deviceId = deviceId;
        this.sensor = sensor;
        this.mqtt = mqtt;
        this.commands = commands;
        this.periodic = periodic;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            String command;
            try {
                command = commands.take();   // așteaptă o comandă, în ordinea sosirii
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            String reply;
            try {
                reply = execute(command.trim());
            } catch (Exception e) {
                // orice problemă la o comandă nu oprește taskul
                reply = "ERROR " + e.getMessage();
            }

            String payload = deviceId + ";" + System.currentTimeMillis() + ";" + reply;
            mqtt.publish(Config.statusTopic(deviceId), payload);
            System.out.println("[aperiodic] " + command + " -> " + reply);
        }
    }

    private String execute(String command) {
        if (command.isEmpty()) {
            return "ERROR comanda goala";
        }

        String[] parts = command.split("\\s+");
        String name = parts[0].toUpperCase();

        switch (name) {
            case "SET_INTERVAL": {
                if (parts.length != 2) {
                    return "ERROR SET_INTERVAL necesita un parametru: SET_INTERVAL <ms>";
                }
                long ms;
                try {
                    ms = Long.parseLong(parts[1]);
                } catch (NumberFormatException e) {
                    return "ERROR parametru invalid: " + parts[1];
                }
                if (ms <= 0) {
                    return "ERROR intervalul trebuie sa fie pozitiv";
                }
                periodic.setPeriodMs(ms);
                return "OK SET_INTERVAL " + ms;
            }
            case "SET_THRESHOLD": {
                if (parts.length != 2) {
                    return "ERROR SET_THRESHOLD necesita un parametru: SET_THRESHOLD <valoare>";
                }
                double value;
                try {
                    value = Double.parseDouble(parts[1]);
                } catch (NumberFormatException e) {
                    return "ERROR parametru invalid: " + parts[1];
                }
                periodic.setThreshold(value);
                return "OK SET_THRESHOLD " + value;
            }
            case "RESET":
                sensor.reset();
                return "OK RESET";
            case "STATUS":
                return "OK STATUS interval=" + periodic.getPeriodMs()
                        + " threshold=" + periodic.getThreshold();
            default:
                return "ERROR comanda necunoscuta: " + parts[0];
        }
    }
}