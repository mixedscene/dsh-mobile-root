package com.dshmobile.app;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/** Rejects launches that arrive during, or wait across, a stop operation. */
final class RuntimeAdmission {
    private final AtomicInteger stopping = new AtomicInteger();
    private final AtomicLong epoch = new AtomicLong();
    private final AtomicBoolean recoveryRequired = new AtomicBoolean();
    long launchTicket() throws IOException {
        long ticket=epoch.get();
        validate(ticket);
        return ticket;
    }
    void validate(long ticket) throws IOException {
        if(stopping.get()!=0||ticket!=epoch.get()||recoveryRequired.get()) throw new IOException("Runtime stopping or cleanup unconfirmed; explicitly Stop/recover before retrying");
    }
    void beginStop() { stopping.incrementAndGet(); epoch.incrementAndGet(); }
    void endStop() {endStop(true);}
    void endStop(boolean confirmed) {recoveryRequired.set(!confirmed);stopping.decrementAndGet();}
}
