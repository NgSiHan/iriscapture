package com.example.irisqualitycapture;

import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;

import com.example.irisqualitycapture.medium.MainActivity3;

public class NamingActivity extends AppCompatActivity {
    private String sub_ID;
    private String session_ID;
    private String trial_Num;
    private int image_Count = 4;
    private boolean torch_Enabled = true;

    private String TAG = "NamingActivity:";

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        int height = displayMetrics.heightPixels;
        int width = displayMetrics.widthPixels;

        setContentView(R.layout.naming);

        MaterialToolbar toolbar = findViewById(R.id.naming_toolbar);
        setSupportActionBar(toolbar);

        EditText usr_sub_id = findViewById(R.id.editsubid);
        usr_sub_id.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {}

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {}

            @Override
            public void afterTextChanged(Editable editable) {
                sub_ID = editable.toString();
            }
        });

        EditText usr_session_id = findViewById(R.id.editsessionid);
        usr_session_id.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {}

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {}

            @Override
            public void afterTextChanged(Editable editable) {
                session_ID = editable.toString();
            }
        });

        EditText usr_trial_num = findViewById(R.id.edittrailnum);
        usr_trial_num.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {}

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {}

            @Override
            public void afterTextChanged(Editable editable) {
                trial_Num = editable.toString();
            }
        });

        EditText usr_image_count = findViewById(R.id.editimagecount);
        usr_image_count.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {}

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {}

            @Override
            public void afterTextChanged(Editable editable) {
                String s = editable.toString();
                if (!s.isEmpty()) {
                    try {
                        int parsed = Integer.parseInt(s);
                        if (parsed > 0) image_Count = parsed;
                    } catch (NumberFormatException ignored) {}
                }
            }
        });

        Switch switchTorch = findViewById(R.id.switchTorch);
        switchTorch.setOnCheckedChangeListener((v, checked) -> torch_Enabled = checked);

        Button next_button = findViewById(R.id.nextButton);
        next_button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(NamingActivity.this, MainActivity3.class);
                intent.putExtra("N_subID", sub_ID);
                intent.putExtra("N_sessionID", session_ID);
                intent.putExtra("N_trialNum", trial_Num);
                intent.putExtra("N_imageCount", image_Count);
                intent.putExtra("N_torchEnabled", torch_Enabled);
                startActivity(intent);
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_naming, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_gallery) {
            startActivity(new Intent(this, GalleryActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        String prevSub = intent.getStringExtra("PREV_subID");
        if (prevSub == null) return;

        ((EditText) findViewById(R.id.editsubid)).setText(prevSub);
        ((EditText) findViewById(R.id.editsessionid)).setText(intent.getStringExtra("PREV_sessionID"));
        ((EditText) findViewById(R.id.edittrailnum)).setText(intent.getStringExtra("PREV_trialNum"));
        ((EditText) findViewById(R.id.editimagecount)).setText(String.valueOf(intent.getIntExtra("PREV_imageCount", 4)));
        ((Switch) findViewById(R.id.switchTorch)).setChecked(intent.getBooleanExtra("PREV_torchEnabled", true));

        // Sync backing fields
        sub_ID        = prevSub;
        session_ID    = intent.getStringExtra("PREV_sessionID");
        trial_Num     = intent.getStringExtra("PREV_trialNum");
        image_Count   = intent.getIntExtra("PREV_imageCount", 4);
        torch_Enabled = intent.getBooleanExtra("PREV_torchEnabled", true);
    }
}
