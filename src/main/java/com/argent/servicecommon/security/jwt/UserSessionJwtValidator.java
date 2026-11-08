package com.argent.servicecommon.security.jwt;

import com.argent.servicecommon.auth.entity.User;
import com.argent.servicecommon.auth.repository.UserRepository;
import com.argent.servicecommon.auth.repository.UserSessionRepository;
import com.argent.servicecommon.auth.service.TokenService;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Проверяет на каждом запросе сессию токена и пользователя (существуют, активны, не истекли): отзыв сессии и
 * блокировка действуют сразу, без ожидания истечения токена.
 */
public class UserSessionJwtValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2TokenValidatorResult INVALID = OAuth2TokenValidatorResult.failure(
            new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "Сессия недействительна", null));

    private final UserSessionRepository sessionRepository;
    private final UserRepository userRepository;

    public UserSessionJwtValidator(UserSessionRepository sessionRepository, UserRepository userRepository) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository);
        this.userRepository = Objects.requireNonNull(userRepository);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Optional<UUID> userId = parse(jwt.getSubject());
        Optional<UUID> sessionId = parse(jwt.getClaimAsString(TokenService.SESSION_ID_CLAIM));
        if (userId.isEmpty() || sessionId.isEmpty()) {
            return INVALID;
        }
        boolean sessionActive = sessionRepository.findById(sessionId.get())
                .filter(session -> !session.isRevoked())
                .filter(session -> session.getExpiresAt().isAfter(Instant.now()))
                .filter(session -> session.getUserId().equals(userId.get()))
                .isPresent();
        if (!sessionActive) {
            return INVALID;
        }
        boolean userEnabled = userRepository.findById(userId.get())
                .map(User::isEnabled)
                .orElse(false);
        return userEnabled ? OAuth2TokenValidatorResult.success() : INVALID;
    }

    private static Optional<UUID> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
