package com.example.irisqualitycapture.medium;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import com.example.irisqualitycapture.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;


/**
 * Camera Connection Fragment that captures images from camera.
 *
 * <p>Instantiated by newInstance.</p>
 */
@SuppressLint("ValidFragment")
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
@SuppressWarnings("FragmentNotInstantiable")
public class CameraConnectionFragment extends Fragment {


    /**
     * The camera preview size will be chosen to be the smallest frame by pixel size capable of
     * containing a DESIRED_SIZE x DESIRED_SIZE square.
     */
    private static final int MINIMUM_PREVIEW_SIZE = 320;

    /** Conversion from screen rotation to JPEG orientation. */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    private static final String FRAGMENT_DIALOG = "dialog";

    private Surface previewSurface;

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    /** A {@link Semaphore} to prevent the app from exiting before closing the camera. */
    private final Semaphore cameraOpenCloseLock = new Semaphore(1);
    /** A {@link OnImageAvailableListener} to receive frames as they are available. */
    private final OnImageAvailableListener imageListener;
    /** The input size in pixels desired by TensorFlow (width and height of a square bitmap). */
    private final Size inputSize;
    /** The layout identifier to inflate for this Fragment. */
    private final int layout;

    private static final int STATE_PREVIEW = 0;
    private static final int STATE_WAITING_LOCK = 1;
    private static final int STATE_PICTURE_TAKEN = 2;
    private int mState = STATE_PREVIEW;

