package com.argent.servicecommon.live;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArgentLiveBrokerTest {
    @Test
    void ticketsAreSingleUseAndScopeBound() {
        ArgentLiveBroker broker = new ArgentLiveBroker();
        String ticket = broker.issueTicket(Set.of(ArgentLiveBroker.QUEUES));
        assertEquals(Set.of(ArgentLiveBroker.QUEUES), broker.consumeTicket(ticket));
        assertTrue(broker.consumeTicket(ticket).isEmpty(), "Ticket replay must be rejected");
        assertTrue(broker.consumeTicket("not-a-ticket").isEmpty());
    }
}
