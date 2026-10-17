package com.dshmobile.app;

import android.app.*;
import android.content.*;
import android.os.*;
import java.lang.Process;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Owns one explicit runtime generation. Unknown task activity never implies idle. */
public final class HarnessService extends Service {
    public static final String STOP = "com.dshmobile.app.STOP";
    private static final String CHANNEL = "runtime";
    private final ExecutorService control = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Runnable> listeners = new HashSet<>();
    private final ArrayDeque<String> logs = new ArrayDeque<>();
    private volatile String state = "Stopped", launchUrl;
    private volatile boolean starting, stopping;
    private volatile boolean destroyed;
    private volatile Process process;
    public final class LocalBinder extends Binder { public HarnessService service() { return HarnessService.this; } }
    @Override public IBinder onBind(Intent intent) { return new LocalBinder(); }
    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL, "Runtime", NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        startForeground(1, notification());
        if (intent != null && STOP.equals(intent.getAction())) stopRuntime(); else startRuntime();
        return START_NOT_STICKY;
    }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, HarnessService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("DSH Root")
            .setContentText(state).setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null, "Stop runtime", stop).build()).build();
    }
    public String state() { return state; }
    public String launchUrl() { return launchUrl; }
    public synchronized String diagnostics() { return String.join("\n", logs); }
    public void observe(Runnable listener) { listeners.add(listener); listener.run(); }
    public void removeObserver(Runnable listener) { listeners.remove(listener); }
    private void publish(String value) {
        state = value;
        synchronized (this) { if (logs.size() == 120) logs.removeFirst(); logs.addLast(new Date() + " " + value); }
        main.post(() -> {
            if(destroyed) return;
            getSystemService(NotificationManager.class).notify(1, notification());
            for (Runnable r : new ArrayList<>(listeners)) r.run();
        });
    }
    private void startRuntime() {
        if (starting || stopping || process != null) return;
        starting = true;
        publish("Starting root runtime");
        control.execute(() -> {
            try {
                if (!RootRuntime.installed(this)) throw new IOException("Runtime not installed");
                process = RootRuntime.startWeb(this, 3080);
                publish("Running; task activity unknown; automatic freeze disabled");
                Process owned = process;
                Thread reader = new Thread(() -> readOutput(owned), "runtime-output");
                reader.start();
                new Thread(() -> {
                    try { int code = owned.waitFor(); main.post(() -> {
                        if (process == owned) { process = null; launchUrl = null; if(!stopping&&!destroyed) publish("Runtime exited: " + code + ". Restart is manual."); }
                    }); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }, "runtime-exit").start();
            } catch (Exception e) { publish("Start failed. Check root authorization and installed runtime; no automatic retry."); }
            finally { starting = false; }
        });
    }
    private void readOutput(Process owned) {
        Pattern url = Pattern.compile("dsh web: (http://127\\.0\\.0\\.1:[0-9]+/\\?token=[^\\s]+)");
        try (BufferedReader in = new BufferedReader(new InputStreamReader(owned.getInputStream()))) {
            StringBuilder line = new StringBuilder(); boolean oversized = false; int ch;
            while ((ch = in.read()) != -1) {
                if (ch == 10) {
                    if (!oversized) { Matcher match = url.matcher(line); if (match.find()) {
                        String candidate=match.group(1);
                        main.post(() -> {
                            if(process==owned&&!stopping&&!destroyed) {
                                launchUrl=candidate; publish("Web UI ready. Task activity unknown; automatic freeze disabled");
                            }
                        });
                    } }
                    line.setLength(0); oversized = false;
                } else if (line.length() < 16384) line.append((char)ch); else oversized = true;
                // Never log process output: it can contain user content and credentials.
            }
        } catch (IOException e) {main.post(() -> {if(process==owned&&!stopping&&!destroyed) publish("Runtime output stream closed");});}
    }
    public void stopRuntime() {
        if (stopping) return;
        stopping = true;
        control.execute(() -> {
            try { RootRuntime.stop(this); launchUrl = null; process = null; publish("Stopped"); main.post(() -> { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }); }
            catch (IOException e) { publish("Stop failed; cleanup not confirmed. Retry Stop explicitly."); }
            finally { stopping = false; }
        });
    }
    @Override public void onDestroy() {
        destroyed=true;
        listeners.clear();
        control.execute(() -> { try { RootRuntime.stop(this); } catch (IOException e) { /* Service death cannot confirm cleanup. */ } });
        control.shutdown();
        super.onDestroy();
    }
}
