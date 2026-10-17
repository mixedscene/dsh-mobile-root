package com.dshmobile.app;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;

/** Explicit root-only setup; installation never starts on ordinary app launch. */
public final class SetupActivity extends Activity {
    private TextView status;
    private Button install;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout box = new LinearLayout(this); box.setOrientation(1); box.setPadding(24,48,24,24);
        box.addView(Ui.title(this, "DSH Root setup"));
        status = Ui.body(this, "Requires a real su grant with SELinux Enforcing. Runtime assets are installed only after confirmation. Android 16/17 device behavior remains unverified."); box.addView(status);
        install = Ui.primaryButton(this, "Probe root and install bundled runtime"); box.addView(install);
        install.setOnClickListener(v -> {
            install.setEnabled(false);
            new Thread(() -> {
                try {
                    RootCapabilities capabilities = RootRuntime.probe(this);
                    if (!capabilities.root) throw new java.io.IOException("Real root is required");
                    runOnUiThread(() -> status.setText(String.valueOf(capabilities)));
                    RuntimeInstaller.install(this, text -> runOnUiThread(() -> status.setText(text)));
                    runOnUiThread(() -> { startActivity(new Intent(this, MainActivity.class)); finish(); });
                } catch (Exception e) { runOnUiThread(() -> { status.setText("Setup failed: " + e.getClass().getSimpleName() + ". Check root authorization, bundled assets and free space."); install.setEnabled(true); }); }
            }, "runtime-install").start();
        });
        Ui.content(this,box);
    }
}
