package ssatr.device;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;

/**
 * TASK SPORADIC: tratează alarmele de temperatură.
 *
 * Evenimentele sosesc în coada alarmEvents (puse de taskul periodic).
 * Intervalul minim între sosiri este MIN_INTERARRIVAL_MS: între două eliberări
 * ale taskului (două alarme publicate) trebuie să treacă cel puțin atât.
 * Intervalul este impus de cod: evenimentele care sosesc mai des sunt
 * respinse, ca să nu fie inundat brokerul.
 */
public class SporadicAlarmTask implements Runnable {

    static final long MIN_INTERARRIVAL_MS = 5000;

    private final String deviceId;
    private final MqttConnection mqtt;
    private final BlockingQueue<Double> alarmEvents;
    private final PeriodicTelemetryTask periodic;

    public SporadicAlarmTask(String deviceId, MqttConnection mqtt, BlockingQueue<Double> alarmEvents,
                             PeriodicTelemetryTask periodic) {
        this.deviceId = deviceId;
        this.mqtt = mqtt;
        this.alarmEvents = alarmEvents;
        this.periodic = periodic;
    }

    @Override
    public void run() {
        Long lastRelease = null;   // momentul ultimei alarme publicate (nanoTime)

        while (!Thread.currentThread().isInterrupted()) {
            double temperature;
            try {
                temperature = alarmEvents.take();   // așteaptă un eveniment
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            long now = System.nanoTime();

            if (lastRelease != null && now - lastRelease < MIN_INTERARRIVAL_MS * 1_000_000L) {
                System.out.println("[sporadic] eveniment RESPINS (interval minim neatins): "
                        + String.format(Locale.US, "%.2f", temperature));
                continue;
            }

            lastRelease = now;   // eliberarea acestui job

            String payload = deviceId + ";" + System.currentTimeMillis() + ";"
                    + String.format(Locale.US, "%.2f", temperature) + ";"
                    + String.format(Locale.US, "%.2f", periodic.getThreshold());
            mqtt.publish(Config.alarmTopic(deviceId), payload);
            System.out.println("[sporadic] ALARMA publicată: " + payload);
        }
    }
}