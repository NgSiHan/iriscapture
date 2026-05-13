# IrisQualityCapture

Android app for capturing and evaluating iris image quality using MediaPipe face landmarking and the [iris-iqm](https://github.com/naveengv7/iris-iqm) quality evaluation backend.

---

## Overview

Modified from [this repo](https://github.com/naveengv7/IrisQualityCapture), the app captures high-resolution eye images from the phone camera, sends them to a Flask server running on your PC, which evaluates them using the iris-iqm library. Images that meet the quality thresholds are saved to the phone; poor-quality images are automatically discarded and the app retries.

**Pipeline:**
```
Phone camera → MediaPipe face detection → crop eye regions
    → POST image to Flask server (Windows)
    → iris-iqm evaluation (ISO quality scores)
    → App checks scores against thresholds
    → Save if pass / discard and retry if fail
```

---

## Prerequisites

- Android phone and Windows PC on the **same Wi-Fi network**
- Python 3.8+ on the PC
- Android Studio for building and installing the APK

---

## Part 1: Quality Evaluation Server Setup

The server runs directly on Windows, no WSL or C++ build tools required.

### 1.1 Install dependencies

Open a terminal in the repo root and run:

```powershell
pip install -r server\requirements.txt
```

This installs Flask and iris-iqm (which brings NumPy and OpenCV as its own dependencies).

### 1.2 Start the server

```powershell
python server\server.py
```

Test it by sending a local eye image:

```powershell
curl -X POST http://localhost:8080/ -F "file=@C:\path\to\eye.png"
```

You should get JSON back with an `output` field containing CSV quality metrics (including `iso_overall_quality`).

### 1.3 Allow the firewall port (first time only)

If the phone can't reach the server, add a Windows Firewall rule:

```powershell
# Run as Administrator
netsh advfirewall firewall add rule name="iris-iqm Server" dir=in action=allow protocol=TCP localport=8080 profile=any
```

---

## Part 2: Network Configuration (PC ↔ Phone)

The phone connects directly to your Windows Wi-Fi IP.

### 2.1 Find your Windows Wi-Fi IP

```powershell
ipconfig
# Find your Wi-Fi adapter → IPv4 Address
# Example: 10.206.158.144
```

### 2.2 Test from the phone

With the server running, open a browser on your phone and navigate to:
```
http://<WINDOWS_WIFI_IP>:8080/
```
You should see a method-not-allowed error (405), which confirms the server is reachable. A connection timeout means the firewall rule isn't in place.

### 2.3 Important: Wi-Fi IP may change

If the app stops working (e.g. after reconnecting to Wi-Fi):

1. Re-run `ipconfig` to get the new IP
2. Update `serverUrl` in `MainActivity3.java` to match

---

## Part 3: Android App Setup

### 3.1 Network security config

The app communicates over plain HTTP. Ensure `app/src/main/res/xml/network_security_config.xml` contains your Windows Wi-Fi IP:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain>YOUR_WINDOWS_WIFI_IP</domain>
    </domain-config>
</network-security-config>
```

### 3.2 Set the server URL

In `MainActivity3.java`, find:

```java
String serverUrl = "http://10.206.157.72:8080";
```

Change the IP to your Windows Wi-Fi IP (from `ipconfig`). The phone connects directly to the Flask server running on Windows.

---

## Part 4: Configuring Quality Thresholds

All threshold configuration is in `MainActivity3.java` inside `sendImageToBIQTAndMaybeSave()`.

### 4.1 Quality thresholds

```java
Map<String, Float> thresholds = new HashMap<>();
thresholds.put("iso_overall_quality", 0f);
//        thresholds.put("iso_greyscale_utilization", 6f);
//        thresholds.put("iso_iris_pupil_concentricity", 90f);
//        thresholds.put("iso_iris_pupil_contrast", 30f);
//        thresholds.put("iso_iris_pupil_ratio", 20f);
//        thresholds.put("iso_iris_sclera_contrast", 5f);
//        thresholds.put("iso_margin_adequacy", 80f);
//        thresholds.put("iso_pupil_boundary_circularity", 70f);
//        thresholds.put("iso_sharpness", 80f);
//        thresholds.put("iso_usable_iris_area", 70f);
```

- Uncomment any line to enable that metric as an additional threshold gate
- Adjust the float value to tighten or loosen each threshold
- All enabled thresholds must pass for an image to be accepted
- Start with just `iso_overall_quality` and tune from there once the pipeline is confirmed working

### 4.2 Pre-filter sharpness threshold

Before even sending to the server, a local OpenCV sharpness check runs:

```java
final double SHARPNESS_THRESHOLD = 0.0;
```

Increase this (e.g. to `30.0`) to filter out obviously blurry frames before they hit the server, saving round-trip time.

### 4.3 Number of saved images per eye

When an eye passes quality checks, the app saves the base image plus offset variants:

```java
private final int totalImageCount = 4;
for (int i = 1; i <= totalImageCount; i++)
```

This saves 4 files per eye total: `subjectID_sessionID_trialNum_left.png` through `..._left_3.png`.
You may change totalImageCount to vary the amount of iris images captured.

---

## Part 5: Output Files

Images are saved to the app's private external storage:

```
/storage/emulated/0/Android/data/com.example.irisqualitycapture/files/Download/
```

Pull them via ADB:

```bash
adb pull /storage/emulated/0/Android/data/com.example.irisqualitycapture/files/Download/ ./output/
```

File naming convention:
```
{subjectID}_{sessionID}_{trialNum}_{eye}.png        ← base image
{subjectID}_{sessionID}_{trialNum}_{eye}_1.png      ← variant 1
{subjectID}_{sessionID}_{trialNum}_{eye}_2.png      ← variant 2
{subjectID}_{sessionID}_{trialNum}_{eye}_3.png      ← variant 3
```

---

## Tips for Good Captures

- Hold the phone **15–20 cm** from the face
- Keep the **whole face in frame** first so MediaPipe can detect landmarks
- Hold **still for 2–3 seconds** to let autofocus lock on the eyes
- The torch stays on continuously for stable lighting — ensure the room isn't too bright to cause glare
- The app retries automatically if quality is poor — just hold steady
