package ssatr.tracker;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;

/**
 * Legatura cu placa ESP32 prin MQTT.
 *
 *   Java -> placa : topic .../commands   mesaje "DIST <metri>" sau "NOBUS"
 *   placa -> Java : topic .../boarded    mesaj  "deviceId;timestamp;BOARDED"
 */
class BoardLink implements AutoCloseable {

    private static final String BASE = "ssatr/" + Config.STUDENT + "/devices/" + Config.DEVICE_ID + "/";
    static final String COMMANDS_TOPIC = BASE + "commands";
    static final String BOARDED_TOPIC = BASE + "boarded";

    private final MqttClient client;

    BoardLink(Runnable onBoarded) throws MqttException {
        // id unic pe broker: doi clienti cu acelasi id se deconecteaza unul pe altul
        String clientId = "tracker-java-" + Config.STUDENT + "-" + System.currentTimeMillis();
        client = new MqttClient(Config.MQTT_BROKER, clientId, new MemoryPersistence());

        client.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverUri) {
                try {
                    // abonarea se reface la fiecare (re)conectare
                    client.subscribe(BOARDED_TOPIC, 1);
                } catch (MqttException e) {
                    System.err.println("[mqtt] abonare esuata: " + e.getMessage());
                }
            }

            @Override
            public void connectionLost(Throwable cause) {
                System.err.println("[mqtt] conexiune pierduta, se reincearca");
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                String text = new String(message.getPayload(), StandardCharsets.UTF_8);
                System.out.println("[mqtt] de la placa: " + text);
                if (text.contains("BOARDED")) {
                    onBoarded.run();
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) { }
        });

        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        client.connect(options);
    }

    /** Trimite placii distanta pana la cel mai apropiat autobuz, in metri. */
    void sendDistance(long meters) {
        send("DIST " + meters);
    }

    /** Spune placii ca nu stim niciun autobuz acum. */
    void sendNoBus() {
        send("NOBUS");
    }

    private void send(String text) {
        try {
            MqttMessage message = new MqttMessage(text.getBytes(StandardCharsets.UTF_8));
            message.setQos(1);
            client.publish(COMMANDS_TOPIC, message);
        } catch (MqttException e) {
            System.err.println("[mqtt] nu am putut trimite \"" + text + "\": " + e.getMessage());
        }
    }

    @Override
    public void close() throws MqttException {
        client.disconnect();
        client.close();
    }
}