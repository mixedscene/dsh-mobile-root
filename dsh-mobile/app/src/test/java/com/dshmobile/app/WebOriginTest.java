package com.dshmobile.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class WebOriginTest {
    @Test public void admitsOnlyTheExactRuntimeOrigin() {
        assertTrue(WebOrigin.allowed("http://127.0.0.1:3080/?token=temporary"));
        assertTrue(WebOrigin.allowed("http://127.0.0.1:3080/api/session"));
        for (String url : new String[]{"http://localhost:3080/", "https://127.0.0.1:3080/", "http://127.0.0.1:3081/", "http://127.0.0.1:3080.evil/", "http://127.0.0.1:3080@evil/", "http://user@127.0.0.1:3080/", "file:///data/private", "content://example", "javascript:alert(1)", "http://2130706433:3080/", "http://127.0.0.1/", "http://127.0.0.1:03080/", "not a uri"}) assertFalse(url,WebOrigin.allowed(url));
        assertFalse(WebOrigin.allowed(null));
    }
}
