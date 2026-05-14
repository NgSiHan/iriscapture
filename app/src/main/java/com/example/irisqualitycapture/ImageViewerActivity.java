package com.example.irisqualitycapture;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ImageViewerActivity extends AppCompatActivity {

    public static final String EXTRA_PATHS = "image_paths";
    public static final String EXTRA_TITLE = "image_title";

    private TextView pageCounter;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_viewer);

        MaterialToolbar toolbar = findViewById(R.id.viewerToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title != null && getSupportActionBar() != null) getSupportActionBar().setTitle(title);

        ArrayList<String> paths = getIntent().getStringArrayListExtra(EXTRA_PATHS);
        if (paths == null || paths.isEmpty()) { finish(); return; }

        pageCounter = findViewById(R.id.pageCounter);
        ViewPager2 pager = findViewById(R.id.imagePager);

        pager.setAdapter(new ImagePagerAdapter(paths));
        updateCounter(1, paths.size());

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateCounter(position + 1, paths.size());
            }
        });
    }

    private void updateCounter(int current, int total) {
        pageCounter.setText(current + " / " + total);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    // =========================================================================
    // ViewPager2 adapter
    // =========================================================================

    private class ImagePagerAdapter extends androidx.recyclerview.widget.RecyclerView.Adapter<ImagePagerAdapter.ImageViewHolder> {

        private final ArrayList<String> paths;

        ImagePagerAdapter(ArrayList<String> paths) {
            this.paths = paths;
        }

        @NonNull
        @Override
        public ImageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ImageView iv = new ImageView(ImageViewerActivity.this);
            iv.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(0xFF111111);
            return new ImageViewHolder(iv);
        }

        @Override
        public void onBindViewHolder(@NonNull ImageViewHolder holder, int position) {
            String path = paths.get(position);
            holder.imageView.setImageBitmap(null);
            holder.imageView.setTag(path);

            executor.execute(() -> {
                Bitmap bmp = BitmapFactory.decodeFile(path);
                mainHandler.post(() -> {
                    if (path.equals(holder.imageView.getTag())) {
                        holder.imageView.setImageBitmap(bmp);
                    }
                });
            });
        }

        @Override
        public int getItemCount() { return paths.size(); }

        class ImageViewHolder extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
            final ImageView imageView;
            ImageViewHolder(ImageView iv) {
                super(iv);
                this.imageView = iv;
            }
        }
    }
}
