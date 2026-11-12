package com.argent.servicecommon.live;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Authenticated, one-way admin notifications. Browser receives only an invalidation type,
 * and re-reads protected REST endpoints; no queue payloads or audit values are broadcast.
 */
public class ArgentLiveBroker extends TextWebSocketHandler implements ArgentLivePublisher {
    private static final Logger log = LoggerFactory.getLogger(ArgentLiveBroker.class);
    static final String SCOPES_ATTRIBUTE = "argent.live.scopes";
    public static final String QUEUES = "queues";
    public static final String AUDIT = "audit";
    private static final long TICKET_LIFETIME_SECONDS = 20;
    private static final int MAX_TICKETS = 2000;
    private static final long SESSION_LIFETIME_SECONDS = 15 * 60;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final Map<String, Subscriber> connections = new ConcurrentHashMap<>();
    private final AtomicBoolean queueDirty = new AtomicBoolean();
    private final AtomicBoolean auditDirty = new AtomicBoolean();

    /** A single-use, short-lived capability scoped to the current user's REST permissions. */
    public String issueTicket(Set<String> scopes) {
        if (scopes.isEmpty()) throw new IllegalArgumentException("Нет доступных подписок");
        Instant now = Instant.now();
        tickets.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        if (tickets.size() >= MAX_TICKETS) {
            throw new IllegalStateException("Слишком много ожидающих WebSocket-подключений");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tickets.put(value, new Ticket(Set.copyOf(scopes), now.plusSeconds(TICKET_LIFETIME_SECONDS)));
        return value;
    }

    /** Consumed before the HTTP -> WebSocket upgrade. A ticket cannot be replayed. */
    public Set<String> consumeTicket(String value) {
        if (value == null || value.length() != 43) return Set.of();
        Ticket ticket = tickets.remove(value);
        if (ticket == null || !ticket.expiresAt().isAfter(Instant.now())) return Set.of();
        return ticket.scopes();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Object attribute = session.getAttributes().get(SCOPES_ATTRIBUTE);
        if (!(attribute instanceof Set<?> scopes) || scopes.isEmpty()) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        @SuppressWarnings("unchecked")
        Set<String> authorized = (Set<String>) scopes;
        connections.put(session.getId(), new Subscriber(
                new ConcurrentWebSocketSessionDecorator(session, 10_000, 64 * 1024), authorized,
                Instant.now().plusSeconds(SESSION_LIFETIME_SECONDS)));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        connections.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        connections.remove(session.getId());
        try { session.close(); } catch (Exception ignored) { /* already disconnected */ }
    }

    @Override
    public void queueChanged() { queueDirty.set(true); }

    @Override
    public void auditChanged() { auditDirty.set(true); }

    /** Collapse many short-lived tasks into at most one refresh notification per flush interval. */
    @Scheduled(fixedDelayString = "${argent.live.flush-ms:500}")
    public void flush() {
        // Reauthentication is required periodically, so revoked/expired permissions do not linger.
        Instant now = Instant.now();
        for (var entry : connections.entrySet()) {
            Subscriber subscriber = entry.getValue();
            if (now.isAfter(subscriber.expiresAt())) {
                connections.remove(entry.getKey(), subscriber);
                try { subscriber.session().close(CloseStatus.NORMAL); }
                catch (Exception ignored) { /* client is gone */ }
            }
        }
        if (queueDirty.getAndSet(false)) broadcast(QUEUES, "{\"type\":\"queues.changed\"}");
        if (auditDirty.getAndSet(false)) broadcast(AUDIT, "{\"type\":\"audit.changed\"}");
    }

    private void broadcast(String scope, String payload) {
        for (var entry : connections.entrySet()) {
            Subscriber subscriber = entry.getValue();
            if (!subscriber.scopes().contains(scope)) continue;
            try {
                if (subscriber.session().isOpen()) {
                    subscriber.session().sendMessage(new TextMessage(payload));
                } else {
                    connections.remove(entry.getKey(), subscriber);
                }
            } catch (Exception ex) {
                connections.remove(entry.getKey(), subscriber);
                log.debug("Admin live connection closed: {}", entry.getKey(), ex);
                try { subscriber.session().close(); } catch (Exception ignored) { /* disconnected */ }
            }
        }
    }

    private record Ticket(Set<String> scopes, Instant expiresAt) { }
    private record Subscriber(WebSocketSession session, Set<String> scopes, Instant expiresAt) { }
}
