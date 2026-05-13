from flask import Flask, request, jsonify
import tempfile, os
import cv2
import iris_iqm

app = Flask(__name__)

@app.route("/", methods=["POST"])
def evaluate():
    print(">>> Files received:", list(request.files.keys()))
    if "file" not in request.files:
        return jsonify({"error": "No image provided"}), 400
    image = request.files["file"]
    with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as tmp:
        image.save(tmp.name)
        tmp_path = tmp.name
    try:
        img = cv2.imread(tmp_path, cv2.IMREAD_GRAYSCALE)
        if img is None:
            return jsonify({"output": "", "error": "Failed to decode image"}), 400
        result = iris_iqm.evaluate_array(img)
        metrics = result.to_dict()
        # Emit BIQT-compatible CSV so the Android parser works with no changes
        lines = ["Provider,Image,Detection,AttributeType,Key,Value"]
        for key, value in metrics.items():
            lines.append(f"iris_iqm,{tmp_path},1,Metric,{key},{value}")
        return jsonify({"output": "\n".join(lines), "error": ""})
    finally:
        os.unlink(tmp_path)

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8080)
