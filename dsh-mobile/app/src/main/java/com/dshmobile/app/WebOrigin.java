package com.dshmobile.app;

import java.net.URI;

/** Exact origin admitted by the embedded browser; never a prefix or hostname suffix match. */
final class WebOrigin {
    private WebOrigin() {}
    static boolean allowed(String value) {
        if(value==null)return false;
        try {
            URI uri = URI.create(value);
            return "http".equals(uri.getScheme()) && "127.0.0.1:3080".equals(uri.getRawAuthority()) && uri.getUserInfo()==null;
        } catch(IllegalArgumentException e) { return false; }
    }
}
