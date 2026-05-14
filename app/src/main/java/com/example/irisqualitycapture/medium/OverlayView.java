package com.example.irisqualitycapture.medium;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class OverlayView extends View {

    private List<PointF> landmarkPoints = new ArrayList<>();
    private Paint pointPaint;
    private Paint boxPaint;
    private Paint afRectPaint;
    private Paint zoomRectPaint;
    private Paint afCenterPaint;
    private Paint faceCenterPaint;
    private Paint debugPaint;
    private int sourceWidth;
    private int sourceHeight;

    private Rect afRectSensor;
    private Rect zoomRectSensor;
    private int sensorWidth;
    private int sensorHeight;
    private String debugText = null;

    private PointF afCenter = null;
    private PointF faceCenter = null;

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        pointPaint = new Paint();
        pointPaint.setColor(Color.RED);
        pointPaint.setStrokeWidth(15);
        pointPaint.setStyle(Paint.Style.FILL);

        boxPaint = new Paint();
        boxPaint.setColor(Color.GREEN);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(4);

        afRectPaint = new Paint();
        afRectPaint.setColor(Color.BLUE);
        afRectPaint.setStyle(Paint.Style.STROKE);
        afRectPaint.setStrokeWidth(4);

        zoomRectPaint = new Paint();
        zoomRectPaint.setColor(Color.YELLOW);
        zoomRectPaint.setStyle(Paint.Style.STROKE);
        zoomRectPaint.setStrokeWidth(4);

        afCenterPaint = new Paint();
        afCenterPaint.setColor(Color.CYAN);
        afCenterPaint.setStyle(Paint.Style.FILL);

        faceCenterPaint = new Paint();
        faceCenterPaint.setColor(Color.MAGENTA);
        faceCenterPaint.setStyle(Paint.Style.STROKE);
        faceCenterPaint.setStrokeWidth(6);

        debugPaint = new Paint();
        debugPaint.setColor(Color.WHITE);
        debugPaint.setTextSize(40);
        debugPaint.setShadowLayer(5.0f, 2.0f, 2.0f, Color.BLACK);
    }

    public void setLandmarks(List<PointF> points, int sourceWidth, int sourceHeight) {
        this.landmarkPoints = points;
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        invalidate();
    }

    public void setSensorInfo(int sensorWidth, int sensorHeight) {
        this.sensorWidth = sensorWidth;
        this.sensorHeight = sensorHeight;
    }

    public void setAfRect(Rect sensorRect) {
        this.afRectSensor = sensorRect;
        invalidate();
    }

    public void setZoomRect(Rect sensorRect) {
        this.zoomRectSensor = sensorRect;
        invalidate();
    }

    public void setAfCenter(PointF center) {
        this.afCenter = center;
        invalidate();
    }

    public void setFaceCenter(PointF center) {
        this.faceCenter = center;
        invalidate();
    }

    public void setDebugText(String text) {
        this.debugText = text;
        invalidate();
    }

    private RectF scaleRect(Rect sensorRect) {
        if (sensorRect == null || sensorWidth == 0 || sensorHeight == 0) return null;

        float scaleX = (float) getWidth() / sensorWidth;
        float scaleY = (float) getHeight() / sensorHeight;

        return new RectF(
                sensorRect.left * scaleX,
                sensorRect.top * scaleY,
                sensorRect.right * scaleX,
                sensorRect.bottom * scaleY
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (sourceWidth <= 0 || sourceHeight <= 0 || landmarkPoints.isEmpty()) {
            return;
        }

        float scaleX = (float) getWidth() / sourceWidth;
        float scaleY = (float) getHeight() / sourceHeight;

        if (afRectSensor != null) {
            RectF af = scaleRect(afRectSensor);
            if (af != null) {
                canvas.drawRect(af, afRectPaint);
            }
        }

        if (zoomRectSensor != null) {
            RectF zoom = scaleRect(zoomRectSensor);
            if (zoom != null) {
                canvas.drawRect(zoom, zoomRectPaint);
            }
        }

        if (afCenter != null) {
            float cx = afCenter.x * scaleX;
            float cy = afCenter.y * scaleY;
            canvas.drawCircle(cx, cy, 12, afCenterPaint);
        }

        if (faceCenter != null) {
            float cx = faceCenter.x * scaleX;
            float cy = faceCenter.y * scaleY;
            canvas.drawCircle(cx, cy, 20, faceCenterPaint);
        }

        if (debugText != null) {
            canvas.drawText(debugText, 20, 60, debugPaint);
        }
    }
}
