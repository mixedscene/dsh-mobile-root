package com.dshmobile.app;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RuntimeDnsPolicyTest {
    @Test public void emitsNumericNameserversAndDeduplicates() throws Exception {
        InetAddress dns=InetAddress.getByAddress(new byte[]{1,1,1,1});
        assertEquals("nameserver 1.1.1.1\n",RuntimeDnsPolicy.resolver(Arrays.asList(dns,dns)));
    }
    @Test public void rejectsUnavailableAndInvalidAddresses() throws Exception {
        assertThrows(IOException.class,()->RuntimeDnsPolicy.resolver(null));
        assertThrows(IOException.class,()->RuntimeDnsPolicy.resolver(Collections.singletonList(InetAddress.getByAddress(new byte[]{0,0,0,0}))));
        assertThrows(IOException.class,()->RuntimeDnsPolicy.resolver(Collections.singletonList(InetAddress.getByAddress(new byte[]{(byte)224,0,0,1}))));
    }
    @Test public void emptyDnsAllowsOfflineRuntimeWithoutInventingAnUpstream() throws Exception {
        String offline=RuntimeDnsPolicy.resolver(Collections.emptyList());
        assertEquals(RuntimeDnsPolicy.OFFLINE_RESOLVER,offline);
        assertTrue(offline.startsWith("# Offline/no DNS"));
        assertTrue(offline.contains("options timeout:1 attempts:1\n"));
        assertFalse(offline.contains("nameserver "));
    }
    @Test public void preservesNumericIpv6Scope() throws Exception {
        byte[] address=new byte[16];address[0]=(byte)0xfe;address[1]=(byte)0x80;address[15]=1;
        Inet6Address dns=Inet6Address.getByAddress(null,address,7);
        assertTrue(RuntimeDnsPolicy.resolver(Collections.singletonList(dns)).endsWith("%7\n"));
        assertThrows(IOException.class,()->RuntimeDnsPolicy.resolver(Collections.singletonList(Inet6Address.getByAddress(null,address,0))));
    }
}
