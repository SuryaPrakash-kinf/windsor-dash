# Windsor Dash

Live battery dashboard for the **MG Windsor EV (52.9 kWh)** over an ELM327 OBD-II dongle, with an **Android Auto** screen.

## What it shows
- **Phone:** SoC, range, energy left, power (drive / regen / charge), pack V & A, trip efficiency, charging session, SoH, pack temperature, highest/lowest cell (with cell number) and spread, 12 V battery, odometer.
- **Android Auto:** Live (SoC + range, power, trip, charging, battery), Charging (kW, kWh added, time to 80/90/100 %), Trip (Wh/km, kWh/100 km, regen, range).

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
| Cluster | 744 / 74C | B921 | Odometer | u24, km |

Range = energy left ÷ efficiency (this trip once ≥ 5 km, otherwise your lifetime average, otherwise 130 Wh/km).

## Install & Android Auto
1. Download the APK from the latest GitHub Release on your phone and install it (allow "install unknown apps").
2. Open Windsor Dash → **Connect** → choose your adapter.
3. Android Auto hides sideloaded apps until you enable them: Android Auto settings → tap **Version** 10 times → ⋮ → **Developer settings** → enable **Unknown sources**. Then add Windsor Dash to the launcher (Android Auto settings → Customise launcher).
4. Connect from the phone before you plug into the car. Android won't let a sideloaded app start the Bluetooth link from the car screen while it's in the background.

## Build locally
Android Studio (Ladybug or newer): open the folder and press Run. CLI: `gradle assembleRelease` (Gradle 8.10+, JDK 17, Android SDK 35).
