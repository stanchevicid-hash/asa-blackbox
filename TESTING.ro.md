# ASA Black Box 1.5.0: testare pe robot

Timp: cam 1 oră. Aveți nevoie de: un robot cu Control Hub (și Expansion Hub, dacă aveți), Driver Hub, un telefon sau
laptop, o baterie încărcată, un suport ca roțile să stea în aer.

Textele citate mai jos sunt în engleză: în INIT-ul programului „ASA Black Box” apăsați **X** ca să treceți pe engleză
(sau comparați după sens în limba voastră).

Cum notați rezultatul: la fiecare pas puneți ✅ (cum scrie), ⚠️ (altfel, dar merge) sau ❌ (nu merge).
La fiecare ⚠️ și ❌ faceți o poză la ecranul Driver Hub și/sau la pagină.

---

## 0. Pregătire

| # | Ce faceți | Ce trebuie să vedeți |
|---|---|---|
| 0.1 | Laptopul în Wi-Fi-ul robotului, `http://192.168.43.1:8080` → OnBot Java. **Descărcați o copie a întregului cod al echipei** (pentru orice eventualitate) | copia pe laptop |
| 0.2 | Dacă era instalat ASA 1.4: ștergeți fișierele lui `Rule`, `Live`, `Recorder`, `Ftc`, `Dashboard`, `WpiLog`, `BatteryHealth`, `CenterOfGravity` | fișierele nu mai sunt |
| 0.3 | Luați acest repository (`git clone https://github.com/stanchevicid-hash/asa-blackbox` sau Code → Download ZIP). Încărcați în `teamcode` toate cele 17 fișiere din folderul lui `onbot/` (fără subfoldere). Build | „Build successful”. La „bad source file” apăsați Build din nou (până la 3 ori) |
| 0.4 | Driver Hub: ⋮ → **Restart Robot** | **ASA Black Box** e în lista de programe |

## 1. Cei 7 pași principali (din README)

| # | Ce faceți | Ce trebuie să vedeți |
|---|---|---|
| 1 | Alegeți **ASA Black Box**, INIT (fără START) | 🟢 „Every match is recorded (hooked in at power-on)” și rândul „RESULTS: … :8099” |
| 2 | Telefonul în Wi-Fi-ul robotului, deschideți adresa de la pasul 1 | pagina cu logoul ASA, un punct verde „robot online” sus |
| 3 | În ASA Black Box: **RB** până la „Recording test”, START. Robotul nu se mișcă, ~16 s | trei rânduri verzi: ciclul cu înregistrare cam la fel ca fără; cam numărul așteptat de măsurători; encoderele citite la fiecare motor |
| 4 | Robotul pe suport. ASA Black Box → „Health check”, START, **Y** | un „OK … encoder counts up” verde la fiecare motor. Roșu sau galben e o problemă reală: un cablu, un port sau o direcție |
| 5 | Rulați **propriul** TeleOp: INIT, START, conduceți un minut, STOP | în timpul mersului, grafice live pe pagină (fila „Live” / „Acum”); după STOP se deschide înregistrarea cu numele TeleOp-ului vostru |
| 6 | Din nou INIT la ASA Black Box | „Newest recording: <TeleOp-ul vostru>, ora” |
| 7 | Acolo, **Y** → „Automatic recording is off”, rulați TeleOp-ul | nicio înregistrare nouă. Apoi reporniți-o (**Y**) |

**Faceți neapărat o poză la pasul 3**: avem nevoie de ecranul acesta în orice caz.

## 2. Erorile reparate

Le-a găsit o verificare a codului. Reparațiile trec testele pe calculator, dar nimeni nu le-a văzut încă pe un robot real.

### 2.1 Encodere și direcție (cel mai important)

| # | Ce faceți | Ce trebuie să vedeți |
|---|---|---|
| 2.1.1 | TeleOp-ul vostru: mergeți **înainte** 2-3 s, STOP. Pe pagină deschideți înregistrarea → „Graphs for a detailed look” → `pos` la fiecare roată | la toate roțile `pos` **crește** (ca `getCurrentPosition()` din codul vostru), nu scade |
| 2.1.2 | Cu **Expansion Hub**: la fel pentru motoarele lui | motoarele de pe Expansion Hub au valorile lor, nu o copie a celor de pe Control Hub |
| 2.1.3 | Raportul acestei înregistrări | fără un fals „the encoder counts against the power” și fără „a hub stopped answering” |

