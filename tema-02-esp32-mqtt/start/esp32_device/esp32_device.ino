// Device-ul IoT din tema 01, de data aceasta pe un ESP32-C3 Super Mini.
//
//   task periodic --(coada de alarme)--> task sporadic
//        |                                     |
//        +---- telemetry ---> MQTT <--- alarm -+
//                              |
//                          commands --> command_received() --(coada de comenzi)--> task aperiodic ---> status
//
// Placa: ESP32C3 Dev Module, USB CDC On Boot: Enabled. Serial Monitor: 115200 baud.

#include <ctype.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

#include "config.h"
#include "connection.h"

// Temperatura cipului, în °C, de la senzorul intern al ESP32-C3.
float read_temperature(void) {
    return temperatureRead();
}

// ---- parametrii taskului periodic ----
// volatile: sunt citiți de taskul periodic și modificați de taskul aperiodic
static volatile uint32_t g_period_ms = PERIOD_MS_INITIAL;
static volatile float g_threshold = THRESHOLD_INITIAL;

// ---- prioritățile taskurilor (între 2 și 19) ----
// periodic = cea mai mare (termene hard), sporadic = mijloc, aperiodic = cea mai mică (soft)
static const UBaseType_t PRIO_PERIODIC = 5;
static const UBaseType_t PRIO_SPORADIC = 4;
static const UBaseType_t PRIO_APERIODIC = 3;
static const uint32_t TASK_STACK = 4096;  // în octeți

// ---- elementele cozilor (se copiază prin valoare) ----
struct AlarmEvent {
    float temperature;
    float threshold;
};

struct Command {
    char text[COMMAND_MAX_LEN];
};

static QueueHandle_t g_alarm_queue = NULL;
static QueueHandle_t g_command_queue = NULL;

// ======================================================================
// TODO 1: TASK PERIODIC
// ======================================================================
static void periodic_task(void* arg) {
    (void)arg;
    TickType_t last = xTaskGetTickCount();  // eliberarea jobului curent pe grilă

    for (;;) {
        // ---- începutul jobului ----
        float t = read_temperature();
        float threshold = g_threshold;

        char payload[96];
        snprintf(payload, sizeof(payload), "%s;%lld;%.2f", DEVICE_ID, (long long)epoch_ms(), t);
        mqtt_publish("telemetry", payload);
        Serial.printf("[periodic] %s\n", payload);

        if (t > threshold) {
            AlarmEvent ev = {t, threshold};
            xQueueSend(g_alarm_queue, &ev, 0);  // timeout 0: nu blocăm taskul periodic
        }
        // ---- sfârșitul jobului ----

        // așteptare ABSOLUTĂ: următoarea eliberare = last + T, fără derivă
        TickType_t period = pdMS_TO_TICKS(g_period_ms);
        if (period == 0) period = 1;
        xTaskDelayUntil(&last, period);
    }
}

// ======================================================================
// TODO 2: TASK SPORADIC
// ======================================================================
static void sporadic_task(void* arg) {
    (void)arg;
    bool have_last = false;
    uint32_t last_release = 0;  // millis() la ultima alarmă publicată

    for (;;) {
        AlarmEvent ev;
        xQueueReceive(g_alarm_queue, &ev, portMAX_DELAY);  // așteaptă un eveniment

        uint32_t now = millis();  // timp monoton

        // interval minim între sosiri: evenimentele prea dese sunt respinse
        if (have_last && (uint32_t)(now - last_release) < MIN_INTERARRIVAL_MS) {
            Serial.printf("[sporadic] eveniment RESPINS (interval minim neatins): %.2f\n", ev.temperature);
            continue;
        }

        last_release = now;  // eliberarea acestui job
        have_last = true;

        char payload[96];
        snprintf(payload, sizeof(payload), "%s;%lld;%.2f;%.2f", DEVICE_ID, (long long)epoch_ms(),
                 ev.temperature, ev.threshold);
        mqtt_publish("alarm", payload);
        Serial.printf("[sporadic] ALARMA publicată: %s\n", payload);
    }
}

// ======================================================================
// TODO 3: TASK APERIODIC
// ======================================================================

// scoate spațiile de la început și de la sfârșit (modifică șirul pe loc)
static char* trim(char* s) {
    while (*s && isspace((unsigned char)*s)) s++;
    char* end = s + strlen(s);
    while (end > s && isspace((unsigned char)end[-1])) end--;
    *end = '\0';
    return s;
}

