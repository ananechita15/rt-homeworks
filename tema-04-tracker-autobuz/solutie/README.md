\# Tema 4: Tracker personal de autobuz pe ESP32-C3



Student: `ananechita15` · Placa: ESP32-C3 Super Mini (`esp32-1e62c4`)



Sistemul urmărește un autobuz real (CTP Cluj) și arată pe LED-ul plăcii cât de aproape este de stația aleasă. Când utilizatorul urcă în autobuz, apasă BOOT, iar confirmarea ajunge în aplicația Java.



\## Ce face utilizatorul



1\. Pornește aplicația Java și alege \*\*linia\*\*, \*\*sensul\*\* și \*\*stația\*\* (din datele reale ale API-ului).

2\. Urmărește \*\*LED-ul albastru\*\* de pe placă: licărește cu atât mai repede cu cât autobuzul care vine spre stație e mai aproape.

3\. Când urcă în autobuz, apasă \*\*BOOT\*\*. Placa trimite `BOARDED`, aplicația Java afișează confirmarea și se oprește.



\## Arhitectura



```mermaid

flowchart LR

&#x20;   L\["CTP Cluj live<br/>(serverul cursului)"]

&#x20;   R(("RabbitMQ<br/>control.aut.utcluj.ro:11156<br/>exchange cluj.vehicles"))

&#x20;   J\["Aplicația Java<br/>(REST + RabbitMQ + MQTT)"]

&#x20;   B(("Broker MQTT<br/>control.aut.utcluj.ro:11188"))

&#x20;   E\["ESP32-C3 Super Mini<br/>(taskuri FreeRTOS)"]



&#x20;   L -- "poziții vehicule" --> R

&#x20;   R -- "coadă proprie, legată de exchange" --> J

&#x20;   L -- "API REST: linii, stații, traseu" --> J

&#x20;   J -- "DIST / NOBUS (commands)" --> B

&#x20;   B -- "DIST / NOBUS" --> E

&#x20;   E -- "BOARDED (boarded)" --> B

&#x20;   B -- "BOARDED" --> J

```



\### Aplicația Java (`aplicatie-java/`)



| Clasă | Rol |

|---|---|

| `TrackerApp` | alege linia/sensul/stația; la fiecare 5 s afișează și trimite placii cel mai apropiat autobuz; se oprește la `BOARDED` sau la Enter |

| `ApiClient` | API REST (`/api/lines`, `/api/line?route\_id=N`), cheia în headerul `X-API-Key` |

| `VehicleListener` | consumator RabbitMQ: \*\*coadă proprie\*\* (temporară) legată de exchange-ul `cluj.vehicles`; nu citește din coada comună `ctp.cluj.vehicles` |

| `NearestBus` | ține ultima poziție a fiecărui vehicul și calculează cel mai apropiat vehicul care \*\*încă vine\*\* spre stație |

| `BoardLink` | legătura MQTT cu placa (trimite `DIST`/`NOBUS`, primește `BOARDED`) |

| `Geo`, `Model`, `Config` | distanța haversine, modelul de date, setările |



\*\*Calculul distanței.\*\* Traseul sensului ales (`shape` din API) este o listă de puncte. Aplicația găsește punctul traseului cel mai apropiat de stație și, pentru fiecare vehicul, punctul cel mai apropiat de el. Dacă vehiculul se află pe traseu \*înaintea\* stației, distanța este lungimea traseului dintre ele. Dacă se află \*după\* stație (a trecut deja), este ignorat. Vehiculele aflate la mai mult de 150 m de traseu sunt ignorate.



\*\*Date reale, cu imperfecțiuni.\*\* Câmpurile lipsă sau `null` (`trip\_id`, `route\_id`, `lat`, `speed`) primesc valori implicite și nu opresc aplicația; un mesaj stricat este ignorat; un vehicul care nu mai trimite nimic 2 minute este considerat dispărut; se ține doar ultima poziție a fiecărui vehicul (după `id`).



\## Mesajele MQTT



Toate sub `ssatr/ananechita15/devices/esp32-1e62c4/`.



| Topic | Direcție | Mesaj | Înseamnă |

|---|---|---|---|

| `commands` | Java → placă | `DIST <metri>` | cel mai apropiat autobuz care vine este la atâția metri pe traseu |

| `commands` | Java → placă | `NOBUS` | nu se cunoaște niciun autobuz care vine |

| `boarded` | placă → Java | `deviceId;timestamp;BOARDED` | utilizatorul a apăsat BOOT: a urcat în autobuz (`timestamp` = milisecunde Unix) |



Mesajele sunt text simplu, cu QoS 1. Placa se abonează la `commands` la fiecare (re)conectare.



\## Taskurile de pe placă



| Task | Tip | Perioadă / interval minim | Prioritate | Ce face |

|---|---|---|---|---|

