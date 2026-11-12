package com.argent.servicecommon.live;

import com.argent.servicecommon.security.CurrentUser;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Issue a WebSocket handshake ticket to JWT-authenticated users with relevant permissions. */
@RestController
@RequestMapping("/api/argent-live")
public class ArgentLiveController {
    private final ArgentLiveBroker broker;

    public ArgentLiveController(ArgentLiveBroker broker) { this.broker = broker; }

    @GetMapping("/ticket")
    @PreAuthorize("@argentAccess.action('queue.read') or @argentAccess.action('admin')")
    public ResponseEntity<Map<String, String>> ticket() {
        Set<String> scopes = new HashSet<>();
        if (CurrentUser.hasAction("queue.read")) scopes.add(ArgentLiveBroker.QUEUES);
        if (CurrentUser.hasAction("admin")) scopes.add(ArgentLiveBroker.AUDIT);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("ticket", broker.issueTicket(scopes)));
    }
}
