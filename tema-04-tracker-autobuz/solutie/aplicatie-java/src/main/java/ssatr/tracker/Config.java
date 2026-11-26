package ssatr.tracker;

/** Setarile aplicatiei. Cheile si parolele NU sunt aici: se citesc din variabile de mediu. */
final class Config {

    static final String STUDENT = "ananechita15";
    static final String DEVICE_ID = "esp32-1e62c4";

    static final String API_BASE = "https://control.aut.utcluj.ro/publictransport";

    static final String RABBIT_HOST = "control.aut.utcluj.ro";
    static final int RABBIT_PORT = 11156;
    static final String EXCHANGE = "cluj.vehicles";

    static final String MQTT_BROKER = "tcp://control.aut.utcluj.ro:11188";

    private Config() { }

    /** Citeste o variabila de mediu obligatorie (CTP_KEY, RABBIT_USER, RABBIT_PASS). */
    static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Lipseste variabila de mediu " + name);
        }
        return value;
    }
}