| `led\_task` | \*\*periodic\*\* | T = 100 ms | 5 | citește starea (cu mutex), alege starea LED-ului, așteptare absolută `xTaskDelayUntil` |

| `button\_task` | \*\*sporadic\*\* | interval minim între sosiri 1000 ms | 4 | preia apăsările din coadă, \*\*respinge\*\* cele prea dese, trimite `BOARDED` |

| `command\_task` | \*\*aperiodic\*\* | fără frecvență garantată | 3 | execută `DIST` / `NOBUS` primite pe MQTT; o comandă greșită dă `ERROR` și nu oprește taskul |

| `button\_isr` | \*\*rutină de întrerupere\*\* (BOOT, pin 9, front căzător) | — | — | pune momentul apăsării în coadă; fără Serial, fără mutex, fără publicare |



\*\*De ce aceste alegeri.\*\*

\- LED-ul trebuie să licărească într-un ritm regulat, deci este un task \*\*periodic\*\*. Perioada de 100 ms este cea mai mică unitate de licărire (cel mai rapid ritm = 100 ms aprins, 100 ms stins).

\- Apăsările de buton apar oricând, dar nu are sens să fie acceptate mai des de o dată pe secundă (și astfel se filtrează și zgomotul butonului): este un task \*\*sporadic\*\* cu interval minim impus în cod.

\- Comenzile de la aplicația Java sosesc când vrea aplicația, fără garanție de frecvență: task \*\*aperiodic\*\*, cu cerințe „cât mai repede", fără termen strict.

\- Prioritățile urmează Rate Monotonic: perioada cea mai mică (LED, 100 ms) are prioritatea cea mai mare; sporadicul (interval minim 1000 ms) urmează; aperiodicul, fără termen strict, are cea mai mică prioritate.

\- Starea comună (`distance\_m`, momentul ultimei actualizări, `boarded\_until\_ms`) este protejată de un \*\*mutex\*\* ținut cât mai puțin; nu se face `Serial` sau publicare MQTT cât timp mutexul este luat.

\- `command\_received()` rulează pe taskul clientului MQTT: doar copiază textul într-o coadă (timeout 0). Cozile și mutexul se creează înainte de `mqtt\_start()`.



\## Ce înseamnă LED-ul și butonul



LED-ul albastru de pe placă (pin 8, aprins pe LOW):



| Ce se vede | Înseamnă |

|---|---|

| licărire rapidă (100 ms aprins / 100 ms stins) | autobuzul e sub 300 m de stație |

| licărire medie (300 ms) | între 300 și 1000 m |

| licărire lentă (800 ms) | între 1000 și 2500 m |

| licărire foarte lentă (2 s) | peste 2500 m |

| o licărire scurtă la 3 s | nu se cunoaște niciun autobuz (`NOBUS`) sau nu au venit date de 30 s |

| aprins continuu 3 s | ai apăsat BOOT: `BOARDED` trimis |



Butonul \*\*BOOT\*\* (pin 9): „am urcat în autobuz". Apăsările mai dese de o secundă sunt respinse.



\## Cum se rulează



\*\*Secrete.\*\* Cheia API, contul RabbitMQ și parola Wi-Fi \*\*nu sunt în repo\*\*. Aplicația Java le citește din variabile de mediu; în `esp32\_tracker/config.h` rămâne rețeaua `iot-demo`.



\*\*1. Aplicația Java\*\* (Java 17+, Maven), din `aplicatie-java/`, în PowerShell:



```text

$env:CTP\_KEY = "<cheia API primită la laborator>"

$env:RABBIT\_USER = "<utilizator RabbitMQ>"

$env:RABBIT\_PASS = "<parola RabbitMQ>"

mvn -q compile exec:java

```



Se introduc numărul liniei (de exemplu `21`), sensul și stația din liste.



\*\*2. Placa:\*\* în Arduino IDE se deschide `esp32\_tracker/esp32\_tracker.ino`; placa `ESP32C3 Dev Module`, `USB CDC On Boot: Enabled`, Serial Monitor 115200. În `config.h` se pune local rețeaua Wi-Fi 2.4 GHz (nu se face commit cu parola). Upload.



\*\*3. Verificare fără placă:\*\* cu MQTTX, abonare la `ssatr/ananechita15/devices/esp32-1e62c4/commands` (se văd `DIST ...` / `NOBUS`); publicare pe `.../boarded` a mesajului `esp32-1e62c4;0;BOARDED` oprește aplicația Java.



\## Limitări



\- Distanța este pe traseu până la cel mai apropiat punct al traseului de stație, deci are o eroare de ordinul zecilor de metri.

\- Sursa nu oferă orare sau estimări de sosire; „cât de aproape" înseamnă doar distanța actuală, nu timpul de sosire.

\- Un vehicul care a trecut de stație este ignorat până când parcurge din nou traseul.

