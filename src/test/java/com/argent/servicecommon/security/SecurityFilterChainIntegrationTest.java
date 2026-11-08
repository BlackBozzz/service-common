package com.argent.servicecommon.security;

import com.argent.servicecommon.auth.AuthProperties;
import com.argent.servicecommon.auth.entity.User;
import com.argent.servicecommon.auth.entity.UserSession;
import com.argent.servicecommon.auth.repository.UserRepository;
import com.argent.servicecommon.auth.repository.UserSessionRepository;
import com.argent.servicecommon.auth.service.TokenService;
import com.argent.servicecommon.security.entity.Role;
import com.argent.servicecommon.security.entity.UserRole;
import com.argent.servicecommon.security.entity.UserRoleId;
import com.argent.servicecommon.security.jwt.JwtKeyProvider;
import com.argent.servicecommon.security.repository.RoleRepository;
import com.argent.servicecommon.security.repository.UserRoleRepository;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Проверяет цепочку фильтров безопасности: выдачу и проверку JWT, отзыв сессий,
 * блокировку пользователя, права ролей как authorities и ответы 401/403 в формате ProblemDetail.
 */
@SpringBootTest(
        classes = {SecurityTestApplication.class, SecurityFilterChainIntegrationTest.StubConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureMockMvc
class SecurityFilterChainIntegrationTest {

    private static final String APPROVE_ACTION = "documents.approve";

    @TempDir
    static Path keyDirectory;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        SecurityTestContainer.register(registry, "it_security_chain");
        registry.add("argent.auth.jwt.key-file",
                () -> keyDirectory.resolve("nested/argent-jwt.pem").toString());
        registry.add("argent.security.permit-paths", () -> "/api/public/**");
    }

    @Autowired private MockMvc mvc;
    @Autowired private TokenService tokenService;
    @Autowired private AuthProperties authProperties;
    @Autowired private RSAKey rsaKey;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository userSessionRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleRepository userRoleRepository;

    private User user;
    private final List<UUID> createdRoles = new ArrayList<>();

    @BeforeEach
    void createUser() {
        User created = new User();
        created.setLogin("chain-" + UUID.randomUUID());
        created.setDisplayName("Пользователь цепочки");
        user = userRepository.save(created);
    }

    @AfterEach
    void cleanUp() {
        userRoleRepository.deleteAll(userRoleRepository.findByIdUserId(user.getId()));
        for (UUID roleId : createdRoles) {
            roleRepository.deleteById(roleId);
        }
        userSessionRepository.deleteAll(userSessionRepository.findAll().stream()
                .filter(session -> session.getUserId().equals(user.getId()))
                .toList());
        userRepository.deleteById(user.getId());
    }

    @Test
    void shouldRejectAnonymousWithProblemDetail() throws Exception {
        mvc.perform(get("/api/argent-dictionary"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Требуется вход"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void shouldAcceptIssuedToken() throws Exception {
        Instant before = Instant.now();
        String token = tokenService.issue(user);

        mvc.perform(get("/api/argent-dictionary").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        List<UserSession> sessions = userSessionRepository.findByUserIdAndRevokedFalse(user.getId());
        assertThat(sessions).hasSize(1);
        Duration ttl = Duration.between(before, sessions.getFirst().getExpiresAt());
        assertThat(ttl).isBetween(Duration.ofDays(30).minusMinutes(1), Duration.ofDays(30).plusMinutes(1));
    }

    @Test
    void shouldRejectTamperedToken() throws Exception {
        String token = tokenService.issue(user);
        String tampered = token.substring(0, token.length() - 4)
                + (token.endsWith("AAAA") ? "BBBB" : "AAAA");

        mvc.perform(get("/api/argent-dictionary").header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Требуется вход"));
    }

    @Test
    void shouldRejectExpiredToken() throws Exception {
        UserSession session = saveSession(user.getId(), Instant.now().plus(Duration.ofDays(1)));
        Instant past = Instant.now().minus(Duration.ofHours(1));
        String token = encode(user.getId(), session.getId(), past.minus(Duration.ofHours(1)), past);

        expectUnauthorized(token);
    }

    @Test
    void shouldRejectSessionExpiredBeforeJwt() throws Exception {
        UserSession session = saveSession(user.getId(), Instant.now().minus(Duration.ofHours(1)));
        Instant now = Instant.now();
        String token = encode(user.getId(), session.getId(), now, now.plus(Duration.ofHours(1)));

        expectUnauthorized(token);
    }

    @Test
    void shouldRejectSessionOfAnotherUser() throws Exception {
        User other = new User();
        other.setLogin("chain-other-" + UUID.randomUUID());
        other.setDisplayName("Другой пользователь");
        other = userRepository.save(other);
        try {
            UserSession otherSession = saveSession(other.getId(), Instant.now().plus(Duration.ofDays(1)));
            Instant now = Instant.now();
            String token = encode(user.getId(), otherSession.getId(), now, now.plus(Duration.ofHours(1)));

            expectUnauthorized(token);
        } finally {
            userRepository.deleteById(other.getId());
        }
    }

    @Test
    void shouldPermitConfiguredAndDefaultPaths() throws Exception {
        int apiDocsStatus = mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getStatus();
        assertThat(apiDocsStatus).isNotEqualTo(401);

        mvc.perform(get("/api/public/ping"))
                .andExpect(status().isOk())
                .andExpect(content().string("pong"));
    }

    @Test
    void shouldRejectRevokedSession() throws Exception {
        String token = tokenService.issue(user);
        mvc.perform(get("/api/argent-dictionary").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        tokenService.revokeAll(user.getId());

        mvc.perform(get("/api/argent-dictionary").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Требуется вход"));
    }

    @Test
    void shouldRejectDisabledUser() throws Exception {
        String token = tokenService.issue(user);

        user.setEnabled(false);
        user = userRepository.save(user);

        mvc.perform(get("/api/argent-dictionary").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Требуется вход"));
    }

    @Test
    void shouldReturnForbiddenAsProblemDetail() throws Exception {
        String token = tokenService.issue(user);

        mvc.perform(get("/api/test-security/approve").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Недостаточно прав"));
    }

    @Test
    void shouldAllowActionGrantedByRole() throws Exception {
        String token = tokenService.issue(user);
        Role role = new Role();
        role.setCode("APPROVER_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        role.setName("Согласующий");
        role.setGrants("{\"actions\":[\"" + APPROVE_ACTION + "\"]}");
        role = roleRepository.save(role);
        createdRoles.add(role.getId());
        userRoleRepository.save(new UserRole(new UserRoleId(user.getId(), role.getId())));

        mvc.perform(get("/api/test-security/approve").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.getId().toString()))
                .andExpect(jsonPath("$.permissions", containsInAnyOrder("action:" + APPROVE_ACTION)));
    }

    @Test
    void shouldPersistKeyBetweenContexts() {
        Path keyFile = authProperties.jwt().keyFile();
        assertThat(keyFile).isEqualTo(keyDirectory.resolve("nested/argent-jwt.pem"));
        assertThat(Files.exists(keyFile)).isTrue();

        RSAKey reloaded = JwtKeyProvider.load(keyFile);

        assertThat(reloaded.getKeyID()).isEqualTo(rsaKey.getKeyID());
        assertThat(reloaded.getModulus()).isEqualTo(rsaKey.getModulus());
    }

    private UserSession saveSession(UUID userId, Instant expiresAt) {
        UserSession session = new UserSession();
        session.setUserId(userId);
        session.setExpiresAt(expiresAt);
        return userSessionRepository.save(session);
    }

    /** Подписывает токен напрямую, минуя {@link TokenService}, чтобы задать произвольные claims. */
    private String encode(UUID subject, UUID sessionId, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(subject.toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("sessionId", sessionId.toString())
                .claim("login", user.getLogin())
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }

    private void expectUnauthorized(String token) throws Exception {
        mvc.perform(get("/api/argent-dictionary").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Требуется вход"));
    }

    /** Контроллеры-заглушки для проверки {@code @PreAuthorize} и открытых путей. */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubConfiguration {

        @Bean
        ApproveStubController approveStubController() {
            return new ApproveStubController();
        }

        @Bean
        PublicStubController publicStubController() {
            return new PublicStubController();
        }
    }

    @RestController
    public static class ApproveStubController {

        @GetMapping("/api/test-security/approve")
        @PreAuthorize("@argentAccess.action('documents.approve')")
        public Map<String, Object> approve() {
            return Map.of(
                    "userId", CurrentUser.id().map(UUID::toString).orElse(""),
                    "permissions", Set.copyOf(CurrentUser.authorities()));
        }
    }

    /** Открытый путь из {@code argent.security.permit-paths}. */
    @RestController
    public static class PublicStubController {

        @GetMapping("/api/public/ping")
        public String ping() {
            return "pong";
        }
    }
}
