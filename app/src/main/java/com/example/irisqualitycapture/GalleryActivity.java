package com.example.irisqualitycapture;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GalleryActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private TextView emptyStateText;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        recyclerView   = findViewById(R.id.galleryRecyclerView);
        emptyStateText = findViewById(R.id.emptyStateText);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        loadGallery();
    }

    private void loadGallery() {
        File downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        TreeMap<String, List<CaptureGroup>> data = ImageFileParser.parse(downloadsDir);

        // Build a flat list: SubjectHeader (String) + CaptureGroup items
        List<Object> items = new ArrayList<>();
        for (Map.Entry<String, List<CaptureGroup>> entry : data.entrySet()) {
            items.add(entry.getKey());          // subject header
            items.addAll(entry.getValue());     // capture groups
        }

        if (items.isEmpty()) {
            emptyStateText.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyStateText.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            recyclerView.setAdapter(new GalleryAdapter(items, data));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    // =========================================================================
    // Adapter
    // =========================================================================

    private static final int VIEW_TYPE_HEADER  = 0;
    private static final int VIEW_TYPE_CAPTURE = 1;

    private class GalleryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private final List<Object> items;
        private final TreeMap<String, List<CaptureGroup>> data;

        GalleryAdapter(List<Object> items, TreeMap<String, List<CaptureGroup>> data) {
            this.items = items;
            this.data  = data;
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position) instanceof String ? VIEW_TYPE_HEADER : VIEW_TYPE_CAPTURE;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (viewType == VIEW_TYPE_HEADER) {
                View v = getLayoutInflater().inflate(R.layout.item_subject_header, parent, false);
                return new HeaderViewHolder(v);
            } else {
                View v = getLayoutInflater().inflate(R.layout.item_capture_group, parent, false);
                return new CaptureViewHolder(v);
            }
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof HeaderViewHolder) {
                bindHeader((HeaderViewHolder) holder, (String) items.get(position));
            } else {
                bindCapture((CaptureViewHolder) holder, (CaptureGroup) items.get(position));
            }
        }

        @Override
        public int getItemCount() { return items.size(); }

        // --- Header ---

        private void bindHeader(HeaderViewHolder h, String subjectID) {
            List<CaptureGroup> groups = data.get(subjectID);
            int captureCount = 0;
            if (groups != null) for (CaptureGroup g : groups) captureCount += g.totalImages();

            h.subjectIdText.setText("Subject  " + subjectID);
            h.captureCountText.setText(captureCount + " image" + (captureCount == 1 ? "" : "s"));
        }

        // --- Capture group ---

        private void bindCapture(CaptureViewHolder h, CaptureGroup group) {
            h.sessionTrialLabel.setText(
                    "Session " + group.sessionID + "  ·  Trial " + group.trialNum);

            int totalVariants = Math.max(group.leftEyeFiles.size(), group.rightEyeFiles.size());
            h.variantCountBadge.setText(totalVariants + (totalVariants == 1 ? " image" : " images"));

            // Cancel any previous async load for these views
            h.leftEyeThumb.setTag(null);
            h.rightEyeThumb.setTag(null);
            h.leftEyeThumb.setImageBitmap(null);
            h.rightEyeThumb.setImageBitmap(null);

            loadThumbnail(h.leftEyeThumb,
                    group.leftEyeFiles.isEmpty() ? null : group.leftEyeFiles.get(0));
            loadThumbnail(h.rightEyeThumb,
                    group.rightEyeFiles.isEmpty() ? null : group.rightEyeFiles.get(0));

            // Click listeners → open ImageViewerActivity
            h.leftEyeThumb.setOnClickListener(v -> openViewer(group, "left"));
            h.rightEyeThumb.setOnClickListener(v -> openViewer(group, "right"));
        }

        private void loadThumbnail(ImageView imageView, File file) {
            if (file == null) {
                imageView.setImageBitmap(null);
                return;
            }
            String path = file.getAbsolutePath();
            imageView.setTag(path);   // used to detect stale loads
            executor.execute(() -> {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 2; // downsample to save memory (300px → 150px)
                Bitmap bmp = BitmapFactory.decodeFile(path, opts);
                mainHandler.post(() -> {
                    // Only apply if the view hasn't been recycled to a different item
                    if (path.equals(imageView.getTag())) {
                        imageView.setImageBitmap(bmp);
                    }
                });
            });
        }

        private void openViewer(CaptureGroup group, String eye) {
            List<File> files = "left".equals(eye) ? group.leftEyeFiles : group.rightEyeFiles;
            if (files.isEmpty()) return;

            ArrayList<String> paths = new ArrayList<>();
            for (File f : files) paths.add(f.getAbsolutePath());

            String title = "Subject " + group.subjectID
                    + " · Session " + group.sessionID
                    + " · Trial " + group.trialNum
                    + " · " + (eye.equals("left") ? "Left" : "Right");

            Intent intent = new Intent(GalleryActivity.this, ImageViewerActivity.class);
            intent.putStringArrayListExtra(ImageViewerActivity.EXTRA_PATHS, paths);
            intent.putExtra(ImageViewerActivity.EXTRA_TITLE, title);
            startActivity(intent);
        }
    }

    // =========================================================================
    // ViewHolders
    // =========================================================================

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView subjectIdText, captureCountText;
        HeaderViewHolder(View v) {
            super(v);
            subjectIdText    = v.findViewById(R.id.subjectIdText);
            captureCountText = v.findViewById(R.id.captureCountText);
        }
    }

    static class CaptureViewHolder extends RecyclerView.ViewHolder {
        TextView sessionTrialLabel, variantCountBadge;
        ImageView leftEyeThumb, rightEyeThumb;
        CaptureViewHolder(View v) {
            super(v);
            sessionTrialLabel = v.findViewById(R.id.sessionTrialLabel);
            variantCountBadge = v.findViewById(R.id.variantCountBadge);
            leftEyeThumb      = v.findViewById(R.id.leftEyeThumb);
            rightEyeThumb     = v.findViewById(R.id.rightEyeThumb);
        }
    }
}
