package com.dshmobile.app;

import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public final class DiagnosticsTextTest {
    @Test public void redactsCredentialLabelsUserinfoAndEmail(){
        String source="token=first api_key:second access_token=third&other=ok Authorization: Bearer fourth https://alice:secret@example.com/file owner@example.net";
        String result=DiagnosticsText.error(new IOException(source));
        for(String secret:new String[]{"first","second","third","fourth","alice","secret@","owner@example.net"})assertFalse(secret,result.contains(secret));
        assertTrue(result.contains("[REDACTED]"));assertTrue(result.contains("other=ok"));assertTrue(result.contains("example.com/file"));
    }
    @Test public void boundsMessagesByUtf8BytesWithoutSplittingCodePoints(){
        String result=DiagnosticsText.error(new IOException("设备🙂".repeat(2000)));
        assertTrue(result.getBytes(StandardCharsets.UTF_8).length<=1024);
        assertTrue(result.endsWith("[truncated]"));assertFalse(result.contains("\ufffd"));
        assertEquals(result,new String(result.getBytes(StandardCharsets.UTF_8),StandardCharsets.UTF_8));
    }
    @Test public void retainsActionableFixedHelperErrors(){
        String result=DiagnosticsText.error(new IOException("DSH_ROOT_ERROR mount failed; runtime-arm64.tar.xz missing"));
        assertTrue(result.contains("mount failed"));assertTrue(result.contains("runtime-arm64.tar.xz missing"));
        assertTrue(DiagnosticsText.error(new IOException()).contains("No additional diagnostic"));
    }
}
