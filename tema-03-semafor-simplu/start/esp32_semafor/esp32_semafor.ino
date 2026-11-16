// Semafor pentru pietoni, cu buton, pe ESP32-C3 Super Mini.
//
//   ISR buton BOOT ----+
//                      +--(coada butonului)--> task sporadic
//   command_received --+   (BUTTON)                 |
//                                                   v
//                                         [ starea semaforului ] <-- mutex
//                                            ^          |
//                                            |          v
//                         task periodic de control   task periodic de ieșire
//                         (fazele, T = 100 ms)       (lămpile, T = 500 ms) --> lights
//
// Placa: ESP32C3 Dev Module, USB CDC On Boot: Enabled. Serial Monitor: 115200 baud.

#include <ctype.h>
#include <string.h>

#include "config.h"
#include "connection.h"

// Fazele semaforului. Numele din PHASE_NAMES sunt cele din mesajul "lights".
enum Phase { CAR_GREEN, CAR_YELLOW, ALL_RED, PED_GREEN };
const char* const PHASE_NAMES[] = {"CAR_GREEN", "CAR_YELLOW", "ALL_RED", "PED_GREEN"};

// Culorile unei lămpi, cu numele din mesajul "lights".
enum Light { RED, YELLOW, GREEN };
const char* const LIGHT_NAMES[] = {"RED", "YELLOW", "GREEN"};

// ---- prioritățile taskurilor (între 2 și 19) ----
// Rate Monotonic: perioada mai mică => prioritatea mai mare.
// control (100 ms) > ieșire (500 ms); sporadicul e scurt și rar.
static const UBaseType_t PRIO_CONTROL = 6;
static const UBaseType_t PRIO_OUTPUT = 5;
static const UBaseType_t PRIO_BUTTON = 4;
static const uint32_t TASK_STACK = 4096;  // în octeți

// ======================================================================
// TODO 0 (c): STAREA SEMAFORULUI, partajată de taskuri și protejată de mutex
// ======================================================================
struct SemaforState {
    Phase phase;                // faza curentă
    uint32_t phase_entered_ms;  // millis() la intrarea în faza curentă
    bool request;               // cererea pietonului (lampa AȘTEPTAȚI)
};

static SemaforState g_state;
static SemaphoreHandle_t g_state_mutex = NULL;

// Elementul cozii butonului: momentul apăsării, luat când apăsarea intră în
// coadă (în ISR sau în command_received()), nu când o preia taskul.
struct ButtonPress {
    TickType_t tick;
};

static QueueHandle_t g_button_queue = NULL;

// ======================================================================
// TODO 1: TASK PERIODIC DE CONTROL, T = CONTROL_PERIOD_MS
// ======================================================================
static void control_task(void* arg) {
    (void)arg;
    TickType_t last = xTaskGetTickCount();  // eliberarea jobului curent pe grilă

    for (;;) {
        // ---- începutul jobului ----
        bool changed = false;
        Phase next = CAR_GREEN;

        xSemaphoreTake(g_state_mutex, portMAX_DELAY);
        uint32_t now = millis();
        uint32_t elapsed = now - g_state.phase_entered_ms;  // cât durează faza curentă
        next = g_state.phase;

        // condiția de ieșire din faza curentă (tabelul din README.md)
        switch (g_state.phase) {
            case CAR_GREEN:
                if (elapsed >= CAR_GREEN_MIN_MS && g_state.request) next = CAR_YELLOW;
                break;
            case CAR_YELLOW:
                if (elapsed >= CAR_YELLOW_MS) next = ALL_RED;
                break;
            case ALL_RED:
                if (elapsed >= ALL_RED_MS) next = PED_GREEN;
                break;
            case PED_GREEN:
                if (elapsed >= PED_GREEN_MS) next = CAR_GREEN;
                break;
        }

        if (next != g_state.phase) {
            g_state.phase = next;
            g_state.phase_entered_ms = now;      // momentul intrării în faza nouă
            if (next == PED_GREEN) {
                g_state.request = false;         // pietonul a primit ce a cerut
            }
            changed = true;
        }
        xSemaphoreGive(g_state_mutex);
        // ---- sfârșitul jobului ----

        // Serial doar după ce am eliberat mutexul
        if (changed) {
            Serial.printf("[control] faza nouă: %s\n", PHASE_NAMES[next]);
        }

        // așteptare ABSOLUTĂ: următoarea eliberare = last + T, fără derivă
        xTaskDelayUntil(&last, pdMS_TO_TICKS(CONTROL_PERIOD_MS));
    }
}

