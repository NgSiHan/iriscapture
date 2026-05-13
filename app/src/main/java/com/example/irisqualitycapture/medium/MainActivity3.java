package com.example.irisqualitycapture.medium;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.MeteringRectangle;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import com.example.irisqualitycapture.NamingActivity;
import com.example.irisqualitycapture.R;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import org.json.JSONObject;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.imgproc.Imgproc;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.widget.Toast;
import java.io.IOException;

public class MainActivity3 extends AppCompatActivity implements ImageReader.OnImageAvailableListener {

    private FaceLandmarker faceLandmarker;
    private ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private int previewWidth = 640;
    private int previewHeight = 360;
    private Bitmap lastCapturedBitmap;
    private CameraConnectionFragment camera2Fragment;
    private Size previewSize;
    private PointF leftEyeCenter;
    private PointF rightEyeCenter;
    private OverlayView overlayView;
    private float currentZoomFactor = 1.0f;
    private boolean faceCurrentlyVisible = false;
    private int missingFaceFrameCount = 0;
    private static final int FACE_MISSING_FRAME_THRESHOLD = 5;
    private FaceLandmarker faceLandmarkerCapture;
    private int leftEyeImageCount = 0;
    private int rightEyeImageCount = 0;
    private final int totalImageCount = 4;
    private String subjectID;
    private String sessionID;
    private String trialNum;
    private long lastFocusTime = 0;