// Execută o comandă și scrie în reply răspunsul ("OK ..." sau "ERROR ...").
static void execute_command(const char* raw, char* reply, size_t size) {
    char buf[COMMAND_MAX_LEN];
    strncpy(buf, raw, sizeof(buf) - 1);
    buf[sizeof(buf) - 1] = '\0';
    for (char* p = buf; *p; p++) {
        if (*p == ';') *p = ',';  // ';' ar strica formatul mesajului de status
    }

    char* text = trim(buf);
    if (*text == '\0') {
        snprintf(reply, size, "ERROR comanda goala");
        return;
    }

    // despărțim numele comenzii de parametru
    char* arg = text;
    while (*arg && !isspace((unsigned char)*arg)) arg++;
    if (*arg) {
        *arg = '\0';
        arg = trim(arg + 1);
    }

    if (strcasecmp(text, "SET_INTERVAL") == 0) {
        if (*arg == '\0') {
            snprintf(reply, size, "ERROR SET_INTERVAL necesita un parametru: SET_INTERVAL <ms>");
            return;
        }
        char* end;
        long ms = strtol(arg, &end, 10);
        if (*end != '\0') {
            snprintf(reply, size, "ERROR parametru invalid: %s", arg);
        } else if (ms <= 0) {
            snprintf(reply, size, "ERROR intervalul trebuie sa fie pozitiv");
        } else {
            g_period_ms = (uint32_t)ms;
            snprintf(reply, size, "OK SET_INTERVAL %ld", ms);
        }
    } else if (strcasecmp(text, "SET_THRESHOLD") == 0) {
        if (*arg == '\0') {
            snprintf(reply, size, "ERROR SET_THRESHOLD necesita un parametru: SET_THRESHOLD <valoare>");
            return;
        }
        char* end;
        float value = strtof(arg, &end);
        if (*end != '\0') {
            snprintf(reply, size, "ERROR parametru invalid: %s", arg);
        } else {
            g_threshold = value;
            snprintf(reply, size, "OK SET_THRESHOLD %.2f", value);
        }
    } else if (strcasecmp(text, "RESET") == 0) {
        g_period_ms = PERIOD_MS_INITIAL;
        g_threshold = THRESHOLD_INITIAL;
        snprintf(reply, size, "OK RESET");
    } else if (strcasecmp(text, "STATUS") == 0) {
        snprintf(reply, size, "OK STATUS interval=%lu threshold=%.2f", (unsigned long)g_period_ms,
                 (double)g_threshold);
    } else {
        snprintf(reply, size, "ERROR comanda necunoscuta: %s", text);
    }
}

static void aperiodic_task(void* arg) {
    (void)arg;
    for (;;) {
        Command cmd;
        xQueueReceive(g_command_queue, &cmd, portMAX_DELAY);  // în ordinea sosirii

        // jobul: execută comanda (o comandă greșită dă ERROR, nu oprește taskul)
        char reply[128];
        execute_command(cmd.text, reply, sizeof(reply));

        char payload[192];
        snprintf(payload, sizeof(payload), "%s;%lld;%s", DEVICE_ID, (long long)epoch_ms(), reply);
        mqtt_publish("status", payload);
        Serial.printf("[aperiodic] %s -> %s\n", cmd.text, reply);
    }
}

// Apelată de clientul MQTT pentru fiecare comandă primită (vezi connection.h).
// Rulează pe taskul clientului MQTT: doar punem comanda în coadă.
void command_received(const char* text) {
    Serial.printf("[mqtt] comandă primită: %s\n", text);

    Command cmd;
    strncpy(cmd.text, text, COMMAND_MAX_LEN - 1);  // copiem textul: nu rămâne valid după apel
    cmd.text[COMMAND_MAX_LEN - 1] = '\0';

    if (g_command_queue == NULL || xQueueSend(g_command_queue, &cmd, 0) != pdTRUE) {
        Serial.println("[mqtt] coada de comenzi plină, comandă pierdută");
    }
}

void setup() {
    Serial.begin(115200);
    Serial.setTxTimeoutMs(0);
    uint32_t t = millis();
    while (!Serial && millis() - t < 3000) delay(10);
    delay(200);
    Serial.println();
    Serial.printf("=== tema 02: STUDENT %s, DEVICE_ID %s ===\n", STUDENT, DEVICE_ID);

    wifi_connect();
    time_sync();

    // TODO 0 (a): cozile, înainte de mqtt_start()
    g_alarm_queue = xQueueCreate(10, sizeof(AlarmEvent));
    g_command_queue = xQueueCreate(8, sizeof(Command));

    mqtt_start();

    // TODO 0 (b): taskurile, după mqtt_start()
    xTaskCreate(periodic_task, "periodic", TASK_STACK, NULL, PRIO_PERIODIC, NULL);
    xTaskCreate(sporadic_task, "sporadic", TASK_STACK, NULL, PRIO_SPORADIC, NULL);
    xTaskCreate(aperiodic_task, "aperiodic", TASK_STACK, NULL, PRIO_APERIODIC, NULL);
}

void loop() {
    // Totul se face în taskuri: loop() nu are nimic de făcut.
    delay(1000);
}