// Setarile trackerului de autobuz si numele topic-urilor MQTT.
//
// Brokerul este partajat de toti studentii. STUDENT este identic cu
// Config.STUDENT din aplicatia Java.
//
// ATENTIE: in repo ramane reteaua "iot-demo". Daca puneti local hotspot-ul
// telefonului, NU faceti commit cu parola voastra Wi-Fi.

#pragma once

#include <Arduino.h>

// Reteaua Wi-Fi. ESP32-C3 merge doar pe 2.4 GHz.
const char* const WIFI_SSID = "iot-demo";
const char* const WIFI_PASSWORD = "demo123!";

const char* const BROKER_URI = "mqtt://control.aut.utcluj.ro:11188";

// serverul de la care placa ia ora exacta (SNTP), pentru timestamp-uri
const char* const NTP_SERVER = "pool.ntp.org";

const char* const STUDENT = "ananechita15";

// ID-ul device-ului: "esp32-" + ultimii 3 octeti ai adresei MAC (vezi connection.cpp)
extern const char* const DEVICE_ID;

// Pinii placii ESP32-C3 Super Mini
const int BUTTON_PIN = 9;  // butonul BOOT: LOW cat timp este apasat (INPUT_PULLUP)
const int LED_PIN = 8;     // LED-ul albastru de pe placa: aprins pe LOW

// Taskul periodic al LED-ului
const uint32_t LED_PERIOD_MS = 100;

// Intervalul minim intre doua apasari acceptate ale butonului (taskul sporadic)
const uint32_t MIN_INTERARRIVAL_MS = 1000;

// Daca nu primim DIST/NOBUS atat timp, consideram ca nu mai stim nimic
const uint32_t DATA_TIMEOUT_MS = 30000;

// Cat timp ramane LED-ul aprins continuu dupa ce utilizatorul a urcat (BOARDED)
const uint32_t BOARDED_LED_MS = 3000;

// Pragurile de distanta (metri) pentru ritmul LED-ului
const int32_t NEAR_M = 300;     // sub: licarire rapida
const int32_t MEDIUM_M = 1000;  // sub: licarire medie
const int32_t FAR_M = 2500;     // sub: licarire lenta; peste: foarte lenta

// Scrie in buf topic-ul "ssatr/<STUDENT>/devices/<DEVICE_ID>/<kind>". kind este:
//   "commands"  Java -> placa:  "DIST <metri>" sau "NOBUS"
//   "boarded"   placa -> Java:  "deviceId;timestamp;BOARDED"
inline void make_topic(char* buf, size_t size, const char* kind) {
    snprintf(buf, size, "ssatr/%s/devices/%s/%s", STUDENT, DEVICE_ID, kind);
}