# Windsor Dash

Live battery dashboard for the **MG Windsor EV (52.9 kWh)** over an ELM327 OBD-II dongle, with an **Android Auto** screen.

## What it shows
- **Phone:** SoC, range, energy left, power (drive / regen / charge), pack V & A, trip efficiency, charging session, SoH, pack temperature, highest/lowest cell (with cell number) and spread, 12 V battery, odometer.
- **Android Auto:** Live (SoC + range, power, trip, charging, battery), Charging (kW, kWh added, time to 80/90/100 %), Trip (Wh/km, kWh/100 km, regen, range).

## Measured vs estimated
Every battery and cell figure is the BMS's own reading, shown as reported. Nothing is interpolated, and no per-cell voltages are made up. Only **range** and **charge time** are estimates, and both are labelled *est.*

## Local logs (no cloud)
The app makes no network calls except to a Wi-Fi OBD dongle. It has no analytics or Firebase, and its data is excluded from Android backup. Logs are kept on the phone in app storage:
- `samples-YYYY-MM-DD.csv`: one row per reading (SoC, SoH, energy left, V, A, kW, max/min cell mV with cell numbers, spread, pack temp, 12 V, odometer). A blank cell means it wasn't read.
- `events.csv`: connects and disconnects, charge sessions (SoC from → to, kWh added, peak kW, minutes) and trip resets.

**Write straight to a folder:** tap **Choose folder** and pick (or create) a folder such as `Documents/WindsorDash`. From then on the app appends rows to the CSV files there every few seconds, and existing logs are copied over. If the folder becomes unavailable, it falls back to app storage so no readings are lost.

In app-storage mode the Logs panel also offers **Save to Downloads** (copies to `Download/WindsorDash`), **Share**, **Delete**, or switch logging off. Demo mode is never logged.

## Odometer
EVDS (and v0.1 of this app) ask for the odometer at ECU 744/74C, DID B921. That address is copied from the MG ZS EV profile. The Windsor is a different platform (Wuling Cloud EV) with its own ECUs, so it usually gets no answer there.

How this app handles it:
- **GPS distance:** while the car link is live, the phone's GPS measures distance driven, filtering out poor fixes, parked drift and jumps. Trips use it, so Wh/km works without the car's odometer.
- **App odometer:** your dashboard reading plus GPS km since. Tap **Correct** whenever you like to re-anchor it. Kilometres driven with the app not connected aren't counted until you correct it.
- **Find in car:** enter the dashboard reading with the car on and parked. The app tries the standard OBD odometer (01 A6), finds which ECUs answer (tester-present 3E 00), then reads DID ranges DDxx, B9xx, E1xx, D0xx and 01xx on each one. It looks for a 2–4 byte value matching your reading at ÷1/10/100/1000. Everything it sends is a read-only request. A strong match is saved automatically; weak ones are listed for you to choose. Once saved, the car's own reading takes over and re-anchors the GPS count.

## Adapters
Bluetooth LE (Vgate iCar Pro BLE, Veepeak BLE, OBDLink CX, vLinker BLE…), classic Bluetooth (pair it in Android settings first) and Wi-Fi (192.168.0.10:35000). There's also a **Demo** adapter that simulates the car, so you can try the Android Auto screens without the car.

## Car protocol (UDS over CAN, 11-bit, 500 kbps — ATSP6)
| ECU | Request / Response | DID | Meaning | Decode |
|---|---|---|---|---|
| BMS | 742 / 74A | D038 | Pack voltage | u16, V |
| BMS | 742 / 74A | D027 | Pack current | (u16 − 10000) / 10, A (− = charging) |
| BMS | 742 / 74A | D062 | SoC | u8, % |
| BMS | 742 / 74A | D061 | SoH | u8, % |
| BMS | 742 / 74A | D060 | Energy left | u16 / 10, kWh |
| BMS | 742 / 74A | D06A / D06C | Max / min cell | u16, mV |
| BMS | 742 / 74A | D06B / D06D | Max / min cell number | u8 |
| Thermal | 745 / 74D | A021 | Battery temperature | u8, °C |
| Cluster? | 744 / 74C | B921 | Odometer (ZS EV guess — usually no reply on Windsor) | u24, km |

Range = energy left ÷ efficiency (this trip once ≥ 5 km, otherwise your lifetime average, otherwise 130 Wh/km).

## Install & Android Auto
1. Download the APK from the latest GitHub Release on your phone and install it (allow "install unknown apps").
2. Open Windsor Dash → **Connect** → choose your adapter.
3. Android Auto hides sideloaded apps until you enable them: Android Auto settings → tap **Version** 10 times → ⋮ → **Developer settings** → enable **Unknown sources**. Then add Windsor Dash to the launcher (Android Auto settings → Customise launcher).
4. Connect from the phone before you plug into the car. Android won't let a sideloaded app start the Bluetooth link from the car screen while it's in the background.

## Build locally
Android Studio (Ladybug or newer): open the folder and press Run. CLI: `gradle assembleRelease` (Gradle 8.10+, JDK 17, Android SDK 35).
