package com.argent.servicecommon.security;

import com.argent.servicecommon.auth.service.TokenService;
import com.argent.servicecommon.security.grants.GrantOp;
import com.argent.servicecommon.security.grants.GrantsAuthorities;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Доступ к текущему пользователю запроса через {@link SecurityContextHolder}.
 *
 * <p>Имя аутентификации — идентификатор пользователя, authorities — права из грантов ролей:
 * {@code table:<t>:read|write} и {@code action:<a>}.</p>
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** Идентификатор вошедшего пользователя или пустое значение для анонимного запроса. */
    public static Optional<UUID> id() {
        return authentication().flatMap(authentication -> {
            try {
                return Optional.of(UUID.fromString(authentication.getName()));
            } catch (IllegalArgumentException exception) {
                return Optional.empty();
            }
        });
    }

    /**
     * Идентификатор сессии из claim {@code sessionId} токена текущего запроса или пустое значение,
     * если запрос анонимный или аутентифицирован не JWT.
     */
    public static Optional<UUID> sessionId() {
        return authentication()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(authentication -> ((JwtAuthenticationToken) authentication).getToken()
                        .getClaimAsString(TokenService.SESSION_ID_CLAIM))
                .flatMap(value -> {
                    try {
                        return Optional.of(UUID.fromString(value));
                    } catch (IllegalArgumentException exception) {
                        return Optional.empty();
                    }
                });
    }

    /**
     * Логин из claim {@code login} токена текущего запроса или пустое значение, если запрос
     * анонимный или аутентифицирован не JWT.
     */
    public static Optional<String> login() {
        return authentication()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(authentication -> ((JwtAuthenticationToken) authentication).getToken()
                        .getClaimAsString(TokenService.LOGIN_CLAIM));
    }

    /**
     * Authorities вошедшего пользователя ({@code table:<t>:<op>}, {@code action:<a>}); для анонимного
     * запроса — пустое множество. Служебные authorities Spring Security о факторах аутентификации
     * не включаются.
     */
    public static Set<String> authorities() {
        return grantedAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Разрешена ли текущему пользователю операция над таблицей с учётом ключа {@code *}.
     *
     * @param op {@code read} или {@code write} без учёта регистра; неизвестная операция — {@code false}
     */
    public static boolean canTable(String table, String op) {
        return GrantOp.fromValueIgnoreCase(op)
                .map(grantOp -> GrantsAuthorities.allowsTable(grantedAuthorities(), table, grantOp))
                .orElse(false);
    }

    /**
     * Есть ли у текущего пользователя действие с указанным кодом. Держатель встроенного действия
     * {@code admin} проходит проверку любого действия (см. {@link GrantsAuthorities#hasAction}).
     */
    public static boolean hasAction(String code) {
        return GrantsAuthorities.hasAction(grantedAuthorities(), code);
    }

    private static List<GrantedAuthority> grantedAuthorities() {
        return authentication()
                .map(authentication -> authentication.getAuthorities().stream()
                        .filter(authority -> !(authority instanceof FactorGrantedAuthority))
                        .map(GrantedAuthority.class::cast)
                        .toList())
                .orElse(List.of());
    }

    private static Optional<Authentication> authentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        return Optional.of(authentication);
    }
}
