# Tata Punch.EV Live Telemetry App (Cyberpunk Dashboard)

A modern, high-refresh-rate EV dashboard for the **Tata Punch.EV** (Ziptron platform). It connects to any standard ELM327 / Vgate Bluetooth OBD-II adapter to read live battery CAN telemetry.

---

## ✨ Features & UI
* **Cyberpunk OLED Aesthetic**: Deep obsidian background with electric cyan & neon emerald accents.
* **Smooth Circular SoC Gauge**: Live battery % with real-time arc animation.
* **Instant Power & Regen**: Shows live kW draw when accelerating and switches to bright emerald green during **Regenerative Braking**.
* **High-Voltage Pack Stats**: Live traction voltage (V) & current (A).
* **Battery Temperature Health**: Live BMS temperature (°C) with cooling status.
* **12V Auxiliary Battery**: Voltage readout to prevent 12V dead-battery lockouts.
* **Interactive Demo Mode**: Simulate real driving telemetry right on your screen without needing to plug into the car.

---

## 🚀 Instant Live UI Preview
You can test and see the UI immediately on your computer or phone:
* Simply double-click **`live-preview.html`** in this folder to open it in any web browser!

---

## 📱 How to Get the Installable `.apk` for Your Phone (Zero Coding Required)

### Method 1: Automatic Free Cloud Build (GitHub Actions) — Recommended
1. Create a free account at [GitHub.com](https://github.com).
2. Click **New Repository** and name it `punch-ev-app` (public or private).
3. Upload all files from this `punch-ev-app/` folder into your GitHub repository.
4. Click the **Actions** tab at the top of your GitHub repository.
5. GitHub will automatically build the Android APK in under 2 minutes!
6. Click the completed run, scroll down to **Artifacts**, and download `Punch-EV-Live-Dashboard-APK` (`app-debug.apk`) directly to your phone.
7. Tap the downloaded file on your phone and click **Install**.

---

### Method 2: Local Android Studio
1. Open [Android Studio](https://developer.android.com/studio).
2. Click **File > Open** and select this `punch-ev-app` folder.
3. Wait for Gradle sync to finish.
4. Click **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. Android Studio will generate the `.apk` in `app/build/outputs/apk/debug/app-debug.apk`. Transfer it to your phone and install.

---

## 🚗 How to Connect to Your Punch EV
1. Plug your **ELM327 Bluetooth / Vgate iCar Pro** adapter into the OBD port under your Punch EV steering wheel.
2. Turn the car ignition on (or press Start button into READY mode).
3. Pair the OBD adapter in your phone's Bluetooth settings (PIN is usually `1234` or `0000`).
4. Open the **Punch.EV Live** app, tap **Connect OBD-II**, and select your adapter!

