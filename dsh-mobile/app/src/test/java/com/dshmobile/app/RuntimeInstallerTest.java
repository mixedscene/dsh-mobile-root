package com.dshmobile.app;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** Exercises the same pure validation used before each extracted file is opened. */
public final class RuntimeInstallerTest {
    @Test public void installedManifestMustMatchBundledBuildExactly() throws Exception {
        byte[] original="build-id-original sha256-original".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        RuntimeArchivePolicy.manifest(original,original.clone());
        assertThrows(IOException.class,()->RuntimeArchivePolicy.manifest(original,"build-id-new sha256-new".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    @Test public void acceptsRuntimePaths() throws Exception {
        assertEquals("opt/node/bin/node",RuntimeArchivePolicy.name("./opt/node/bin/node",false));
        assertEquals("",RuntimeArchivePolicy.name("./",true));
        RuntimeArchivePolicy.entry(true,false,false,false,10,20,30);
    }
    @Test public void rejectsTraversalAndAbsolutePaths() {
        for(String name:new String[]{"../outside","bin/../../outside","/etc/passwd","bin\\outside","bad\0name"})
            assertThrows(IOException.class,()->RuntimeArchivePolicy.name(name,false));
    }
    @Test public void rejectsLinksDevicesAndSparseFiles() {
        assertThrows(IOException.class,()->RuntimeArchivePolicy.entry(true,false,true,false,0,0,100));
        assertThrows(IOException.class,()->RuntimeArchivePolicy.entry(false,false,false,false,0,0,100));
        assertThrows(IOException.class,()->RuntimeArchivePolicy.entry(true,false,false,true,0,0,100));
    }
    @Test public void boundsExpansionWithoutOverflow() {
        assertThrows(IOException.class,()->RuntimeArchivePolicy.entry(true,false,false,false,11,90,100));
        assertThrows(IOException.class,()->RuntimeArchivePolicy.entry(true,false,false,false,Long.MAX_VALUE,1,100));
        assertThrows(IOException.class,()->RuntimeArchivePolicy.entry(true,false,false,false,-1,0,100));
    }
    @Test public void rejectsWrongAndMalformedHashes() throws Exception {
        String a="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String b="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        RuntimeArchivePolicy.hash(a,a);
        assertThrows(IOException.class,()->RuntimeArchivePolicy.hash(a,b));
        assertThrows(IOException.class,()->RuntimeArchivePolicy.hash("latest",a));
    }
}
