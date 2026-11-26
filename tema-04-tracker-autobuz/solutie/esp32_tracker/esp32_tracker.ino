// Tracker personal de autobuz, pe ESP32-C3 Super Mini.
//
//   Java --MQTT "commands"--> command_received --(coada comenzilor)--> task aperiodic
//                                                                         |
//   ISR buton BOOT --(coada butonului)--> task sporadic                   v
//                                              |               [ starea trackerului ] <-- mutex
//                                              |                          |
//                                              v                          v
//                                  MQTT "boarded" --> Java     task periodic: LED (T = 100 ms)
//
// Ce arata LED-ul (albastru, de pe placa):
//   licarire rapida / medie / lenta / foarte lenta = autobuzul e tot mai departe de statie
//   o licarire scurta la 3 s   = nu stim niciun autobuz (NOBUS sau date vechi)
//   aprins continuu 3 s        = ai apasat BOOT: te-ai urcat (BOARDED trimis)
//
// Placa: ESP32C3 Dev Module, USB CDC On Boot: Enabled. Serial Monitor: 115200 baud.

#include <ctype.h>
#include <stdlib.h>
#include <string.h>

#include "config.h"
#include "connection.h"

// ---- prioritatile taskurilor (intre 2 si 19) ----
// Rate Monotonic: perioada mai mica => prioritatea mai mare.
//   LED periodic (100 ms)          > sporadic (interval minim 1000 ms) > aperiodic (comenzi)
static const UBaseType_t PRIO_LED = 5;
static const UBaseType_t PRIO_BUTTON = 4;
static const UBaseType_t PRIO_COMMAND = 3;
static const uint32_t TASK_STACK = 4096;  // in octeti

// ======================================================================
// STAREA TRACKERULUI, partajata de taskuri si protejata de mutex
// ======================================================================
struct TrackerState {
    int32_t distance_m;         // distanta pana la cel mai apropiat autobuz; -1 = nu stim
    uint32_t updated_ms;        // millis() la ultimul DIST / NOBUS
    uint32_t boarded_until_ms;  // pana cand LED-ul sta aprins continuu (dupa BOARDED)
};

static TrackerState g_state;
static SemaphoreHandle_t g_state_mutex = NULL;

// Elementul cozii comenzilor: textul primit pe MQTT
struct Command {
    char text[COMMAND_MAX_LEN];
};
static QueueHandle_t g_command_queue = NULL;

// Elementul cozii butonului: momentul apasarii
struct ButtonPress {
    TickType_t tick;
};
static QueueHandle_t g_button_queue = NULL;

// Cate "tick-uri" de LED_PERIOD_MS dureaza jumatate din perioada de licarire,
// in functie de distanta: cu cat autobuzul e mai aproape, cu atat licareste mai repede.
static uint32_t half_period_ticks(int32_t distance_m) {
    if (distance_m < NEAR_M) return 1;     // 100 ms aprins, 100 ms stins
    if (distance_m < MEDIUM_M) return 3;   // 300 ms
    if (distance_m < FAR_M) return 8;      // 800 ms
    return 20;                             // 2 s
}

// ======================================================================
// TASK PERIODIC: LED-ul, T = LED_PERIOD_MS
// ======================================================================
static void led_task(void* arg) {
    (void)arg;
    TickType_t last = xTaskGetTickCount();  // eliberarea jobului curent pe grila
    uint32_t tick = 0;

    for (;;) {
        // ---- inceputul jobului ----
        // copiem starea cu mutexul luat cat mai putin
        xSemaphoreTake(g_state_mutex, portMAX_DELAY);
        TrackerState s = g_state;
        xSemaphoreGive(g_state_mutex);

        uint32_t now = millis();
        bool on;

        if ((int32_t)(s.boarded_until_ms - now) > 0) {
            on = true;                                    // BOARDED: aprins continuu
        } else if (s.distance_m < 0 || now - s.updated_ms > DATA_TIMEOUT_MS) {
            on = (tick % 30) == 0;                        // nu stim nimic: licarire scurta la 3 s
        } else {
            uint32_t half = half_period_ticks(s.distance_m);
            on = ((tick / half) % 2) == 0;                // licarire in ritmul distantei
        }

        digitalWrite(LED_PIN, on ? LOW : HIGH);           // LED-ul placii e aprins pe LOW
        tick++;
        // ---- sfarsitul jobului ----

        // asteptare ABSOLUTA: urmatoarea eliberare = last + T, fara deriva
        xTaskDelayUntil(&last, pdMS_TO_TICKS(LED_PERIOD_MS));
    }
}

// ======================================================================
// RUTINA DE INTRERUPERE A BUTONULUI BOOT
// ======================================================================
// Scurta: pune momentul apasarii in coada si atat. Fara Serial,
// fara mqtt_publish(), fara mutex.
void IRAM_ATTR button_isr(void) {
    ButtonPress press = {xTaskGetTickCountFromISR()};
    BaseType_t woken = pdFALSE;
    xQueueSendFromISR(g_button_queue, &press, &woken);
    portYIELD_FROM_ISR(woken);  // daca a fost trezit un task mai prioritar, ruleaza imediat
}

