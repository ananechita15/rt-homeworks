package ssatr.service;

import java.util.Map;
import java.util.TreeMap;

/**
 * Ultima stare cunoscută a fiecărui device: ultima temperatură, momentul
 * ultimului mesaj, numărul de alarme etc.
 *
 * Este actualizată de thread-urile MessageProcessor și citită de CommandConsole,
 * deci toate metodele sunt synchronized.
 */
public class Dashboard {

    /** Starea unui singur device. */
    private static class DeviceState {
        double lastTemperature = Double.NaN;   // NaN = încă nu a sosit telemetrie
        long lastMessageAt = 0;                // momentul sosirii ultimului mesaj (ms)
        int alarmCount = 0;
    }

    // TreeMap: device-urile apar sortate după nume în tabel
    private final Map<String, DeviceState> devices = new TreeMap<>();

    private DeviceState state(String deviceId) {
        return devices.computeIfAbsent(deviceId, id -> new DeviceState());
    }

    /** Apelată la fiecare mesaj de telemetrie. */
    public synchronized void updateTelemetry(String deviceId, double temperature) {
        DeviceState s = state(deviceId);
        s.lastTemperature = temperature;
        s.lastMessageAt = System.currentTimeMillis();
    }

    /** Apelată la fiecare alarmă. */
    public synchronized void updateAlarm(String deviceId, double temperature) {
        DeviceState s = state(deviceId);
        s.lastTemperature = temperature;
        s.alarmCount++;
        s.lastMessageAt = System.currentTimeMillis();
    }

    /** Apelată la fiecare confirmare (status). */
    public synchronized void updateStatus(String deviceId) {
        DeviceState s = state(deviceId);
        s.lastMessageAt = System.currentTimeMillis();
    }

    /** Afișează un tabel cu starea tuturor device-urilor. */
    public synchronized void print() {
        if (devices.isEmpty()) {
            System.out.println("(niciun device nu a trimis încă mesaje)");
            return;
        }

        long now = System.currentTimeMillis();
        System.out.printf("%-12s %-8s %-16s %s%n", "DEVICE", "TEMP", "ULTIMUL MESAJ", "ALARME");

        for (Map.Entry<String, DeviceState> entry : devices.entrySet()) {
            DeviceState s = entry.getValue();

            String temp = Double.isNaN(s.lastTemperature)
                    ? "-" : String.format(java.util.Locale.US, "%.2f", s.lastTemperature);
            String age = "acum " + (now - s.lastMessageAt) / 1000 + " s";

            System.out.printf("%-12s %-8s %-16s %d%n", entry.getKey(), temp, age, s.alarmCount);
        }
    }
}