package com.dshmobile.app;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.json.JSONObject;

/** Installs hash-pinned regular files/directories and confined backward hard links atomically. */
public final class RuntimeInstaller {
    public interface Progress { void onProgress(String message); }
    private static final long MAX_EXPANDED = 4L * 1024 * 1024 * 1024;
    private static final int MAX_ENTRIES = 500000;
    private RuntimeInstaller() {}

    public static boolean isInstalled(Context c) {
        File root = RootRuntime.rootfsDir(c);
        boolean files = new File(root,".runtime-verified.json").isFile()
                && new File(root,"opt/dsh-mobile/start-mobile.sh").isFile()
                && new File(root,"usr/local/bin/node").isFile();
        if(!files) return false;
        try(InputStream in=new FileInputStream(new File(root,".runtime-verified.json"))) {
            RuntimeArchivePolicy.manifest(manifest(c),readManifest(in));
            return true;
        } catch(IOException error) {return false;}
    }
    private static byte[] manifest(Context c) throws IOException {
        try (InputStream in=c.getAssets().open("runtime/runtime-manifest.json")) {return readManifest(in);}
    }
    private static byte[] readManifest(InputStream in) throws IOException {
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] bytes=new byte[4096]; int n;
            while((n=in.read(bytes))>=0) {
                if(out.size()+n>65536) throw new IOException("Runtime manifest exceeds limit");
                out.write(bytes,0,n);
            }
            return out.toByteArray();
        }
    }
    private static void cancelled() throws IOException {
        if(Thread.currentThread().isInterrupted()) throw new IOException("Runtime installation cancelled before commit");
    }
    private static void mkdir(File dir) throws IOException {
        if(!dir.isDirectory()&&!dir.mkdirs()) throw new IOException("Cannot create " + dir.getName());
    }
    private static void remove(File file) throws IOException {
        if(!file.exists()) return;
        if(file.isDirectory()) {
            File[] children=file.listFiles();
            if(children==null) throw new IOException("Cannot inspect staging directory");
            for(File child:children) remove(child);
        }
        if(!file.delete()) throw new IOException("Cannot remove incomplete runtime staging");
    }

    public static synchronized void install(Context c, Progress progress) throws IOException {
        if(isInstalled(c)) { progress.onProgress("Verified runtime already installed"); return; }
        byte[] manifestBytes=manifest(c);
        final String hash;
        try {
            JSONObject json=new JSONObject(new String(manifestBytes,StandardCharsets.UTF_8));
            if(json.getInt("format")!=1) throw new IOException("Unsupported runtime manifest format");
            hash=json.getString("sha256");
            if(!hash.matches("[0-9a-f]{64}")) throw new IOException("Invalid runtime hash");
            if(!json.getString("buildId").matches("[A-Za-z0-9._-]{1,128}")) throw new IOException("Invalid runtime build ID");
        } catch(org.json.JSONException error) { throw new IOException("Invalid runtime manifest",error); }
        RootRuntime.probe(c);
        File base=RootRuntime.baseDir(c); mkdir(base);
        if(RootRuntime.rootfsDir(c).exists()) throw new IOException("Incomplete or older runtime exists; explicit recovery required, not overwritten");
        progress.onProgress("Verifying bundled ARM64 runtime SHA-256");
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream in=c.getAssets().open("runtime/runtime.tar.gz")) {
                byte[] bytes=new byte[65536]; int n;
                while((n=in.read(bytes))>=0) { cancelled(); digest.update(bytes,0,n); }
            }
            StringBuilder actual=new StringBuilder();
            for(byte value:digest.digest()) actual.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
            RuntimeArchivePolicy.hash(hash,actual.toString());
        } catch(NoSuchAlgorithmException error) { throw new IOException(error); }
        File stage=new File(base,"staging-"+UUID.randomUUID()); mkdir(stage);
        boolean committed=false;
        try {
            progress.onProgress("Extracting verified runtime (no scripts or network installs)");
            Set<String> seen=new HashSet<>(),seenRegular=new HashSet<>(); long total=0; int entries=0;
            String prefix=stage.getCanonicalPath()+File.separator;
            try(TarArchiveInputStream tar=new TarArchiveInputStream(new GZIPInputStream(c.getAssets().open("runtime/runtime.tar.gz")))) {
                TarArchiveEntry entry;
                while((entry=tar.getNextTarEntry())!=null) {
                    cancelled();
                    if(++entries>MAX_ENTRIES) throw new IOException("Too many runtime entries");
                    String name=RuntimeArchivePolicy.name(entry.getName(),entry.isDirectory());
                    if(name.isEmpty()) continue;
                    boolean regular=entry.isFile()&&!entry.isLink()&&!entry.isSymbolicLink()
                            &&!entry.isBlockDevice()&&!entry.isCharacterDevice()&&!entry.isFIFO();
                    RuntimeArchivePolicy.entry(regular,entry.isDirectory(),entry.isLink(),entry.isSymbolicLink(),
                            entry.isSparse(),entry.getSize(),total,MAX_EXPANDED);
                    File target=new File(stage,name);
                    if(!target.getCanonicalPath().startsWith(prefix)) throw new IOException("Archive path escapes staging");
                    if(!seen.add(target.getCanonicalPath())) throw new IOException("Duplicate runtime entry");
                    String relative=target.getCanonicalPath().substring(prefix.length());
                    if(relative.equals(".runtime-verified.json")) throw new IOException("Archive uses a reserved installer path");
                    if(entry.isDirectory()) { mkdir(target); continue; }
                    if(entry.isLink()) {
                        String link=RuntimeArchivePolicy.hardLinkTarget(entry.getLinkName(),relative,seenRegular);
                        File source=new File(stage,link);
                        if(!source.getCanonicalPath().startsWith(prefix)) throw new IOException("Hard-link source escapes staging");
                        mkdir(target.getParentFile());
                        try {
                            if(!OsConstants.S_ISREG(Os.lstat(source.getAbsolutePath()).st_mode)) throw new IOException("Hard-link source is not a regular file");
                            Os.link(source.getAbsolutePath(),target.getAbsolutePath());
                        } catch(android.system.ErrnoException error) {throw new IOException("Cannot create confined backward hard link",error);}
                        // Link metadata must not chmod/chown the already extracted shared inode.
                        continue;
                    }
                    if(base.getUsableSpace()<entry.getSize()+64L*1024*1024) throw new IOException("Insufficient runtime storage");
                    mkdir(target.getParentFile());
                    if(!target.createNewFile()) throw new IOException("Duplicate runtime file");
                    try(FileOutputStream out=new FileOutputStream(target)) {
                        byte[] bytes=new byte[65536]; int n; long written=0;
                        while((n=tar.read(bytes))>=0) { cancelled(); written+=n; out.write(bytes,0,n); }
                        if(written!=entry.getSize()) throw new IOException("Truncated runtime entry");
                        out.getFD().sync(); total+=written;
                    }
                    try { Os.chmod(target.getAbsolutePath(),(entry.getMode()&0111)!=0?0755:0644); }
                    catch(android.system.ErrnoException error) { throw new IOException("Cannot set runtime mode",error); }
                    seenRegular.add(relative);
                }
            }
            for(String required:Arrays.asList("usr/local/bin/node","opt/dsh-mobile/start-mobile.sh","bin/bash","usr/local/bin/git","usr/bin/rg","etc/resolv.conf"))
                if(!new File(stage,required).isFile()) throw new IOException("Runtime missing "+required);
            for(String dir:Arrays.asList("home/dsh","tmp","proc","dev/pts")) mkdir(new File(stage,dir));
            for(String device:Arrays.asList("null","zero","random","urandom","tty","ptmx")) {
                File file=new File(stage,"dev/"+device);
                if(!file.exists()&&!file.createNewFile()) throw new IOException("Cannot create device mountpoint");
                if(!file.isFile()) throw new IOException("Invalid device mountpoint");
            }
            try(FileOutputStream out=new FileOutputStream(new File(stage,".runtime-verified.json"))) {
                out.write(manifestBytes); out.getFD().sync();
            }
            cancelled(); progress.onProgress("Committing root-owned runtime and isolated data identity");
            RootRuntime.checked(c,Arrays.asList("install",stage.getAbsolutePath(),base.getAbsolutePath()),300);
            committed=true;
            if(!isInstalled(c)) throw new IOException("Runtime commit could not be verified");
            progress.onProgress("Runtime installed; chroot/device compatibility awaits first launch");
        } catch(IOException | RuntimeException error) {
            if(!committed&&stage.exists()) {
                try { remove(stage); }
                catch(IOException cleanup) {
                    error.addSuppressed(new IOException("Incomplete staging retained; root ownership may require explicit recovery: "+stage.getName(),cleanup));
                }
            }
            throw error;
        }
    }
}