// ======================================================================
// TASK SPORADIC: apasarile butonului (BOARDED)
// ======================================================================
static void button_task(void* arg) {
    (void)arg;
    bool have_last = false;
    TickType_t last_accepted = 0;  // momentul ultimei apasari acceptate

    for (;;) {
        ButtonPress press;
        xQueueReceive(g_button_queue, &press, portMAX_DELAY);  // blocat pana vine o apasare

        // interval minim intre sosiri: apasarile prea dese sunt respinse
        // (aici se filtreaza si bounce-ul butonului)
        if (have_last && (TickType_t)(press.tick - last_accepted) < pdMS_TO_TICKS(MIN_INTERARRIVAL_MS)) {
            Serial.println("[buton] apasare RESPINSA (interval minim neatins)");
            continue;
        }
        last_accepted = press.tick;
        have_last = true;

        // utilizatorul a urcat: spunem aplicatiei Java si aprindem LED-ul continuu
        char payload[64];
        snprintf(payload, sizeof(payload), "%s;%lld;BOARDED", DEVICE_ID, (long long)epoch_ms());
        mqtt_publish("boarded", payload);

        xSemaphoreTake(g_state_mutex, portMAX_DELAY);
        g_state.boarded_until_ms = millis() + BOARDED_LED_MS;
        xSemaphoreGive(g_state_mutex);

        Serial.printf("[buton] apasare acceptata, trimis: %s\n", payload);
    }
}

// scoate spatiile si newline-urile de la inceput si de la sfarsit
static char* trim(char* s) {
    while (*s && isspace((unsigned char)*s)) s++;
    char* end = s + strlen(s);
    while (end > s && isspace((unsigned char)end[-1])) end--;
    *end = '\0';
    return s;
}

// ======================================================================
// TASK APERIODIC: executa comenzile primite de la aplicatia Java
// ======================================================================
//   DIST <metri>   distanta pana la cel mai apropiat autobuz
//   NOBUS          nu stim niciun autobuz
// O comanda gresita produce un mesaj ERROR si NU opreste taskul.
static void command_task(void* arg) {
    (void)arg;

    for (;;) {
        Command cmd;
        xQueueReceive(g_command_queue, &cmd, portMAX_DELAY);  // o comanda, in ordinea sosirii
        char* text = trim(cmd.text);

        if (strncmp(text, "DIST ", 5) == 0) {
            char* end = NULL;
            long meters = strtol(text + 5, &end, 10);
            if (end == text + 5 || *end != '\0' || meters < 0) {
                Serial.printf("[comanda] ERROR parametru invalid: %s\n", text);
                continue;
            }
            xSemaphoreTake(g_state_mutex, portMAX_DELAY);
            g_state.distance_m = (int32_t)meters;
            g_state.updated_ms = millis();
            xSemaphoreGive(g_state_mutex);
            Serial.printf("[comanda] OK DIST %ld\n", meters);
        } else if (strcmp(text, "NOBUS") == 0) {
            xSemaphoreTake(g_state_mutex, portMAX_DELAY);
            g_state.distance_m = -1;
            g_state.updated_ms = millis();
            xSemaphoreGive(g_state_mutex);
            Serial.println("[comanda] OK NOBUS");
        } else {
            Serial.printf("[comanda] ERROR comanda necunoscuta: %s\n", text);
        }
    }
}

// Apelata de clientul MQTT pentru fiecare comanda primita (vezi connection.h).
// Ruleaza pe taskul clientului MQTT: doar punem textul in coada, fara sa-l executam.
void command_received(const char* text) {
    Command cmd;
    strncpy(cmd.text, text, sizeof(cmd.text) - 1);
    cmd.text[sizeof(cmd.text) - 1] = '\0';

    if (g_command_queue == NULL || xQueueSend(g_command_queue, &cmd, 0) != pdTRUE) {
        Serial.println("[mqtt] coada comenzilor plina, comanda pierduta");
    }
}

void setup() {
    Serial.begin(115200);
    Serial.setTxTimeoutMs(0);
    uint32_t t = millis();
    while (!Serial && millis() - t < 3000) delay(10);
    delay(200);
    Serial.println();
    Serial.printf("=== tema 04: tracker autobuz, STUDENT %s, DEVICE_ID %s ===\n", STUDENT, DEVICE_ID);

    pinMode(LED_PIN, OUTPUT);
    digitalWrite(LED_PIN, HIGH);  // LED stins
    pinMode(BUTTON_PIN, INPUT_PULLUP);

    wifi_connect();
    time_sync();

    // mutexul, starea initiala si cozile, INAINTE de mqtt_start()
    g_state_mutex = xSemaphoreCreateMutex();
    g_command_queue = xQueueCreate(8, sizeof(Command));
    g_button_queue = xQueueCreate(10, sizeof(ButtonPress));
    g_state.distance_m = -1;
    g_state.updated_ms = millis();
    g_state.boarded_until_ms = 0;

    mqtt_start();

    // taskurile, dupa mqtt_start(), apoi intreruperea butonului
    xTaskCreate(led_task, "led", TASK_STACK, NULL, PRIO_LED, NULL);
    xTaskCreate(button_task, "button", TASK_STACK, NULL, PRIO_BUTTON, NULL);
    xTaskCreate(command_task, "command", TASK_STACK, NULL, PRIO_COMMAND, NULL);
    attachInterrupt(digitalPinToInterrupt(BUTTON_PIN), button_isr, FALLING);
}

void loop() {
    // Totul se face in taskuri: loop() nu are nimic de facut.
    delay(1000);
}