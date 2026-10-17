package com.dshmobile.app;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Bounded display of fixed helper diagnostics; not an accept-list for arbitrary process output. */
final class DiagnosticsText {
    private static final Pattern USERINFO=Pattern.compile("(?i)(https?://)[^\\s/]+@");
    private static final Pattern VALUE=Pattern.compile("(?i)((?:[a-z0-9_-]*token|(?:api[ _-]?)?key|secret|password|authorization)\\b[\\\"']?\\s*[:=]\\s*[\\\"']?)(?:Bearer\\s+)?[^\\s&?#,;\\\"'}]+");
    private static final Pattern LABELED=Pattern.compile("(?i)((?:token|api[ _-]?key|secret|password)\\s+)[^\\s,;]+");
    private static final Pattern EMAIL=Pattern.compile("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
    private DiagnosticsText(){}
    static String error(Throwable error){String message=error.getMessage();return sanitize(error.getClass().getSimpleName()+": "+(message==null?"No additional diagnostic":message),1024);}
    static String report(String message){return sanitize(message,16384);}
    static String sanitize(String message,int limit){
        String value=message==null?"":message.substring(0,Math.min(message.length(),65536));
        value=USERINFO.matcher(value).replaceAll("$1[REDACTED]@");
        value=VALUE.matcher(value).replaceAll("$1[REDACTED]");
        value=LABELED.matcher(value).replaceAll("$1[REDACTED]");
        value=EMAIL.matcher(value).replaceAll("[REDACTED-EMAIL]");
        if(value.getBytes(StandardCharsets.UTF_8).length<=limit && (message==null||message.length()<=65536))return value;
        String marker="\n[truncated]";int budget=Math.max(0,limit-marker.length()),used=0;
        StringBuilder out=new StringBuilder();
        for(int offset=0;offset<value.length();){int cp=value.codePointAt(offset);String symbol=new String(Character.toChars(cp));int bytes=symbol.getBytes(StandardCharsets.UTF_8).length;if(used+bytes>budget)break;out.append(symbol);used+=bytes;offset+=Character.charCount(cp);}
        return out.append(marker).toString();
    }
}
