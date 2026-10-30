package ssatr.service;

/**
 * SetÄrile aplicaČ›iei Č™i numele topic-urilor MQTT.
 *
 * Brokerul este partajat de toČ›i studenČ›ii. ĂŽnlocuiČ›i STUDENT cu numele vostru
 * (fÄrÄ spaČ›ii sau diacritice), identic Ă®n ambele aplicaČ›ii.
 */
public class Config {

    public static final String BROKER = "tcp://control.aut.utcluj.ro:11188";

    public static final String STUDENT = "ananechita15";

    private static final String DEVICES = "ssatr/" + STUDENT + "/devices/";

    /** date periodice: "deviceId;timestamp;temperatura" */
    public static String telemetryTopic(String deviceId) {
        return DEVICES + deviceId + "/telemetry";
    }

    /** alarme (sporadice): "deviceId;timestamp;temperatura;prag" */
    public static String alarmTopic(String deviceId) {
        return DEVICES + deviceId + "/alarm";
    }

    /** comenzi de la business service cÄtre device: "SET_INTERVAL 2000" etc. */
    public static String commandTopic(String deviceId) {
        return DEVICES + deviceId + "/commands";
    }

    /** confirmÄri trimise de device dupÄ executarea unei comenzi: "deviceId;timestamp;text" */
    public static String statusTopic(String deviceId) {
        return DEVICES + deviceId + "/status";
    }
}