    private final ConnectionCallback cameraConnectionCallback;
    private final CameraCaptureSession.CaptureCallback captureCallback =
            new CameraCaptureSession.CaptureCallback() {
                private void process(CaptureResult result) {
                    switch (mState) {
                        case STATE_PREVIEW: {
                            break;
                        }
                        case STATE_WAITING_LOCK: {
                            Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                            if (afState == null) {
                                captureFinalStill();
                            } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                                    CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                                Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                                if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                                    mState = STATE_PICTURE_TAKEN;
                                    captureFinalStill();
                                }
                            }
                            break;
                        }
                    }
                }

                @Override
                public void onCaptureProgressed(
                        final CameraCaptureSession session,
                        final CaptureRequest request,
                        final CaptureResult partialResult) {
                    process(partialResult);
                }

                @Override
                public void onCaptureCompleted(
                        final CameraCaptureSession session,
                        final CaptureRequest request,
                        final TotalCaptureResult result) {
                    process(result);
                }
            };

    private String cameraId;
    private AutoFitTextureView textureView;
    private CameraCaptureSession captureSession;
    private CameraDevice cameraDevice;
    private Integer sensorOrientation;
    private Size previewSize;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(
                        final SurfaceTexture texture, final int width, final int height) {
                    openCamera(width, height);
                }

                @Override
                public void onSurfaceTextureSizeChanged(
                        final SurfaceTexture texture, final int width, final int height) {
                    configureTransform(width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(final SurfaceTexture texture) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(final SurfaceTexture texture) {
                }
            };
    private ImageReader previewReader;
    private CaptureRequest.Builder previewRequestBuilder;
    private CaptureRequest previewRequest;

    private OverlayView overlayView;
    private MeteringRectangle[] afRegions;
    private Rect zoomRegion;
    private CameraCaptureSession cameraCaptureSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private ImageReader captureReader;


    public interface OnImageCapturedListener {
        void onImageCaptured(Bitmap highResImage);
    }

    private OnImageCapturedListener imageCapturedListener;

    public void setOnImageCapturedListener(OnImageCapturedListener listener) {
        this.imageCapturedListener = listener;
    }

    public void setAfRegion(MeteringRectangle[] regions) {
        this.afRegions = regions;
        startAutoFocusWithAfRegion();
    }





    public interface LandmarkCallback {
        void onLandmarksDetected(List<PointF> points);
    }

    public void setZoomRegion(Rect region) {
        this.zoomRegion = region;
        updateCameraRequest();
    }
    public Rect getCurrentZoomRegion() {
        return zoomRegion;
    }

    private final CameraDevice.StateCallback stateCallback =
            new CameraDevice.StateCallback() {
                @Override
                public void onOpened(final CameraDevice cd) {
                    // This method is called when the camera is opened.  We start camera preview here.
                    cameraOpenCloseLock.release();
                    cameraDevice = cd;
                    createCameraPreviewSession();
                }

                @Override
                public void onDisconnected(final CameraDevice cd) {
                    cameraOpenCloseLock.release();
                    cd.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(final CameraDevice cd, final int error) {
                    cameraOpenCloseLock.release();
                    cd.close();
                    cameraDevice = null;
                    final Activity activity = getActivity();
                    if (null != activity) {
                        activity.finish();
                    }
                }
            };

    private void startAutoFocusWithAfRegion() {
        if (cameraDevice == null || cameraCaptureSession == null) return;

        try {
            final CaptureRequest.Builder focusBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            focusBuilder.addTarget(previewSurface);

            focusBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, afRegions);
            focusBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            focusBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);

            if (zoomRegion != null)
                focusBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoomRegion);

            cameraCaptureSession.capture(focusBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                    if (afState != null &&
                            (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                                    afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED)) {
                        captureStillPicture();    // fire capture as soon as lock is done
                    }
                }
            }, backgroundHandler);

        } catch (CameraAccessException e) {
            Log.e("AF_Debug", "startAutoFocusWithAfRegion failed: " + e.getMessage());
        }
    }

    @SuppressLint("ValidFragment")
    private CameraConnectionFragment(
            final ConnectionCallback connectionCallback,
            final OnImageAvailableListener imageListener,
            final int layout,
            final Size inputSize) {
        this.cameraConnectionCallback = connectionCallback;
        this.imageListener = imageListener;
        this.layout = layout;
        this.inputSize = inputSize;
    }

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, chooses the smallest one whose
     * width and height are at least as large as the minimum of both, or an exact match if possible.
     *
     * @param choices The list of sizes that the camera supports for the intended output class
     * @param width The minimum desired width
     * @param height The minimum desired height
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    protected static Size chooseOptimalSize(final Size[] choices, final int width, final int height) {
        final int minSize = Math.max(Math.min(width, height), MINIMUM_PREVIEW_SIZE);
        final Size desiredSize = new Size(width, height);

        // Collect the supported resolutions that are at least as big as the preview Surface
        boolean exactSizeFound = false;
        final List<Size> bigEnough = new ArrayList<Size>();
        final List<Size> tooSmall = new ArrayList<Size>();
        for (final Size option : choices) {
            if (option.equals(desiredSize)) {
                // Set the size but don't return yet so that remaining sizes will still be logged.
                exactSizeFound = true;
            }

            if (option.getHeight() >= minSize && option.getWidth() >= minSize) {
                bigEnough.add(option);
            } else {
                tooSmall.add(option);
            }
        }

        if (exactSizeFound) {
            return desiredSize;
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            final Size chosenSize = Collections.min(bigEnough, new CompareSizesByArea());
            // LOGGER.i("Chosen size: " + chosenSize.getWidth() + "x" + chosenSize.getHeight());
            return chosenSize;
        } else {
            // LOGGER.e("Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    public static CameraConnectionFragment newInstance(
            final ConnectionCallback callback,
            final OnImageAvailableListener imageListener,
            final int layout,
            final Size inputSize) {
        return new CameraConnectionFragment(callback, imageListener, layout, inputSize);
    }

    /**
     * Shows a {@link Toast} on the UI thread.
     *
     * @param text The message to show
     */
    private void showToast(final String text) {
        final Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(
                    new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
                        }
                    });
        }
    }

    public void captureStillPicture() {
        try {
            if (cameraDevice == null || cameraCaptureSession == null) return;

            mState = STATE_WAITING_LOCK;

            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
            cameraCaptureSession.capture(captureRequestBuilder.build(), captureCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e("CameraConnection", "captureStillPicture failed: " + e.getMessage());
        }
    }

    public void triggerFinalCaptureAfterAF() {
        captureStillPicture();
    }
    private void captureFinalStill() {
        try {
            final CaptureRequest.Builder captureBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 100);  //Max JPEG quality
            captureBuilder.addTarget(captureReader.getSurface());

            if (afRegions != null) {
                captureBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, afRegions);
            }
            if (zoomRegion != null) {
                captureBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoomRegion);
            }

            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            captureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            captureBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);

            captureBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF);
            captureBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF);

            captureBuilder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF);

            captureBuilder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF);

            cameraCaptureSession.capture(captureBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    // Reset focus trigger and state back to preview
                    try {
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
                        cameraCaptureSession.capture(captureRequestBuilder.build(), captureCallback, backgroundHandler);
                        mState = STATE_PREVIEW;
                        cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), captureCallback, backgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }
            }, backgroundHandler);

        } catch (CameraAccessException e) {
            Log.e("ResearchCapture", "Final capture failed: " + e.getMessage());
        }
    }

    private void updateCameraRequest() {
        if (captureRequestBuilder != null && cameraCaptureSession != null) {
            try {
                if (afRegions != null) {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, afRegions);
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
                }
                if (zoomRegion != null) {
                    captureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoomRegion);
                }

                // Apply updated repeating request for preview
                cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), captureCallback, backgroundHandler);

            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
    }


    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
        return inflater.inflate(layout, container, false);
    }

    @Override
    public void onViewCreated(final View view, final Bundle savedInstanceState) {
        textureView = view.findViewById(R.id.texture);
        overlayView = view.findViewById(R.id.overlay); // Connect to overlay here
    }


    @Override
    public void onActivityCreated(final Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if(textureView==null){
            System.out.println("textview    =   "+textureView);
            return;}
        if (textureView.isAvailable()) {
            openCamera(textureView.getWidth(), textureView.getHeight());
        } else {
            textureView.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    @Override
    public void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }
    public void setCamera(String cameraId) {
        this.cameraId = cameraId;
    }

    public String getCameraId() {
        return cameraId;
    }

    public int getSensorOrientation() {
        return sensorOrientation != null ? sensorOrientation : 0;
    }

    /** Sets up member variables related to camera. */
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void setUpCameraOutputs() {
        final Activity activity = getActivity();
        final CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (final String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);

                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    this.cameraId = cameraId;
                    break;
                }
            }

            final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

            int[] capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            if (capabilities != null) {
                StringBuilder caps = new StringBuilder();
                for (int cap : capabilities) {
                    switch (cap) {
                        case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE:
                            caps.append("BACKWARD_COMPATIBLE, ");
                            break;
                        case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR:
                            caps.append("MANUAL_SENSOR, ");
                            break;
                        case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING:
                            caps.append("MANUAL_POST_PROCESSING, ");
                            break;
                        case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW:
                            caps.append("RAW, ");
                            break;
                        case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING:
                            caps.append("PRIVATE_REPROCESSING, ");
                            break;
                        case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS:
                            caps.append("READ_SENSOR_SETTINGS, ");
                            break;
                        case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE:
                            caps.append("BURST_CAPTURE, ");
                            break;
                        case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING:
                            caps.append("YUV_REPROCESSING, ");
                            break;
                        case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT:
                            caps.append("DEPTH_OUTPUT, ");
                            break;
                        case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO:
                            caps.append("HIGH_SPEED_VIDEO, ");
                            break;
                        case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING:
                            caps.append("MOTION_TRACKING, ");
                            break;
                        case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA:
                            caps.append("MULTI_CAMERA, ");
                            break;
                        case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME:
                            caps.append("MONOCHROME, ");
                            break;
                        case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA:
                            caps.append("SECURE_IMAGE_DATA, ");
                            break;
                        case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR:
                            caps.append("ULTRA_HIGH_RES, ");
                            break;
                        case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING:
                            caps.append("REMOSAIC_REPROCESSING, ");
                            break;
                        default:
                            caps.append("UNKNOWN(").append(cap).append("), ");
                    }
                }
            }


            final StreamConfigurationMap map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

            // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
            // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
            // garbage capture data.
            previewSize =
                    chooseOptimalSize(
                            map.getOutputSizes(SurfaceTexture.class),
                            inputSize.getWidth(),
                            inputSize.getHeight());

            final int orientation = getResources().getConfiguration().orientation;
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                textureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
            } else {
                textureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
            }

            Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
            // Cap at 12MP to avoid session configuration failures on very high-res sensors (e.g. S24 Ultra 200MP)
            final long MAX_CAPTURE_PIXELS = 4032L * 3024L;
            Size largest = null;
            for (Size s : jpegSizes) {
                long area = (long) s.getWidth() * s.getHeight();
                if (area <= MAX_CAPTURE_PIXELS &&
                        (largest == null || area > (long) largest.getWidth() * largest.getHeight())) {
                    largest = s;
                }
            }
            if (largest == null) {
                largest = Collections.max(Arrays.asList(jpegSizes), new CameraConnectionFragment.CompareSizesByArea());
            }

            Log.d("CameraCapture", "Using JPEG size: " + largest.getWidth() + "x" + largest.getHeight());


            captureReader = ImageReader.newInstance(
                    largest.getWidth(),
                    largest.getHeight(),
                    ImageFormat.JPEG,
                    2
            );

            captureReader.setOnImageAvailableListener(reader -> {
                Image image = reader.acquireNextImage();
                if (image != null) {
                    Bitmap bitmap = ImageUtils.imageToBitmap(image);
                    image.close();

                    if (imageCapturedListener != null && bitmap != null) {
                        imageCapturedListener.onImageCaptured(bitmap);
                    }
                } else {
                    Log.e("CameraCapture", "captureReader returned null image!");
                }
            }, backgroundHandler);

        } catch (final CameraAccessException e) {
            //  LOGGER.e(e, "Exception!");
        } catch (final NullPointerException e) {
            throw new IllegalStateException("getString(R.string.tfe_ic_camera_error)");
        }

        cameraConnectionCallback.onPreviewSizeChosen(previewSize, sensorOrientation);
    }

    public void triggerAutoFocusThenCapture() {
        if (cameraDevice == null || cameraCaptureSession == null) {
            Log.w("AF_Debug", "Camera not ready, skipping triggerAutoFocusThenCapture");
            return;
        }

        try {
            final CaptureRequest.Builder focusBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            focusBuilder.addTarget(previewSurface);

            boolean usingAfRegion = false;

            focusBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            if (afRegions != null) {
                focusBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, afRegions);
                focusBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                focusBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
                usingAfRegion = true;
            } else {
                Log.d("AF_Debug", "No AF region set → using CONTINUOUS_PICTURE mode");
            }

            if (zoomRegion != null)
                focusBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoomRegion);



            cameraCaptureSession.capture(focusBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                    String stateDesc;
                    if (afState != null) {
                        switch (afState) {
                            case CaptureResult.CONTROL_AF_STATE_INACTIVE:
                                stateDesc = "INACTIVE (0)";
                                break;
                            case CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN:
                                stateDesc = "PASSIVE_SCAN (1)";
                                break;
                            case CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED:
                                stateDesc = "PASSIVE_FOCUSED (2)";
                                break;
                            case CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN:
                                stateDesc = "ACTIVE_SCAN (3)";
                                break;
                            case CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED:
                                stateDesc = "FOCUSED_LOCKED (4)";
                                break;
                            case CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED:
                                stateDesc = "NOT_FOCUSED_LOCKED (5)";
                                break;
                            case CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED:
                                stateDesc = "PASSIVE_UNFOCUSED (6)";
                                break;
                            default:
                                stateDesc = "UNKNOWN (" + afState + ")";
                        }
                    } else {
                        stateDesc = "NULL";
                    }

                    if (afState != null && (
                            afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                                    afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED)) {;
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            captureStillPicture();
                        }, 600);

                    } else if (afState != null && afState == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN) {
                        //Log.w("AF_Debug", "AF still ACTIVE_SCAN → skipping capture (no lock)");
                     //                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
//                        Log.d("AF_Debug", "Retrying autofocus after ACTIVE_SCAN");
//                        triggerAutoFocusThenCapture();
//                    }, 300);
                    } else {
                        //Log.w("AF_Debug", "AF not locked → skipping capture.");
                    }

                    if (previewRequestBuilder != null) {
                        try {
                            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                                    CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
                            cameraCaptureSession.setRepeatingRequest(previewRequestBuilder.build(),
                                    captureCallback, backgroundHandler);
                            //Log.d("AF_Debug", "AF trigger reset to IDLE");
                        } catch (CameraAccessException e) {
                            Log.e("AF_Debug", "Failed to reset AF trigger: " + e.getMessage());
                        }
                    } else {
                        Log.w("AF_Debug", "previewRequestBuilder is null → skipping AF trigger reset");
                    }
                }
            }, backgroundHandler);

        } catch (CameraAccessException e) {
            Log.e("AF_Debug", "triggerAutoFocusThenCapture failed: " + e.getMessage());
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @SuppressLint("MissingPermission")
    private void openCamera(final int width, final int height) {
        setUpCameraOutputs();
        configureTransform(width, height);
        final Activity activity = getActivity();
        final CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            if (ActivityCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }
            manager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (final CameraAccessException e) {
            // LOGGER.e(e, "Exception!");
        } catch (final InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    /** Closes the current {@link CameraDevice}. */
    private void closeCamera() {
        try {
            cameraOpenCloseLock.acquire();
            if (null != cameraCaptureSession) {  // was: captureSession
                cameraCaptureSession.close();
                cameraCaptureSession = null;
            }
            if (null != cameraDevice) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (null != previewReader) {
                previewReader.close();
                previewReader = null;
            }
            if (null != captureReader) {  // add this — it's never closed!
                captureReader.close();
                captureReader = null;
            }
        } catch (final InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            cameraOpenCloseLock.release();
        }
    }

    /** Starts a background thread and its {@link Handler}. */
    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("ImageListener");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    /** Stops the background thread and its {@link Handler}. */
    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
    private void stopBackgroundThread() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            backgroundThread.quitSafely();

            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (final InterruptedException e) {
                //    LOGGER.e(e, "Exception!");
            }
        }
    }

    /** Creates a new {@link CameraCaptureSession} for camera preview. */
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void createCameraPreviewSession() {
        try {
            final SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;

            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            final Surface surface = new Surface(texture);
            previewSurface = surface;

            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW); // Assign here
            captureRequestBuilder.addTarget(surface);

            previewReader = ImageReader.newInstance(previewSize.getWidth(), previewSize.getHeight(), ImageFormat.YUV_420_888, 2);
            previewReader.setOnImageAvailableListener(imageListener, backgroundHandler);
            captureRequestBuilder.addTarget(previewReader.getSurface());

            cameraDevice.createCaptureSession(Arrays.asList(surface, previewReader.getSurface(), captureReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (cameraDevice == null) return;
                            cameraCaptureSession = session;

                            try {

                                captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                                captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);

                                previewRequest = captureRequestBuilder.build();
                                cameraCaptureSession.setRepeatingRequest(previewRequest, captureCallback, backgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            Log.e("CameraConnection", "Configuration failed");
                        }
                    }, null);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    /**
     * Configures the necessary {@link Matrix} transformation to `mTextureView`. This method should be
     * called after the camera preview size is determined in setUpCameraOutputs and also the size of
     * `mTextureView` is fixed.
     *
     * @param viewWidth The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(final int viewWidth, final int viewHeight) {
        final Activity activity = getActivity();
        if (null == textureView || null == previewSize || null == activity) {
            return;
        }
        final int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        final Matrix matrix = new Matrix();
        final RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        final RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        final float centerX = viewRect.centerX();
        final float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            final float scale =
                    Math.max(
                            (float) viewHeight / previewSize.getHeight(),
                            (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        textureView.setTransform(matrix);
    }

    /**
     * Callback for Activities to use to initialize their data once the selected preview size is
     * known.
     */
    public interface ConnectionCallback {
        void onPreviewSizeChosen(Size size, int cameraRotation);
    }

    /** Compares two {@code Size}s based on their areas. */
    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(final Size lhs, final Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum(
                    (long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    /** Shows an error message dialog. */
    public static class ErrorDialog extends DialogFragment {
        private static final String ARG_MESSAGE = "message";

        public static ErrorDialog newInstance(final String message) {
            final ErrorDialog dialog = new ErrorDialog();
            final Bundle args = new Bundle();
            args.putString(ARG_MESSAGE, message);
            dialog.setArguments(args);
            return dialog;
        }

        @Override
        public Dialog onCreateDialog(final Bundle savedInstanceState) {
            final Activity activity = getActivity();
            return new AlertDialog.Builder(activity)
                    .setMessage(getArguments().getString(ARG_MESSAGE))
                    .setPositiveButton(
                            android.R.string.ok,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(final DialogInterface dialogInterface, final int i) {
                                    activity.finish();
                                }
                            })
                    .create();
        }
    }
}

