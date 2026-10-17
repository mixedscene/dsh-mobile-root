package com.dshmobile.app;

/** Device evidence, not a claim that chroot or Android version compatibility passed. */
public final class RootCapabilities {
    public final boolean root;
    public final String details;
    public final String freezer = "signal-process-group-fallback (not cgroup freezer; escaped groups not covered)";

    RootCapabilities(boolean root, String details) {
        this.root = root;
        this.details = details;
    }

    @Override public String toString() {
        return details + "\nFreezer: " + freezer
                + "\nChroot, SELinux, DNS/TLS and 4/16KB native libraries require device smoke."
                + "\nUID 65534 is a shared nobody identity, not an application sandbox.";
    }
}
