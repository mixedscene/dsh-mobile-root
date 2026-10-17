package com.dshmobile.app;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Produces only numeric nameserver directives from Android's active-network addresses. */
final class RuntimeDnsPolicy {
    private RuntimeDnsPolicy() {}
    static String resolver(List<InetAddress> addresses) throws IOException {
        if(addresses==null||addresses.isEmpty()) throw new IOException("Active network has no DNS servers; reconnect and restart runtime");
        Set<String> servers=new LinkedHashSet<>();
        for(InetAddress address:addresses) {
            if(address==null||address.isAnyLocalAddress()||address.isMulticastAddress()) throw new IOException("Active network returned an invalid DNS address");
            String numeric=InetAddress.getByAddress(address.getAddress()).getHostAddress();
            if(address instanceof Inet6Address) {
                int scope=((Inet6Address)address).getScopeId();
                if(address.isLinkLocalAddress()&&scope==0) throw new IOException("Link-local DNS has no interface scope");
                if(scope>0) numeric += "%"+scope;
            }
            if(!numeric.matches("[0-9a-fA-F:.%]+")) throw new IOException("DNS address is not numeric");
            servers.add(numeric);
            if(servers.size()==3) break;
        }
        StringBuilder out=new StringBuilder();
        for(String server:servers) out.append("nameserver ").append(server).append('\n');
        return out.toString();
    }
}
