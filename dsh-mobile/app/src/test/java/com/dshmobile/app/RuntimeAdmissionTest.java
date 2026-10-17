package com.dshmobile.app;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.assertThrows;

public final class RuntimeAdmissionTest {
    @Test public void failedStopKeepsAdmissionClosedUntilExplicitConfirmedStop() throws Exception {
        RuntimeAdmission gate=new RuntimeAdmission();
        gate.beginStop(); gate.endStop(false);
        assertThrows(IOException.class,gate::launchTicket);
        gate.beginStop(); gate.endStop(true);
        gate.validate(gate.launchTicket());
    }
    @Test public void rejectsLaunchDuringStop() throws Exception {
        RuntimeAdmission gate=new RuntimeAdmission();
        gate.beginStop();
        assertThrows(IOException.class,gate::launchTicket);
        gate.endStop();
        gate.validate(gate.launchTicket());
    }
    @Test public void rejectsLaunchTicketThatWaitedAcrossStop() throws Exception {
        RuntimeAdmission gate=new RuntimeAdmission();
        long stale=gate.launchTicket();
        gate.beginStop(); gate.endStop();
        assertThrows(IOException.class,()->gate.validate(stale));
        gate.validate(gate.launchTicket());
    }
    @Test public void concurrentStopsKeepAdmissionClosedUntilBothFinish() throws Exception {
        RuntimeAdmission gate=new RuntimeAdmission();
        gate.beginStop(); gate.beginStop(); gate.endStop();
        assertThrows(IOException.class,gate::launchTicket);
        gate.endStop(); gate.validate(gate.launchTicket());
    }
}
