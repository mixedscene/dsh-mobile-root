package com.dshmobile.app;

import java.util.*;

/** Fixed local Git argv, with no shell interpolation, remote operations, hooks or global credentials. */
final class GitCommands {
    private GitCommands() {}
    static String workspace(String value) {
        if(value==null || !value.startsWith("/home/dsh/") || value.indexOf('\0')>=0 || value.indexOf('\n')>=0 || value.indexOf('\r')>=0)throw new IllegalArgumentException("Use an absolute workspace below /home/dsh");
        for(String part:value.split("/"))if(part.equals("..")||part.equals("."))throw new IllegalArgumentException("Dot path segments are not accepted");
        return value;
    }
    static String file(String value) {
        if(value==null || value.isEmpty() || value.startsWith("/") || value.indexOf('\0')>=0 || value.indexOf('\n')>=0 || value.indexOf('\r')>=0)throw new IllegalArgumentException("Enter one relative file path");
        for(String part:value.split("/"))if(part.equals("..")||part.equals("."))throw new IllegalArgumentException("Dot path segments are not accepted");
        return value;
    }
    static List<String> argv(String workspace,List<String> action) {
        List<String> args=new ArrayList<>(Arrays.asList("/usr/bin/env","-i","HOME=/home/dsh","PATH=/usr/local/bin:/usr/bin:/bin","LANG=C.UTF-8","GIT_CONFIG_NOSYSTEM=1","GIT_CONFIG_GLOBAL=/dev/null","GIT_TERMINAL_PROMPT=0","GIT_OPTIONAL_LOCKS=0","/usr/bin/timeout","--kill-after=3s","30s","/usr/local/bin/git","--no-lazy-fetch","--literal-pathspecs","-c","maintenance.auto=false","-c","gc.auto=0","-c","core.hooksPath=/dev/null","-c","core.fsmonitor=false","-c","credential.helper=","-c","commit.gpgSign=false","-c","tag.gpgSign=false","-c","core.pager=cat","-c","core.editor=false","-c","protocol.ext.allow=never","-c","protocol.file.allow=never","-C",workspace(workspace)));
        args.addAll(action);return args;
    }
}
