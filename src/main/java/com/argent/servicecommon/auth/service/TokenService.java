package com.argent.servicecommon.auth.service;

import com.argent.servicecommon.auth.entity.User;
import com.argent.servicecommon.auth.entity.UserSession;
import com.argent.servicecommon.auth.repository.UserSessionRepository;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Выдаёт JWT, привязанные к сессиям в базе данных, и отзывает эти сессии.
 *
 * <p>Токен содержит {@code sub} — идентификатор пользователя, {@code sessionId} — идентификатор
 * сессии и {@code login}. Срок действия токена совпадает со сроком сессии. Отзыв сессии
 * делает токен недействительным при следующем запросе.</p>
 */
public class TokenService {

    /** Имя claim с идентификатором сессии. */
    public static final String SESSION_ID_CLAIM = "sessionId";

    /** Имя claim с логином пользователя. */
    public static final String LOGIN_CLAIM = "login";

    private final UserSessionRepository sessionRepository;
    private final JwtEncoder jwtEncoder;
    private final Duration sessionTtl;

    public TokenService(UserSessionRepository sessionRepository, JwtEncoder jwtEncoder, Duration sessionTtl) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository);
        this.jwtEncoder = Objects.requireNonNull(jwtEncoder);
        this.sessionTtl = Objects.requireNonNull(sessionTtl);
    }

    /**
     * Создаёт сессию пользователя и выдаёт подписанный RS256 токен для неё.
     *
     * @param user сохранённый пользователь
     * @return компактная сериализация JWT
     */
    @Transactional
    public String issue(User user) {
        Objects.requireNonNull(user.getId(), "Пользователь должен быть сохранён до выдачи токена");
        Instant now = Instant.now();
        UserSession session = new UserSession();
        session.setUserId(user.getId());
        session.setCreatedAt(now);
        session.setExpiresAt(now.plus(sessionTtl));
        session = sessionRepository.save(session);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(session.getExpiresAt())
                .claim(SESSION_ID_CLAIM, session.getId().toString())
                .claim(LOGIN_CLAIM, user.getLogin())
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /**
     * Отзывает сессию; отсутствующая или уже отозванная сессия не считается ошибкой.
     *
     * @param sessionId идентификатор сессии
     */
    @Transactional
    public void revoke(UUID sessionId) {
        sessionRepository.findById(sessionId)
                .filter(session -> !session.isRevoked())
                .ifPresent(session -> {
                    session.setRevoked(true);
                    sessionRepository.save(session);
                });
    }

    /**
     * Отзывает все действующие сессии пользователя.
     *
     * @param userId идентификатор пользователя
     */
    @Transactional
    public void revokeAll(UUID userId) {
        List<UserSession> sessions = sessionRepository.findByUserIdAndRevokedFalse(userId);
        sessions.forEach(session -> session.setRevoked(true));
        sessionRepository.saveAll(sessions);
    }
}
