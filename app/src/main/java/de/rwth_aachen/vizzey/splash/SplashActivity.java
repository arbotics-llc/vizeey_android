package de.rwth_aachen.vizzey.splash;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;

import androidx.appcompat.app.AppCompatActivity;

import de.rwth_aachen.vizzey.ExperimentList;
import de.rwth_aachen.vizzey.R;

public class SplashActivity extends AppCompatActivity {
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(SplashActivity.this, ExperimentList.class));
                finish();
            }
        }, 1000);

    }
}