    // Flag to prevent new captures after both eyes have passed
    private boolean captureComplete = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        System.loadLibrary("opencv_java4");
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main3);

        subjectID = getIntent().getStringExtra("N_subID");
        if (subjectID == null) subjectID = "unknown";
        sessionID = getIntent().getStringExtra("N_sessionID");
        if (sessionID == null) sessionID = "0";
        trialNum  = getIntent().getStringExtra("N_trialNum");
        if (trialNum == null) trialNum = "0";

        overlayView = findViewById(R.id.overlay_view);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 121);
        } else {
            setFragment();
        }

        setupFaceLandmarker();
    }

    private void setFragment() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        String cameraId = null;
        try {
            cameraId = manager.getCameraIdList()[0];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Rect sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            overlayView.setSensorInfo(sensorArraySize.width(), sensorArraySize.height());
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        camera2Fragment = CameraConnectionFragment.newInstance(
                (Size size, int rotation) -> {
                    previewHeight = size.getHeight();
                    previewWidth = size.getWidth();
                    previewSize = size;
                },
                this,
                R.layout.camera_fragment,
                new Size(640, 360));

        camera2Fragment.setCamera(cameraId);
        getSupportFragmentManager().beginTransaction().replace(R.id.container, camera2Fragment).commit();

        camera2Fragment.setOnImageCapturedListener(highResImage -> {
            // Don't process if capture is already complete
            if (captureComplete) return;

            MPImage mpHighRes = new BitmapImageBuilder(highResImage).build();
            FaceLandmarkerResult result = faceLandmarkerCapture.detect(mpHighRes);

            if (!result.faceLandmarks().isEmpty()) {
                List<NormalizedLandmark> landmarks = result.faceLandmarks().get(0);
                List<PointF> points = new ArrayList<>();
                for (NormalizedLandmark landmark : landmarks) {
                    points.add(new PointF(landmark.x() * highResImage.getWidth(), landmark.y() * highResImage.getHeight()));
                }

                PointF leftEye = points.get(468);
                PointF rightEye = points.get(473);

                float distanceHighRes = (float) Math.hypot(
                        rightEye.x - leftEye.x,
                        rightEye.y - leftEye.y
                );

                if (previewLeftEye != null && previewRightEye != null) {
                    float previewDistance = (float) Math.hypot(
                            previewRightEye.x - previewLeftEye.x,
                            previewRightEye.y - previewLeftEye.y
                    );
                }

                float distance = distanceHighRes;
                if (distance < 10) return;

                int sensorRotation = camera2Fragment.getSensorOrientation();

                Bitmap leftCrop = cropEyeRegion(highResImage, leftEye, "left");
                Bitmap rightCrop = cropEyeRegion(highResImage, rightEye, "right");
                Bitmap rotatedLeft = rotateBitmap(leftCrop, sensorRotation);
                Bitmap rotatedRight = rotateBitmap(rightCrop, sensorRotation);

                double sharpnessLeft = calculateSharpness(rotatedLeft);
                double sharpnessRight = calculateSharpness(rotatedRight);
                Log.d("BIQT_LOG", "gh" + sharpnessLeft);
                Log.d("BIQT_LOG", "gh" + sharpnessRight);

                final double SHARPNESS_THRESHOLD = 0.0;

                if (leftEyeImageCount < 4 && sharpnessLeft >= SHARPNESS_THRESHOLD) {
                    sendImageToBIQTAndMaybeSave(rotatedLeft, "left_eye");
                }

                if (rightEyeImageCount < 4 && sharpnessRight >= SHARPNESS_THRESHOLD) {
                    sendImageToBIQTAndMaybeSave(rotatedRight, "right_eye");
                }

            } else {
                Log.w("HighResEye", "No face detected in high-res image → skipping.");
            }
        });
    }

    private String buildFilename(String eyeSide, int variant) {
        if (variant == 0)
            return subjectID + "_" + sessionID + "_" + trialNum + "_" + eyeSide + ".png";
        else
            return subjectID + "_" + sessionID + "_" + trialNum + "_" + eyeSide + "_" + variant + ".png";
    }

    /**
     * Parses the raw CSV output from the BIQT server into a JSONObject of metric key→value pairs.
     *
     * CSV format:
     *   Provider,Image,Detection,AttributeType,Key,Value
     *   BIQTIris,/tmp/...,1,Metric,iso_overall_quality,0
     *
     * We extract all rows where AttributeType == "Metric" and build { key: numericValue }.
     */
    private JSONObject parseCSVToQualityScores(String csvOutput) {
        JSONObject scores = new JSONObject();
        if (csvOutput == null || csvOutput.isEmpty()) return scores;

        String[] lines = csvOutput.split("\n");
        // Line 0 is the header row — skip it
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split(",");
            if (parts.length < 6) continue;

            String attributeType = parts[3].trim();
            String key            = parts[4].trim();
            String value          = parts[5].trim();

            if ("Metric".equals(attributeType)) {
                try {
                    scores.put(key, Double.parseDouble(value));
                } catch (Exception e) {
                    Log.w("BIQT_LOG", "Could not parse metric: " + key + "=" + value);
                }
            }
        }

        Log.d("BIQT_LOG", "Parsed quality scores: " + scores.toString());
        return scores;
    }

    private void sendImageToBIQTAndMaybeSave(Bitmap cropBitmap, String imageLabel) {
        String eyeSide = imageLabel.contains("left") ? "left" : "right";
        String serverUrl = "http://10.206.157.72:8080";

        // Unique temp filename per attempt — avoids overwrite race conditions
        String tempFilename = "temp_" + eyeSide + "_" + System.currentTimeMillis() + ".png";

        File downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloadsDir != null && !downloadsDir.exists()) downloadsDir.mkdirs();
        File tempFile = new File(downloadsDir, tempFilename);

        Log.d("BIQT_LOG", "Saving temp image: " + tempFilename);

        try {
            FileOutputStream out = new FileOutputStream(tempFile);
            cropBitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
            out.close();
            Log.d("BIQT_LOG", "Temp image saved to: " + tempFile.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
            Log.e("BIQT_LOG", "Failed to save temp image: " + e.getMessage());
            return;
        }

        Map<String, Float> thresholds = new HashMap<>();
        thresholds.put("iso_overall_quality", 30f);
//        thresholds.put("iso_greyscale_utilization", 6f);
//        thresholds.put("iso_iris_pupil_concentricity", 90f);
//        thresholds.put("iso_iris_pupil_contrast", 30f);
//        thresholds.put("iso_iris_pupil_ratio", 20f);
//        thresholds.put("iso_iris_sclera_contrast", 5f);
//        thresholds.put("iso_margin_adequacy", 80f);
//        thresholds.put("iso_pupil_boundary_circularity", 70f);
//        thresholds.put("iso_sharpness", 80f);
//        thresholds.put("iso_usable_iris_area", 70f);

        BIQTClient.sendImageFileToBIQTServer(MainActivity3.this, tempFile, serverUrl, new BIQTClient.BIQTCallback() {
            @Override
            public void onSuccess(JSONObject result) {
                runOnUiThread(() -> {
                    // Parse the raw CSV from the server response into a quality scores map
                    String csvOutput = result.optString("output", "");
                    JSONObject qualityScores = parseCSVToQualityScores(csvOutput);

                    boolean passed = BIQTQualityEvaluator.checkQualityScores(qualityScores, thresholds);
                    Log.d("BIQT_LOG", imageLabel + " quality check passed: " + passed);

                    if (passed) {
                        Log.d("BIQT_LOG", imageLabel + " passed → keeping image.");

                        // Rename temp file to the proper final filename
                        File finalFile = new File(downloadsDir, buildFilename(eyeSide, 0));
                        if (!tempFile.renameTo(finalFile)) {
                            // renameTo can fail across filesystems — fall back to copy+delete
                            try {
                                FileOutputStream out = new FileOutputStream(finalFile);
                                cropBitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                                out.flush();
                                out.close();
                                tempFile.delete();
                            } catch (IOException e) {
                                Log.e("BIQT_LOG", "Failed to write final file: " + e.getMessage());
                            }
                        }
                        Log.d("DEBUG_SAVE", "Final image saved: " + finalFile.getAbsolutePath());

                        // Save slight-offset variants from the original crop
                        int originalCropSize = 300;
                        float centerX = cropBitmap.getWidth() / 2f;
                        float centerY = cropBitmap.getHeight() / 2f;

                        for (int i = 1; i <= totalImageCount; i++) {
                            int offsetX = 5 * i;
                            int offsetY = 5 * i;

                            int x = Math.max(0, Math.min((int) centerX - originalCropSize / 2 + offsetX, cropBitmap.getWidth() - originalCropSize));
                            int y = Math.max(0, Math.min((int) centerY - originalCropSize / 2 + offsetY, cropBitmap.getHeight() - originalCropSize));

                            Bitmap variantCrop = Bitmap.createBitmap(cropBitmap, x, y, originalCropSize, originalCropSize);
                            File variantFile = new File(downloadsDir, buildFilename(eyeSide, i));

                            try {
                                FileOutputStream out = new FileOutputStream(variantFile);
                                variantCrop.compress(Bitmap.CompressFormat.PNG, 100, out);
                                out.flush();
                                out.close();
                                Log.d("DEBUG_SAVE", "Saved variant: " + variantFile.getAbsolutePath());
                            } catch (IOException e) {
                                Log.e("DEBUG_SAVE", "Failed to save variant: " + e.getMessage());
                            }
                        }

                        // Mark this eye as done
                        if (eyeSide.equals("left")) {
                            leftEyeImageCount = 4;
                        } else {
                            rightEyeImageCount = 4;
                        }

                        // If both eyes are done, finish — do NOT reset counts before finishing
                        if (leftEyeImageCount == 4 && rightEyeImageCount == 4) {
                            captureComplete = true;
                            Toast.makeText(MainActivity3.this, "All images captured! Exiting...", Toast.LENGTH_LONG).show();
                            startActivity(new Intent(MainActivity3.this, NamingActivity.class));
                            finish();
                        }

                    } else {
                        Log.w("BIQT", imageLabel + " failed quality check → deleting temp file.");
                        if (tempFile.exists()) tempFile.delete();
                        Toast.makeText(MainActivity3.this, "Image rejected: Poor Quality. Retrying...", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onFailure(Exception e) {
                runOnUiThread(() -> {
                    Log.e("BIQT", imageLabel + " BIQT request failed: " + e.getMessage());
                    if (tempFile.exists()) tempFile.delete();
                });
            }
        });
    }

    private void setupFaceLandmarker() {
        BaseOptions baseOptions = BaseOptions.builder().setModelAssetPath("face_landmarker.task").build();
        faceLandmarker = FaceLandmarker.createFromOptions(this,
                FaceLandmarker.FaceLandmarkerOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setRunningMode(RunningMode.LIVE_STREAM)
                        .setResultListener(this::handleFaceLandmarkerResult)
                        .build());

        faceLandmarkerCapture = FaceLandmarker.createFromOptions(this,
                FaceLandmarker.FaceLandmarkerOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setRunningMode(RunningMode.IMAGE)
                        .build());
    }

    @Override
    public void onImageAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) return;

        byte[][] yuvBytes = new byte[3][];
        final Image.Plane[] planes = image.getPlanes();
        fillBytes(planes, yuvBytes);

        int[] rgbBytes = new int[previewWidth * previewHeight];
        ImageUtils.convertYUV420ToARGB8888(
                yuvBytes[0], yuvBytes[1], yuvBytes[2],
                previewWidth, previewHeight,
                planes[0].getRowStride(),
                planes[1].getRowStride(),
                planes[1].getPixelStride(),
                rgbBytes);

        Bitmap bitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(rgbBytes, 0, previewWidth, 0, 0, previewWidth, previewHeight);
        image.close();

        long timestamp = System.currentTimeMillis();
        MPImage mpImage = new BitmapImageBuilder(bitmap).build();
        faceLandmarker.detectAsync(mpImage, timestamp);
        lastCapturedBitmap = bitmap;
    }

    protected void fillBytes(final Image.Plane[] planes, final byte[][] yuvBytes) {
        for (int i = 0; i < planes.length; ++i) {
            final ByteBuffer buffer = planes[i].getBuffer();
            if (yuvBytes[i] == null) yuvBytes[i] = new byte[buffer.capacity()];
            buffer.get(yuvBytes[i]);
        }
    }

    private void handleFaceLandmarkerResult(FaceLandmarkerResult result, MPImage mpImage) {
        if (camera2Fragment == null) return;

        if (result.faceLandmarks().isEmpty()) {
            missingFaceFrameCount++;

            if (faceCurrentlyVisible && missingFaceFrameCount >= FACE_MISSING_FRAME_THRESHOLD) {
                runOnUiThread(() -> {
                    faceCurrentlyVisible = false;
                    zoomLocked = false;
                    resetZoomToDefault();
                    overlayView.setZoomRect(null);
                });
            }
            return;
        }

        missingFaceFrameCount = 0;
        if (!faceCurrentlyVisible) faceCurrentlyVisible = true;

        List<NormalizedLandmark> landmarks = result.faceLandmarks().get(0);
        int sensorRotation = camera2Fragment.getSensorOrientation();

        List<PointF> points = new ArrayList<>();
        for (int i = 0; i < landmarks.size(); i++) {
            PointF original = new PointF(landmarks.get(i).x(), landmarks.get(i).y());
            PointF rotated = rotateNormalizedPoint(original, sensorRotation);
            PointF pixel = new PointF(rotated.x * previewWidth, rotated.y * previewHeight);
            points.add(pixel);
        }

        PointF leftEye = points.get(468);
        PointF rightEye = points.get(473);
        this.leftEyeCenter = leftEye;
        this.rightEyeCenter = rightEye;

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE;
        for (PointF p : points) {
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
        }
        PointF faceCenter = new PointF((minX + maxX) / 2, (minY + maxY) / 2);

        runOnUiThread(() -> {
            overlayView.setLandmarks(points, previewWidth, previewHeight);
            overlayView.setFaceCenter(faceCenter);

            if (System.currentTimeMillis() - lastFocusTime > 2000) {
                setFocusOnEyes(leftEye, rightEye);
                lastFocusTime = System.currentTimeMillis();
            }
        });

        estimateFaceDistance(leftEye, rightEye);
    }

    private PointF rotateNormalizedPoint(PointF point, int rotationDegrees) {
        float x = point.x;
        float y = point.y;
        switch (rotationDegrees) {
            case 90:  return new PointF(1f - y, x);
            case 180: return new PointF(1f - x, 1f - y);
            case 270: return new PointF(y, 1f - x);
            default:  return new PointF(x, y);
        }
    }

    private PointF previewLeftEye;
    private PointF previewRightEye;

    private Bitmap rotateBitmap(Bitmap source, float angle) {
        Matrix matrix = new Matrix();
        matrix.postRotate(angle);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    private void setFocusOnEyes(PointF leftEye, PointF rightEye) {
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(camera2Fragment.getCameraId());
            Rect sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Rect zoomCrop = camera2Fragment.getCurrentZoomRegion();
            if (zoomCrop == null) zoomCrop = sensorArraySize;

            float shiftPixels = 20f;
            PointF center = new PointF(
                    ((leftEye.x + rightEye.x) / 2f) + shiftPixels,
                    (leftEye.y + rightEye.y) / 2f
            );

            float eyeDistance = (float) Math.hypot(
                    rightEye.x - leftEye.x,
                    rightEye.y - leftEye.y
            );

            float normalizedX = center.x / previewWidth;
            float normalizedY = center.y / previewHeight;
            float sensorX = normalizedX * zoomCrop.width() + zoomCrop.left;
            float sensorY = normalizedY * zoomCrop.height() + zoomCrop.top;

            overlayView.setAfCenter(new PointF(center.x, center.y));

            float scaleFactor = 10.0f;
            int boxWidth = (int) (eyeDistance * scaleFactor * (zoomCrop.width() / (float) previewWidth));
            int boxHeight = (int) (boxWidth * 0.1f);
            boxWidth = Math.max(400, Math.min(boxWidth, 2000));
            boxHeight = Math.max(80, Math.min(boxHeight, 200));

            Rect afRect = new Rect(
                    (int) sensorX - boxWidth / 2,
                    (int) sensorY - boxHeight / 2,
                    (int) sensorX + boxWidth / 2,
                    (int) sensorY + boxHeight / 2
            );
            afRect.intersect(sensorArraySize);

            camera2Fragment.setAfRegion(new MeteringRectangle[]{
                    new MeteringRectangle(afRect, MeteringRectangle.METERING_WEIGHT_MAX - 1)
            });
            overlayView.setAfRect(afRect);

            previewLeftEye = leftEye;
            previewRightEye = rightEye;

        } catch (Exception e) {
            Log.e("Focus", "Exception in setFocusOnEyes(): " + e.getMessage());
        }
    }

    private void resetZoomToDefault() {
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(camera2Fragment.getCameraId());
            Rect sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Rect fullRegion = new Rect(0, 0, sensorArraySize.width(), sensorArraySize.height());
            camera2Fragment.setZoomRegion(new Rect(fullRegion));
            currentZoomFactor = 1.0f;
        } catch (CameraAccessException e) {
            Log.e("Zoom", "Error resetting zoom: " + e.getMessage());
        }
    }

    private double calculateSharpness(Bitmap bitmap) {
        Mat mat = new Mat();
        Utils.bitmapToMat(bitmap, mat);
        Mat gray = new Mat();
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY);
        Mat laplacian = new Mat();
        Imgproc.Laplacian(gray, laplacian, CvType.CV_64F);
        MatOfDouble mu = new MatOfDouble();
        MatOfDouble sigma = new MatOfDouble();
        Core.meanStdDev(laplacian, mu, sigma);
        double sharpness = sigma.get(0, 0)[0];
        laplacian.release();
        gray.release();
        mat.release();
        return sharpness;
    }

    private double calculateContrast(Bitmap bitmap) {
        Mat mat = new Mat();
        Utils.bitmapToMat(bitmap, mat);
        Mat gray = new Mat();
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY);
        MatOfDouble mean = new MatOfDouble();
        MatOfDouble stddev = new MatOfDouble();
        Core.meanStdDev(gray, mean, stddev);
        double contrast = stddev.get(0, 0)[0];
        gray.release();
        mat.release();
        return contrast;
    }

    private Bitmap cropEyeRegion(Bitmap sourceBitmap, PointF center, String eyeSide) {
        final int cropWidth = 300;
        final int cropHeight = 300;

        if ("left".equals(eyeSide)) {
            center.x -= 5f;
        }

        int x = Math.round(center.x) - cropWidth / 2;
        int y = Math.round(center.y) - cropHeight / 2;
        x = Math.max(0, Math.min(x, sourceBitmap.getWidth() - cropWidth));
        y = Math.max(0, Math.min(y, sourceBitmap.getHeight() - cropHeight));

        return Bitmap.createBitmap(sourceBitmap, x, y, cropWidth, cropHeight);
    }

    private boolean zoomLocked = false;

    private float estimateFaceDistance(PointF leftEye, PointF rightEye) {
        float pixelDistance = (float) Math.hypot(
                rightEye.x - leftEye.x,
                rightEye.y - leftEye.y
        );
        float A = 7000f;
        float B = 0f;
        if (pixelDistance <= 0) return -1f;
        return A / pixelDistance + B;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (faceLandmarker != null) faceLandmarker.close();
        backgroundExecutor.shutdown();
    }
}