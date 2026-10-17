package com.dshmobile.app;

import java.io.IOException;
import java.util.Arrays;

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
        if((!regular&&!directory)||link||sparse) throw new IOException("Archive links/devices/sparse files are forbidden");
        if(size<0||size>max-total||total<0||total>max) throw new IOException("Expanded runtime exceeds limit");
    }
    static void hash(String expected,String actual) throws IOException {
        if(!expected.matches("[0-9a-f]{64}")||!expected.equals(actual)) throw new IOException("Runtime archive hash mismatch");
    }
}
