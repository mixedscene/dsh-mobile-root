package com.dshmobile.app;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Explicit su-only runtime; no emulation, package installation or root fallback. */
public final class RootRuntime {
    private static final Map<String, Process> JOBS = new LinkedHashMap<>();
    private RootRuntime() {}
    public static File baseDir(Context c) { return new File(c.getFilesDir(), "mobile"); }
    public static File rootfsDir(Context c) { return new File(baseDir(c), "rootfs"); }
    public static File homeDir(Context c) { return new File(baseDir(c), "data/home"); }
    public static File tmpDir(Context c) { return new File(baseDir(c), "data/tmp"); }
    public static boolean installed(Context c) { return RuntimeInstaller.isInstalled(c); }

    static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    static Process supervisor(Context c, List<String> args) throws IOException {
        File binary = new File(c.getApplicationInfo().nativeLibraryDir, "libmobile_supervisor.so");
        if (!binary.isFile()) throw new IOException("ARM64 supervisor missing from APK nativeLibraryDir");
        StringBuilder command = new StringBuilder("exec ").append(quote(binary.getAbsolutePath()));
        for (String arg : args) command.append(' ').append(quote(arg));
        return new ProcessBuilder("su", "-c", command.toString()).redirectErrorStream(true).start();
    }

    static String checked(Context c, List<String> args, int seconds) throws IOException {
        Process process = supervisor(c, args);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] bytes = new byte[4096]; int count;
                while ((count = in.read(bytes)) >= 0) synchronized (output) {
                    if (output.size() < 65536) output.write(bytes, 0, Math.min(count, 65536-output.size()));
                }
            } catch (IOException error) { /* Exit status and bounded output own command failure. */ }
        }, "root-command-output");
        reader.setDaemon(true); reader.start();
        try {
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly(); throw new IOException("Root command timed out; outcome unknown");
            }
            reader.join(1000);
            String text; synchronized (output) { text = output.toString("UTF-8"); }
            if (process.exitValue() != 0) throw new IOException("Root command exit " + process.exitValue() + ": " + text);
            return text;
        } catch (InterruptedException error) {
            process.destroyForcibly(); Thread.currentThread().interrupt(); throw new IOException("Root command interrupted", error);
        }
    }

    public static RootCapabilities probe(Context c) throws IOException {
        return new RootCapabilities(true, checked(c, Arrays.asList("probe"), 45));
    }
    private static synchronized Process launch(Context c, List<String> command, File log) throws IOException {
        if (!installed(c)) throw new IOException("Verified runtime is not installed");
        JOBS.entrySet().removeIf(entry -> !entry.getValue().isAlive());
        if(JOBS.isEmpty()) checked(c,Arrays.asList("check-state",new File(baseDir(c),"state").getAbsolutePath()),10);
        String id = UUID.randomUUID().toString();
        List<String> args = new ArrayList<>(Arrays.asList("run", rootfsDir(c).getAbsolutePath(),
                new File(baseDir(c), "data").getAbsolutePath(), new File(baseDir(c), "state").getAbsolutePath(), id, "--"));
        args.addAll(command);
        Process process = supervisor(c, args); JOBS.put(id, process);
        if (log != null) {
            Thread pump = new Thread(() -> {
                try (InputStream in = process.getInputStream(); java.io.FileOutputStream out = new java.io.FileOutputStream(log, true)) {
                    byte[] bytes = new byte[4096]; int n; long total = 0;
                    while ((n=in.read(bytes))>=0) { if(total<1048576) out.write(bytes,0,(int)Math.min(n,1048576-total)); total+=n; }
                } catch (IOException error) { process.destroy(); }
            }, "runtime-log"); pump.setDaemon(true); pump.start();
        }
        return process;
    }
    public static Process exec(Context c, List<String> command, File log) throws IOException { return launch(c,command,log); }
    public static Process execPiped(Context c, List<String> command) throws IOException { return launch(c,command,null); }
    public static Process startWeb(Context c, int port) throws IOException {
        if(port<1024||port>65535) throw new IOException("Invalid Web port");
        return execPiped(c,Arrays.asList("/opt/dsh-mobile/start-mobile.sh",String.valueOf(port)));
    }
    private static synchronized void control(Context c, String action) throws IOException {
        IOException failure = null;
        for (Map.Entry<String,Process> job : JOBS.entrySet()) {
            if (!job.getValue().isAlive()) continue;
            try {
                checked(c, Arrays.asList("control", new File(baseDir(c),"state").getAbsolutePath(),job.getKey(),action), 10);
                if (action.equals("stop")) {
                    if(!job.getValue().waitFor(13,TimeUnit.SECONDS)) throw new IOException("Controller did not exit; cleanup not confirmed");
                    if(job.getValue().exitValue()==79) throw new IOException("Escaped descendants remain; cleanup UNKNOWN");
                }
            } catch (IOException error) { failure=error; }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
        }
        JOBS.entrySet().removeIf(entry -> !entry.getValue().isAlive());
        if(failure!=null) throw failure;
    }
    public static void stop(Context c) throws IOException {
        IOException first=null;
        try { control(c,"stop"); } catch(IOException error) {first=error;}
        if(installed(c)) {
            try { checked(c,Arrays.asList("stop-all",new File(baseDir(c),"state").getAbsolutePath()),18); }
            catch(IOException error) {if(first!=null) error.addSuppressed(first); throw error;}
        }
        if(first!=null) throw first;
    }
    /** Stops one registered command without stopping the Web runtime or other jobs. */
    public static synchronized void cancel(Context c,Process process) throws IOException {
        String id=null;
        for(Map.Entry<String,Process> job:JOBS.entrySet()) if(job.getValue()==process) id=job.getKey();
        if(id==null) throw new IOException("Command is not owned by this runtime instance");
        if(!process.isAlive()) {JOBS.remove(id);return;}
        checked(c,Arrays.asList("control",new File(baseDir(c),"state").getAbsolutePath(),id,"stop"),10);
        try {
            if(!process.waitFor(13,TimeUnit.SECONDS)||process.exitValue()==79) throw new IOException("Command cleanup not confirmed");
        } catch(InterruptedException error) {Thread.currentThread().interrupt();throw new IOException(error);}
        JOBS.remove(id);
    }
    /** Requests signal-group suspension, without claiming cgroup freezer acknowledgement. */
    public static void freeze(Context c) throws IOException { control(c,"freeze"); }
    public static void thaw(Context c) throws IOException { control(c,"thaw"); }
}
