# Haier Smart Android TV Remote (PC & Mobile Web App)

A smart TV remote control application designed for **Haier Android Smart TVs** (such as **LE32K6600G** and K6600 series) using the official Android TV Remote v2 protocol.

Provides a physical-remote experience on both Desktop PC and Android Mobile devices, featuring the original circular D-Pad, dedicated Netflix and YouTube shortcuts, Google Assistant, volume/channel rockers, and search keyboard input.

---

## Features

- **Original Physical Remote Design:** Styled after the authentic Haier HTR-U28 Bluetooth voice remote.
- **Circular Directional Pad (D-Pad):** Up, Down, Left, Right arrows with a prominent center OK button.
- **Dedicated App Shortcuts:** Instant launch buttons for **NETFLIX**, **YouTube**, **Prime Video**, and **Google Play**.
- **Full TV Controls:** Power toggle, TV Input/Source, Google Assistant, Mute, Settings, Info, Live TV, and Home/Back/Menu.
- **Vertical Rocker Controls:** Dedicated Volume (+ / -) and Channel (Up / Down) rockers.
- **Direct Search Typing:** Type text on your PC or phone keyboard and send it straight to TV search fields.
- **Expandable Number Pad:** Number keys 0-9 for channel entry.
- **PC Keyboard Support:** Navigate menus directly with arrow keys, Enter, Esc, Home, Space, and +/-.
- **Android Mobile PWA & Touch Feedback:**
  - Installs directly to any Android home screen as a standalone app.
  - Haptic touch vibration on button press.
  - QR Code generator inside the app for instant mobile pairing.
- **Native Android Studio Project:** Included in the `android/` directory for building a standalone Android APK.

---

## Prerequisites

1. **Python 3.9+** installed on your PC.
2. Required Python libraries:
   ```bash
   pip install fastapi uvicorn androidtvremote2 pillow
   ```
3. Both your PC and Haier TV must be connected to the same local Wi-Fi network.

---

## Quick Start (PC)

1. Double-click `start_remote.bat` (or run in terminal):
   ```bash
   python server.py
   ```
2. Open your browser and navigate to:
   ```
   http://localhost:5000
   ```
3. **One-Time Pairing:**
   - Click the **Pairing Required** status pill or banner.
   - Click **Start Pairing**.
   - A 6-character code will appear on your Haier TV screen.
   - Enter the code in the prompt and click **Confirm Code**.
   - Your remote is now paired and ready to control the TV.

---

## Android Mobile Usage

1. Start the server on your PC (`python server.py`).
2. Click the **Mobile** button on the web app header to display your local Wi-Fi QR Code.
3. Scan the QR code with your Android phone's camera, or visit:
   ```
   http://<YOUR_PC_IP>:5000
   ```
   (e.g., `http://192.168.0.104:5000`).
4. In Chrome on Android, tap the three dots menu and select **"Add to Home screen"** or **"Install app"**.
5. The Haier Remote icon will appear on your phone's home screen and run full-screen just like a native Android app with touch vibration feedback.

---

## Repository Structure

```
haier-smart-tv-remote/
├── server.py                  # FastAPI asynchronous backend with AndroidTVRemote2
├── start_remote.bat           # 1-Click Windows launcher
├── .gitignore
├── README.md
├── static/
│   ├── index.html             # Authentic Haier remote UI
│   ├── manifest.json          # PWA configuration for Android
│   ├── service-worker.js      # PWA offline cache worker
│   ├── icon-192.png           # Mobile app launcher icon
│   └── icon-512.png           # High-resolution splash icon
└── android/                   # Native Android Studio WebView app project
    ├── build.gradle.kts
    ├── settings.gradle.kts
    └── app/
        ├── build.gradle.kts
        └── src/main/
            ├── AndroidManifest.xml
            └── java/com/mastersahib/haierremote/MainActivity.kt
```

---

## Author & Credits

Developed for the **Master Sahib Educational Series** / Haier LE32K6600G Smart TV series.
GitHub: [mohammadfaisalpirzada](https://github.com/mohammadfaisalpirzada)
Website: [mastersahib.com](https://www.mastersahib.com)
