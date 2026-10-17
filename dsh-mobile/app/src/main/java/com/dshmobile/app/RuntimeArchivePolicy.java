package com.dshmobile.app;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;

/** Pure archive validation shared by the installer and its host-side unit tests. */
final class RuntimeArchivePolicy {
    private RuntimeArchivePolicy() {}
    static String name(String raw, boolean directory) throws IOException {
        String name=raw;
        while(name.startsWith("./")) name=name.substring(2);
        if(name.isEmpty()||name.equals(".")) {
            if(directory) return "";
            throw new IOException("Empty archive path");
        }
        if(name.startsWith("/")||name.contains("\\")||name.indexOf('\0')>=0
                ||Arrays.asList(name.split("/")).contains("..")) throw new IOException("Unsafe archive path");
        return name;
    }
    static void entry(boolean regular, boolean directory, boolean link, boolean sparse, long size, long total, long max) throws IOException {
        entry(regular,directory,false,link,sparse,size,total,max);
    }
    static void entry(boolean regular,boolean directory,boolean hardLink,boolean symbolicLink,boolean sparse,long size,long total,long max) throws IOException {
        if((!regular&&!directory&&!hardLink)||symbolicLink||sparse) throw new IOException("Archive symlinks/devices/sparse files are forbidden");
        if((hardLink||directory)&&size!=0) throw new IOException("Hard-link and directory entries must have no payload");
        if(size<0||size>max-total||total<0||total>max) throw new IOException("Expanded runtime exceeds limit");
    }
    static String hardLinkTarget(String raw,String destination,Set<String> seenRegular) throws IOException {
        String source=name(raw,false);
        for(String component:source.split("/",-1))
            if(component.isEmpty()||component.equals(".")) throw new IOException("Hard-link target must be a normal relative file path");
        if(source.equals(destination)||!seenRegular.contains(source)) throw new IOException("Hard link must refer to an earlier extracted regular file, never a directory/link/self/forward target");
        return source;
    }
    static void hash(String expected,String actual) throws IOException {
        if(!expected.matches("[0-9a-f]{64}")||!expected.equals(actual)) throw new IOException("Runtime archive hash mismatch");
    }
    static void manifest(byte[] bundled,byte[] installed) throws IOException {
        if(!Arrays.equals(bundled,installed)) throw new IOException("Installed runtime differs from this APK; explicit migration/recovery required");
    }
}