### 2.2 Programul nu e încetinit

| # | Ce faceți | Ce trebuie să vedeți |
|---|---|---|
| 2.2.1 | TeleOp-ul vostru cu IMU: INIT | INIT durează cât fără ASA. IMU-ul merge (unghiul se schimbă la întoarcere) |
| 2.2.2 | Conduceți, comparați cu senzația fără ASA (opriți-o cu Y și conduceți din nou) | nicio diferență |

### 2.3 Health check

| # | Ce faceți | Ce trebuie să vedeți |
|---|---|---|
| 2.3.1 | Health check, START, **Y** (test automat), în timpul testului **țineți B** 2 s | toate motoarele se opresc imediat. **Niciun motor nu se rotește înapoi** cât e ținut B |
| 2.3.2 | **X** (raport), apoi **Y** (test din nou) | testul pornește din nou, programul nu se blochează |

### 2.4 Centrul de greutate

| # | Ce faceți | Ce trebuie să vedeți |
|---|---|---|
| 2.4.1 | „Center of gravity”, START, pasul 1 (robotul drept) - **A** | pasul 1 acceptat |
| 2.4.2 | Fără să înclinați robotul, din nou **A** | pasul **nu e acceptat**, ecranul scrie „tip it more: at least 3°” |
| 2.4.3 | Faceți toți pașii corect | un rezultat cu numere rezonabile; pagina desenează vederea de sus și din lateral. Nicăieri `Infinity` sau `NaN` |

### 2.5 Pagina în timpul meciului (Wi-Fi-ul Driver Station)

| # | Ce faceți | Ce trebuie să vedeți |
|---|---|---|
| 2.5.1 | Pe laptop începeți descărcarea unei înregistrări mari (**WPILOG**-ul unei sesiuni lungi), imediat INIT la TeleOp | descărcarea **se oprește**; Driver Station nu pierde conexiunea |
| 2.5.2 | În timpul mersului deschideți pe pagină o înregistrare veche și lungă | „The robot is driving: big recordings open after it stops”; după STOP se deschide |

### 2.6 Propriul Black Box al echipei (dacă folosiți `FtcBlackBox.attachAll` sau `BlackBox.create`)

| # | Ce faceți | Ce trebuie să vedeți |
|---|---|---|
| 2.6.1 | Rulați un TeleOp care are propriul Black Box | **o singură** înregistrare pe rulare, nu două |

## 3. „Șterge ASA” (la final, pe o copie sau când nu mai aveți nevoie de ASA)

| # | Ce faceți | Ce trebuie să vedeți |
|---|---|---|
| 3.1 | Dacă codul vostru folosește Black Box: ASA Black Box → **RB** până la „Remove ASA”, START | roșu „Your code uses Black Box, so nothing is removed” și lista fișierelor voastre. Nu se șterge nimic |
| 3.2 | Dacă sunt fișiere vechi ASA 1.4 fără marcaj | galben „These files have ASA's names but not its mark”, lista. Nu se șterge nimic |
| 3.3 | Altfel: țineți **A + B** 3 s | bara se umple; dacă eliberați mai devreme, se anulează |
| 3.4 | Țineți până la capăt, apoi STOP → OnBot Java → Build | „ASA removed”. Niciun fișier ASA în OnBot Java, **toate fișierele voastre sunt la locul lor**, Build trece |
| 3.5 | Dacă e instalat și ASA SysId | SysId merge, constantele (`sysid/*.ff`) sunt la locul lor |

## 4. Ce ne trimiteți

- Poza de la pasul 3 (obligatoriu) și a fiecărui ⚠️ / ❌.
- Tabelul cu notițe.
- Modelele motoarelor (goBILDA / REV HD Hex / altele), versiunea SDK (Driver Hub → ⋮ → About), Expansion Hub da/nu.
- Formular: https://github.com/stanchevicid-hash/asa-blackbox/issues/new?template=robot-result.yml

Dacă ceva merge foarte rău: Y oprește înregistrarea automată, iar „Șterge ASA” (secțiunea 3) scoate totul. Codul
vostru e în copia de la pasul 0.1.