// ======================================================================
// TODO 2: TASK PERIODIC DE IEȘIRE, T = OUTPUT_PERIOD_MS
// ======================================================================
static void output_task(void* arg) {
    (void)arg;
    TickType_t last = xTaskGetTickCount();  // eliberarea jobului curent pe grilă

    for (;;) {
        // ---- începutul jobului ----
        // 1. copiem starea, cu mutexul luat cât mai puțin
        xSemaphoreTake(g_state_mutex, portMAX_DELAY);
        SemaforState s = g_state;
        xSemaphoreGive(g_state_mutex);

        uint32_t now = millis();

        // 2. lămpile din faza copiată
        Light cars = RED;
        Light peds = RED;
        switch (s.phase) {
            case CAR_GREEN:  cars = GREEN;  peds = RED;   break;
            case CAR_YELLOW: cars = YELLOW; peds = RED;   break;
            case ALL_RED:    cars = RED;    peds = RED;   break;
            case PED_GREEN:  cars = RED;    peds = GREEN; break;
        }

        // 3. secundele rămase din verdele pietonilor, rotunjite în sus
        int seconds = 0;
        if (s.phase == PED_GREEN) {
            uint32_t elapsed = now - s.phase_entered_ms;
            uint32_t remaining = (elapsed >= PED_GREEN_MS) ? 0 : (PED_GREEN_MS - elapsed);
            seconds = (int)((remaining + 999) / 1000);
        }

        // 4. publicăm mesajul lights (fără mutex)
        char payload[128];
        snprintf(payload, sizeof(payload), "%s;%lld;%s;%s;%s;%d;%d", DEVICE_ID, (long long)epoch_ms(),
                 PHASE_NAMES[s.phase], LIGHT_NAMES[cars], LIGHT_NAMES[peds], s.request ? 1 : 0, seconds);
        mqtt_publish("lights", payload);

        // 5. LED-ul plăcii: aprins pe LOW cât timp lampa AȘTEPTAȚI e aprinsă
        digitalWrite(LED_PIN, s.request ? LOW : HIGH);
        // ---- sfârșitul jobului ----

        // așteptare ABSOLUTĂ: următoarea eliberare = last + T, fără derivă
        xTaskDelayUntil(&last, pdMS_TO_TICKS(OUTPUT_PERIOD_MS));
    }
}

// ======================================================================
// TODO 3: RUTINA DE ÎNTRERUPERE A BUTONULUI BOOT
// ======================================================================
// Scurtă: pune momentul apăsării în coadă și atât. Fără Serial,
// fără mqtt_publish(), fără mutex.
void IRAM_ATTR button_isr(void) {
    ButtonPress press = {xTaskGetTickCountFromISR()};
    BaseType_t woken = pdFALSE;
    xQueueSendFromISR(g_button_queue, &press, &woken);
    portYIELD_FROM_ISR(woken);  // dacă a fost trezit un task mai prioritar, rulează imediat
}

// ======================================================================
// TODO 3: TASK SPORADIC (apăsările butonului)
// ======================================================================
static void button_task(void* arg) {
    (void)arg;
    bool have_last = false;
    TickType_t last_accepted = 0;  // momentul ultimei apăsări acceptate

    for (;;) {
        ButtonPress press;
        xQueueReceive(g_button_queue, &press, portMAX_DELAY);  // blocat până vine o apăsare

        // interval minim între sosiri: apăsările prea dese sunt respinse
        // (aici se filtrează și bounce-ul butonului)
        if (have_last && (TickType_t)(press.tick - last_accepted) < pdMS_TO_TICKS(MIN_INTERARRIVAL_MS)) {
            Serial.println("[buton] apăsare RESPINSĂ (interval minim neatins)");
            continue;
        }

        last_accepted = press.tick;
        have_last = true;

        // înregistrăm cererea pietonului, cu mutexul luat cât mai puțin
        xSemaphoreTake(g_state_mutex, portMAX_DELAY);
        g_state.request = true;
        xSemaphoreGive(g_state_mutex);

        Serial.println("[buton] apăsare acceptată: cerere pentru pietoni");
    }
}

// scoate spațiile și newline-urile de la început și de la sfârșit
static char* trim(char* s) {
    while (*s && isspace((unsigned char)*s)) s++;
    char* end = s + strlen(s);
    while (end > s && isspace((unsigned char)end[-1])) end--;
    *end = '\0';
    return s;
}

// Apelată de clientul MQTT pentru fiecare comandă primită (vezi connection.h).
// Rulează pe taskul clientului MQTT: doar punem apăsarea în coadă.
void command_received(const char* text) {
    Serial.printf("[mqtt] comandă primită: %s\n", text);

    char buf[COMMAND_MAX_LEN];
    strncpy(buf, text, sizeof(buf) - 1);
    buf[sizeof(buf) - 1] = '\0';

    if (strcmp(trim(buf), "BUTTON") != 0) {
        return;  // orice altă comandă se ignoră
    }

    ButtonPress press = {xTaskGetTickCount()};  // momentul sosirii, luat aici
    if (g_button_queue == NULL || xQueueSend(g_button_queue, &press, 0) != pdTRUE) {
        Serial.println("[mqtt] coada butonului plină, apăsare pierdută");
    }
}

void setup() {
    Serial.begin(115200);
    Serial.setTxTimeoutMs(0);
    uint32_t t = millis();
    while (!Serial && millis() - t < 3000) delay(10);
    delay(200);
    Serial.println();
    Serial.printf("=== tema 03: STUDENT %s, DEVICE_ID %s ===\n", STUDENT, DEVICE_ID);

    pinMode(LED_PIN, OUTPUT);
    digitalWrite(LED_PIN, HIGH);  // LED stins
    pinMode(BUTTON_PIN, INPUT_PULLUP);

    wifi_connect();
    time_sync();

    // TODO 0 (a): mutexul, starea inițială și coada, înainte de mqtt_start()
    g_state_mutex = xSemaphoreCreateMutex();
    g_button_queue = xQueueCreate(10, sizeof(ButtonPress));
    g_state.phase = CAR_GREEN;
    g_state.phase_entered_ms = millis();
    g_state.request = false;

    mqtt_start();

    // TODO 0 (b): taskurile, după mqtt_start(), apoi întreruperea butonului
    xTaskCreate(control_task, "control", TASK_STACK, NULL, PRIO_CONTROL, NULL);
    xTaskCreate(output_task, "output", TASK_STACK, NULL, PRIO_OUTPUT, NULL);
    xTaskCreate(button_task, "button", TASK_STACK, NULL, PRIO_BUTTON, NULL);
    attachInterrupt(digitalPinToInterrupt(BUTTON_PIN), button_isr, FALLING);
}

void loop() {
    // Totul se face în taskuri: loop() nu are nimic de făcut.
    delay(1000);
}