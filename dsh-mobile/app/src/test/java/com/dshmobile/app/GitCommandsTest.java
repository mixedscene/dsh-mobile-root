package com.dshmobile.app;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public final class GitCommandsTest {
    @Test public void keepsShellMetacharactersInsideOneArgvValue() {
        String path="file; $(id) 'quote'.txt";
        List<String> args=GitCommands.argv("/home/dsh/project space",Arrays.asList("add","--",GitCommands.file(path)));
        assertEquals(path,args.get(args.size()-1));
        assertTrue(args.contains("--literal-pathspecs"));assertTrue(args.contains("--no-lazy-fetch"));
        assertTrue(args.contains("core.hooksPath=/dev/null"));assertTrue(args.contains("GIT_CONFIG_GLOBAL=/dev/null"));
        assertTrue(args.contains("GIT_TERMINAL_PROMPT=0"));assertTrue(args.contains("maintenance.auto=false"));
        assertFalse(args.contains("sh"));assertFalse(args.contains("-c "));
    }
    @Test public void rejectsPathsOutsideTheUserSelectedWorkspaceScope() {
        for(String value:new String[]{"/etc","/home/dsh/../root","relative","/home/dsh/project\nother"})assertThrows(IllegalArgumentException.class,()->GitCommands.workspace(value));
        for(String value:new String[]{"","/absolute","../outside","sub/../../outside","a\nother"})assertThrows(IllegalArgumentException.class,()->GitCommands.file(value));
        assertEquals(":(glob)*",GitCommands.file(":(glob)*"));
    }
}
