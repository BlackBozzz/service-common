package com.argent.servicecommon.live.config;

import com.argent.servicecommon.live.ArgentLiveBroker;
import com.argent.servicecommon.live.ArgentLiveController;
import com.argent.servicecommon.live.ArgentLiveTicketInterceptor;
import com.argent.servicecommon.security.config.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;

/** Optional, secure, REST-backed WebSocket notifications for admin screens. */
@AutoConfiguration(after = SecurityAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = {"argent.security.enabled", "argent.live.enabled"},
        havingValue = "true", matchIfMissing = true)
@EnableWebSocket
@EnableScheduling
public class ArgentLiveAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ArgentLiveBroker.class)
    public ArgentLiveBroker argentLiveBroker() { return new ArgentLiveBroker(); }

    @Bean
    @ConditionalOnMissingBean(ArgentLiveController.class)
    public ArgentLiveController argentLiveController(ArgentLiveBroker broker) {
        return new ArgentLiveController(broker);
    }

    @Bean
    public WebSocketConfigurer argentLiveWebSocketConfigurer(
            ArgentLiveBroker broker, @Value("${argent.live.allowed-origins:}") String allowedOrigins) {
        return registry -> {
            var registration = registry.addHandler(broker, "/api/argent-live/ws")
                    .addInterceptors(new ArgentLiveTicketInterceptor(broker));
            // Default is secure same-origin. Cross-origin dev/proxy access is opt-in.
            if (!allowedOrigins.isBlank()) {
                registration.setAllowedOrigins(allowedOrigins.split("\\s*,\\s*"));
            }
        };
    }
}
