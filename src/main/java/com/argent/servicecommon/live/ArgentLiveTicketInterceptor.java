package com.argent.servicecommon.live;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.Set;

/** JWT is used only on the protected ticket REST request; never put JWT in WebSocket URL. */
public class ArgentLiveTicketInterceptor implements HandshakeInterceptor {
    private final ArgentLiveBroker broker;

    public ArgentLiveTicketInterceptor(ArgentLiveBroker broker) { this.broker = broker; }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String ticket = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("ticket");
        Set<String> scopes = broker.consumeTicket(ticket);
        if (scopes.isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(ArgentLiveBroker.SCOPES_ATTRIBUTE, scopes);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) { }
}
