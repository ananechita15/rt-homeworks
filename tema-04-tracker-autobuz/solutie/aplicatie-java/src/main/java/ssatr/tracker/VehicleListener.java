package ssatr.tracker;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Asculta pozitiile vehiculelor pe exchange-ul fanout cluj.vehicles.
 * Isi creeaza propria coada temporara si o leaga de exchange; NU citeste din
 * coada comuna ctp.cluj.vehicles. Contul RabbitMQ vine din RABBIT_USER / RABBIT_PASS.
 */
class VehicleListener implements AutoCloseable {

    private final Connection connection;
    private final Channel channel;

    VehicleListener(Consumer<Model.Vehicle> onVehicle) throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(Config.RABBIT_HOST);
        factory.setPort(Config.RABBIT_PORT);
        factory.setUsername(Config.env("RABBIT_USER"));
        factory.setPassword(Config.env("RABBIT_PASS"));

        connection = factory.newConnection();
        channel = connection.createChannel();

        channel.exchangeDeclare(Config.EXCHANGE, "fanout");          // exact ca in ghid
        String queue = channel.queueDeclare().getQueue();            // coada temporara, doar a mea
        channel.queueBind(queue, Config.EXCHANGE, "");

        channel.basicConsume(queue, true, (tag, message) -> {
            try {
                String json = new String(message.getBody(), StandardCharsets.UTF_8);
                onVehicle.accept(parse(json));
            } catch (RuntimeException e) {
                // un mesaj stricat nu trebuie sa opreasca aplicatia
                System.err.println("[rabbit] mesaj ignorat: " + e.getMessage());
            }
        }, tag -> { });
    }

    /** Transforma un mesaj JSON intr-un vehicul; campurile lipsa primesc valori implicite. */
    static Model.Vehicle parse(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        return new Model.Vehicle(
                intField(o, "id", -1),
                textField(o, "label"),
                intField(o, "route_id", -1),
                textField(o, "trip_id"),
                doubleField(o, "lat", Double.NaN),
                doubleField(o, "lon", Double.NaN),
                doubleField(o, "speed", 0),
                (long) doubleField(o, "age", 0));
    }

    private static boolean missing(JsonObject o, String f) {
        JsonElement v = o.get(f);
        return v == null || v.isJsonNull();
    }

    private static int intField(JsonObject o, String f, int def) {
        return missing(o, f) ? def : o.get(f).getAsInt();
    }

    private static double doubleField(JsonObject o, String f, double def) {
        return missing(o, f) ? def : o.get(f).getAsDouble();
    }

    private static String textField(JsonObject o, String f) {
        return missing(o, f) ? "" : o.get(f).getAsString();
    }

    @Override
    public void close() throws IOException, TimeoutException {
        channel.close();
        connection.close();
    }
}