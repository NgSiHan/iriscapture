# IrisQualityCapture

Android app for capturing and evaluating iris image quality using MediaPipe face landmarking and the BIQT Iris quality evaluation backend.

---

## Overview

Modified from [this repo](https://github.com/naveengv7/IrisQualityCapture), the app captures high-resolution eye images from the phone camera, sends them to a Flask server running on your PC, which evaluates them using the BIQT Iris model. Images that meet the quality thresholds are saved to the phone; poor-quality images are automatically discarded and the app retries.

**Pipeline:**
```
Phone camera → MediaPipe face detection → crop eye regions
    → POST image to Flask server (WSL)
    → BIQT Iris evaluation (CSV scores)
    → App checks scores against thresholds
    → Save if pass / discard and retry if fail
```

---

## Prerequisites

- Android phone and Windows PC on the **same Wi-Fi network**
- WSL Ubuntu 22.04 on the PC
- Android Studio for building and installing the APK

---

## Part 1: BIQT Iris Evaluation Server (WSL Setup)

### 1.1 Install dependencies

```bash
sudo apt update
sudo apt install -y cmake build-essential libopencv-dev git libjsoncpp-dev default-jdk
```

### 1.2 Build and install the BIQT framework

```bash
git clone https://github.com/mitre/biqt
cd biqt
mkdir build && cd build
cmake -DCMAKE_BUILD_TYPE=Release ..
make
sudo make install
```

Add the BIQT home to your environment (add this to `~/.bashrc` so it persists):

```bash
echo 'export BIQT_HOME=/usr/local' >> ~/.bashrc
source ~/.bashrc
```

### 1.3 Build and install the BIQTIris provider

```bash
cd ~
git clone https://github.com/mitre/biqt-iris
cd biqt-iris
mkdir build && cd build
cmake -DCMAKE_BUILD_TYPE=Release ..
make
sudo make install
```

### 1.4 Verify the CLI works

```bash
biqt -p BIQTIris /path/to/some/eye.png
```

You should see CSV output with metrics like `iso_overall_quality`, `iso_sharpness`, etc. If this doesn't work, do not proceed — the Flask server will also fail.

### 1.5 Set up the Flask server

```bash
python3 -m venv ~/biqt-server/venv
source ~/biqt-server/venv/bin/activate
pip install flask
```

Create `~/biqt-server/server.py` with the following content:

```python
from flask import Flask, request, jsonify
import subprocess, tempfile, os, json

app = Flask(__name__)

@app.route("/", methods=["POST"])
def evaluate():
    print(">>> Files received:", list(request.files.keys()))
    print(">>> Form data:", list(request.form.keys()))
    if "file" not in request.files:
        return jsonify({"error": "No image provided"}), 400
    image = request.files["file"]
    with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as tmp:
        image.save(tmp.name)
        tmp_path = tmp.name
    try:
        result = subprocess.run(["biqt", "-p", "BIQTIris", tmp_path], capture_output=True, text=True)
        return jsonify({"output": result.stdout, "error": result.stderr})
    finally:
        os.unlink(tmp_path)

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000)
```

> To do so via CLI, you may call ```nano ~/biqt-server/server.py```

### 1.6 Start the server

```bash
source ~/biqt-server/venv/bin/activate
python ~/biqt-server/server.py
```

Test it locally by copying a test image from Windows into WSL and send the image into the server:

```bash
cp /mnt/c/path/to/eye.png ~/eye.png
curl -X POST http://localhost:5000/ -F "file=@/path/to/eye.png"
```

You should get JSON back with an `output` field containing the BIQT CSV.

---

## Part 2: Network Configuration (WSL ↔ Phone)

WSL does not expose ports to your local network directly. You need to use Windows port forwarding.

### 2.1 Find your IPs

In WSL:
```bash
ip addr show eth0 | grep "inet "
# Example: 172.29.91.154 — this is your WSL IP
```

In Windows (PowerShell or CMD):
```
ipconfig
# Find your Wi-Fi adapter → IPv4 Address
# Example: 10.206.158.144 — this is what the phone connects to
```

### 2.2 Set up port forwarding (run as Administrator in PowerShell)

```powershell
netsh interface portproxy add v4tov4 listenport=5000 listenaddress=0.0.0.0 connectport=5000 connectaddress=<WSL_IP>
netsh advfirewall firewall add rule name="BIQT Server" dir=in action=allow protocol=TCP localport=5000 profile=any
```

Replace `<WSL_IP>` with your actual WSL IP from the step above (e.g. `172.29.91.154`).

### 2.3 Test from the phone

With the Flask server running in WSL, open a browser on your phone and navigate to:
```
http://<WINDOWS_WIFI_IP>:5000/
```
You should see a method-not-allowed error (405), which confirms the server is reachable. A connection timeout means port forwarding isn't working.

### 2.4 Important: WSL IP changes on reboot

Every time you restart your PC, the WSL IP may change. If the app stops working after a reboot:

1. Check the new WSL IP: `ip addr show eth0 | grep "inet "`
2. Re-run the `netsh interface portproxy add` command with the new IP
3. Update `serverUrl` in `MainActivity3.java` if the Windows Wi-Fi IP also changed

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
String serverUrl = "http://10.206.158.144:5000";
```

Change the IP to your Windows Wi-Fi IP. This is the only IP you need to update — the phone connects to Windows, and Windows forwards to WSL.

---

## Part 4: Configuring Quality Thresholds

All threshold configuration is in `MainActivity3.java` inside `sendImageToBIQTAndMaybeSave()`.

### 4.1 BIQT quality thresholds

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
