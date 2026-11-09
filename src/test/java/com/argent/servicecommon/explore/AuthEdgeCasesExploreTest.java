package com.argent.servicecommon.explore;

import com.argent.servicecommon.auth.AuthProperties;
import com.argent.servicecommon.auth.dto.LoginRequest;
import com.argent.servicecommon.auth.entity.User;
import com.argent.servicecommon.auth.entity.UserSession;
import com.argent.servicecommon.auth.repository.UserRepository;
import com.argent.servicecommon.auth.repository.UserSessionRepository;
import com.argent.servicecommon.auth.service.AuthService;
import com.argent.servicecommon.auth.service.BootstrapAdminInitializer;
import com.argent.servicecommon.auth.service.RoleSeeder;
import com.argent.servicecommon.auth.service.TokenService;
import com.argent.servicecommon.auth.service.UserProvisioningService;
import com.argent.servicecommon.security.SecurityTestApplication;
import com.argent.servicecommon.security.SecurityTestContainer;
import com.argent.servicecommon.security.entity.Role;
import com.argent.servicecommon.security.entity.UserRole;
import com.argent.servicecommon.security.entity.UserRoleId;
import com.argent.servicecommon.security.grants.GrantConstants;
import com.argent.servicecommon.security.repository.RoleRepository;
import com.argent.servicecommon.security.repository.UserRoleRepository;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Исследовательские пробы граничных случаев входа, токенов, грантов и администрирования (QA, одноразовый класс).
 * Каждый тест — одна проба; падение пробы — находка (см. qa-auth-report.md).
 */
@SpringBootTest(classes = SecurityTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class AuthEdgeCasesExploreTest {

    private static final JsonMapper JSON = JsonMapper.shared();
    private static final String PWD = "qa-pass-123";
    private static final String LIMITED_GRANTS = "{\"tables\":{\"a\":[\"read\"]},\"actions\":[\"admin\"]}";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SecurityTestContainer.register(registry, "it_explore_auth");
        registry.add("argent.auth.password-required", () -> "true");
        registry.add("argent.auth.bootstrap-admin.password", () -> "test-admin-pass");
        registry.add("argent.auth.registration.enabled", () -> "true");
        registry.add("argent.auth.registration.default-roles", () -> "QA_DEFAULT");
        registry.add("argent.security.actions", () -> "documents.approve");
    }

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository sessionRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleRepository userRoleRepository;
    @Autowired private TokenService tokenService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private ResourceLoader resourceLoader;
    @Autowired private PlatformTransactionManager transactionManager;

    private User admin;
    private String adminToken;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByLogin("admin").orElseThrow();
        adminToken = tokenService.issue(admin);
        role("QA_DEFAULT", "{\"tables\":{\"qa_t\":[\"read\"]}}");
    }

    @AfterEach
    void cleanUp() {
        userRepository.findAll().stream()
                .filter(u -> u.getLogin().toLowerCase().startsWith("qa") || u.getLogin().isBlank()
                        || u.getLogin().contains(" "))
                .forEach(userRepository::delete);
        roleRepository.findAll().stream()
                .filter(r -> r.getCode().startsWith("QA_"))
                .forEach(roleRepository::delete);
        User current = userRepository.findById(admin.getId()).orElseThrow();
        current.setEnabled(true);
        current.setDisplayName("Администратор");
        current.setPasswordHash(passwordEncoder.encode("test-admin-pass"));
        userRepository.save(current);
        Role adminRole = roleRepository.findByCode(GrantConstants.ADMIN_ROLE_CODE).orElseThrow();
        adminRole.setGrants(GrantConstants.ADMIN_GRANTS);
        adminRole.setName(GrantConstants.ADMIN_ROLE_NAME);
        roleRepository.save(adminRole);
        UserRoleId link = new UserRoleId(admin.getId(), adminRole.getId());
        if (!userRoleRepository.existsById(link)) {
            userRoleRepository.save(new UserRole(link));
        }
    }

    // ---------------------------------------------------------------- вход

    @Test
    void p01_wrongPasswordUnknownUserAndDisabledUserGiveSameAnswer() throws Exception {
        User disabled = user("qa_disabled", PWD);
        disabled.setEnabled(false);
        userRepository.save(disabled);
        login("admin", "wrong-pass").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Неверный логин или пароль"));
        login("qa_nobody_xyz", "wrong-pass").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Неверный логин или пароль"));
        login("qa_disabled", PWD).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Неверный логин или пароль"));
    }

    @Test
    void p02_emptyBodyAndEmptyObjectGive400() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("login"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.TEXT_PLAIN).content("admin"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void p03_loginIsTrimmedButCaseSensitive() throws Exception {
        login("  admin  ", "test-admin-pass").andExpect(status().isOk());
        // Регистр логина значим: «Admin» — другой (несуществующий) пользователь.
        login("Admin", "test-admin-pass").andExpect(status().isUnauthorized());
    }

    @Test
    @Disabled("QA: auth #5, отложено")
    void p04_userWithoutPasswordIsDistinguishableWithoutKnowingPassword() throws Exception {
        user("qa_nopass", null);
        // Ожидаемое (без перечисления пользователей): тот же ответ, что и для неизвестного логина.
        login("qa_nopass", "whatever").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Неверный логин или пароль"));
    }

    @Test
    @Disabled("QA: auth #6, отложено")
    void p05_nulCharacterInLoginGives401Not500() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"ad\\u0000min\",\"password\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void p06_tooLongPasswordAtLoginGives401() throws Exception {
        login("admin", "x".repeat(200)).andExpect(status().isUnauthorized());
        login("admin", "x".repeat(100_000)).andExpect(status().isUnauthorized());
    }

    @Test
    void p07_passwordlessModeLetsAnyoneWithPasswordInWithoutIt() {
        AuthService passwordless = new AuthService(userRepository, passwordEncoder, tokenService,
                props(false, "admin", ""), roleRepository);
        user("qa_haspass", PWD);
        // Документировано: пароль не проверяется даже у пользователей, у которых он задан (включая admin).
        assertThat(passwordless.login(new LoginRequest("qa_haspass", "WRONG"))).isNotBlank();
        assertThat(passwordless.login(new LoginRequest("admin", null))).isNotBlank();
        User disabled = user("qa_dis2", null);
        disabled.setEnabled(false);
        userRepository.save(disabled);
        assertThatThrownBy(() -> passwordless.login(new LoginRequest("qa_dis2", null)))
                .isInstanceOf(BadCredentialsException.class);
    }

    // ---------------------------------------------------------------- регистрация

    @Test
    void p08_registerAssignsDefaultRolesAndRejectsDuplicate() throws Exception {
        String token = token(register("qa_reg", "Рег", PWD).andExpect(status().isCreated()));
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grants.tables.qa_t[0]").value("read"));
        register("qa_reg", "Рег", PWD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("login"));
        register("  qa_reg ", "Рег", PWD).andExpect(status().isBadRequest());
    }

    @Test
    void p09_registerCaseVariantIsDistinctLoginByDesign() throws Exception {
        register("qa_case", "A", PWD).andExpect(status().isCreated());
        // QA auth #1: логин сравнивается с учётом регистра (так задокументировано) — «QA_CASE» другой пользователь.
        register("QA_CASE", "B", PWD).andExpect(status().isCreated());
    }

    @Test
    void p10_registerLoginLengthLimit() throws Exception {
        register("qa" + "x".repeat(99), "A", PWD).andExpect(status().isBadRequest());
        register("qa" + "x".repeat(98), "A", PWD).andExpect(status().isCreated());
    }

    @Test
    void p11_registerCyrillicPasswordOver72BytesGivesValidationError() throws Exception {
        // 40 символов кириллицы = 80 байт UTF-8: проходит @Size(max=72), но BCrypt принимает максимум 72 байта.
        register("qa_cyr", "Кир", "п".repeat(40))
                .andDo(r -> System.out.println("QA p11: " + r.getResponse().getStatus() + " "
                        + r.getResponse().getContentAsString(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
    }

    @Test
    @Disabled("QA: auth #7, отложено")
    void p12_registerDisplayNameIsTrimmedLikeAdminCreate() throws Exception {
        register("qa_trim", "   Боб   ", PWD).andExpect(status().isCreated());
        assertThat(userRepository.findByLogin("qa_trim").orElseThrow().getDisplayName()).isEqualTo("Боб");
    }

    @Test
    @Disabled("QA: auth #8, отложено")
    void p13_registerLoginWithInnerOrUnicodeWhitespace() throws Exception {
        // Неразрывный пробел по краям: String.trim() его не убирает — логин « qa_nb» отличим от «qa_nb».
        register(" qa_nb", "A", PWD).andExpect(status().isCreated());
        assertThat(userRepository.findByLogin("qa_nb")).isPresent();
    }

    @Test
    void p14_registerWeakPasswordsAccepted() throws Exception {
        // Документация: только длина 6..72. Документируем: «123456» и «aaaaaa» принимаются.
        register("qa_weak", "A", "123456").andExpect(status().isCreated());
        register("qa_weak2", "A", "      a").andExpect(status().isCreated());
    }

    // ---------------------------------------------------------------- JWT

    @Test
    void p15_tamperedAndAlgNoneAndForeignKeyTokensRejected() throws Exception {
        String token = adminToken;
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                payload.replace("\"login\":\"admin\"", "\"login\":\"root\"").getBytes(StandardCharsets.UTF_8));
        me(parts[0] + "." + forgedPayload + "." + parts[2]).andExpect(status().isUnauthorized());
        String none = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        me(none + "." + parts[1] + ".").andExpect(status().isUnauthorized());
        String hs = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        me(hs + "." + parts[1] + "." + parts[2]).andExpect(status().isUnauthorized());

        RSAKey foreign = new RSAKeyGenerator(2048).keyID("x").generate();
        JwtEncoder foreignEncoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(foreign)));
        UserSession session = session(admin.getId(), Instant.now().plusSeconds(3600));
        me(encode(foreignEncoder, admin.getId(), session.getId(), Instant.now().plusSeconds(3600)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void p16_expiredTokenAndExpiredSessionRejected() throws Exception {
        UserSession live = session(admin.getId(), Instant.now().plusSeconds(3600));
        me(encode(jwtEncoder, admin.getId(), live.getId(), Instant.now().minusSeconds(120)))
                .andExpect(status().isUnauthorized());
        UserSession expired = session(admin.getId(), Instant.now().minusSeconds(5));
        me(encode(jwtEncoder, admin.getId(), expired.getId(), Instant.now().plusSeconds(3600)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void p17_sessionOfAnotherUserRejected() throws Exception {
        User other = user("qa_other", PWD);
        UserSession othersSession = session(other.getId(), Instant.now().plusSeconds(3600));
        me(encode(jwtEncoder, admin.getId(), othersSession.getId(), Instant.now().plusSeconds(3600)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void p18_logoutRevokesAndSecondLogoutIs401() throws Exception {
        String token = token(login("admin", "test-admin-pass").andExpect(status().isOk()));
        mvc.perform(post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());
        me(token).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/logout")).andExpect(status().isUnauthorized());
    }

    @Test
    void p19_revokeAllAndPasswordResetKillOldTokens() throws Exception {
        User u = user("qa_rev", PWD);
        String t1 = tokenService.issue(u);
        String t2 = tokenService.issue(u);
        mvc.perform(post("/api/users/" + u.getId() + "/revoke-sessions").header(HttpHeaders.AUTHORIZATION,
                "Bearer " + adminToken)).andExpect(status().isNoContent());
        me(t1).andExpect(status().isUnauthorized());
        me(t2).andExpect(status().isUnauthorized());
        String t3 = tokenService.issue(u);
        mvc.perform(post("/api/users/" + u.getId() + "/password").header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"new-pass-1\"}"))
                .andExpect(status().isNoContent());
        me(t3).andExpect(status().isUnauthorized());
        login("qa_rev", PWD).andExpect(status().isUnauthorized());
        login("qa_rev", "new-pass-1").andExpect(status().isOk());
    }

    @Test
    void p20_deletedAndDisabledUserTokensRejected() throws Exception {
        User u = user("qa_del", PWD);
        String token = tokenService.issue(u);
        me(token).andExpect(status().isOk());
        u.setEnabled(false);
        userRepository.save(u);
        me(token).andExpect(status().isUnauthorized());
        userRepository.delete(u);
        me(token).andExpect(status().isUnauthorized());
    }

    @Test
    void p21_grantsAreReadPerRequestNotBakedIntoToken() throws Exception {
        User u = user("qa_live", PWD);
        Role r = role("QA_LIVE", "{\"tables\":{\"qa_t\":[\"read\"]}}");
        assign(u, r);
        String token = tokenService.issue(u);
        me(token).andExpect(jsonPath("$.grants.tables.qa_t[0]").value("read"));
        mvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
        r.setGrants("{\"tables\":{},\"actions\":[\"admin\"]}");
        roleRepository.save(r);
        mvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        me(token).andExpect(jsonPath("$.grants.actions[0]").value("admin"));
    }

    @Test
    void p22_malformedAuthorizationHeaders() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized())
                .andExpect(r -> assertThat(r.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Bearer"));
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer "))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer garbage"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Basic YWRtaW46eA=="))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "bearer " + adminToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").param("access_token", adminToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Disabled("QA: auth #13, отложено")
    void p23_staleTokenDoesNotBlockLoginEndpoint() throws Exception {
        String stale = token(login("admin", "test-admin-pass"));
        mvc.perform(post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + stale))
                .andExpect(status().isNoContent());
        // Ожидаемое: открытый /login работает, даже если клиент по привычке прислал старый токен.
        mvc.perform(post("/api/auth/login").header(HttpHeaders.AUTHORIZATION, "Bearer " + stale)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"admin\",\"password\":\"test-admin-pass\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void p24_sessionExpiresAtStoredInUtc() {
        String token = tokenService.issue(admin);
        String sessionId = JSON.readTree(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]),
                StandardCharsets.UTF_8)).get("sessionId").asString();
        UserSession s = sessionRepository.findById(UUID.fromString(sessionId)).orElseThrow();
        assertThat(Duration.between(Instant.now(), s.getExpiresAt()).toDays()).isBetween(29L, 30L);
    }

    // ---------------------------------------------------------------- гранты

    @Test
    void p25_writeWithoutReadAllowedAndStoredAsIs() throws Exception {
        putRole(adminToken, "QA_W", "{\"tables\":{\"*\":[\"write\"]}}").andExpect(status().isOk())
                .andExpect(jsonPath("$.grants.tables['*'][0]").value("write"));
    }

    @Test
    void p26_invalidGrantsRejected() throws Exception {
        putRole(adminToken, "QA_B1", "{\"tables\":{\"a\":[\"delete\"]}}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("grants.tables"));
        putRole(adminToken, "QA_B2", "{\"tables\":{\"Orders\":[\"read\"]}}").andExpect(status().isBadRequest());
        putRole(adminToken, "QA_B3", "{\"tables\":{\"a\":[\"READ\"]}}").andExpect(status().isBadRequest());
        putRole(adminToken, "QA_B4", "{\"tables\":{\"a\":[]}}").andExpect(status().isBadRequest());
        putRole(adminToken, "QA_B5", "{\"table\":{}}").andExpect(status().isBadRequest());
        putRole(adminToken, "QA_B6", "{\"actions\":[\"A\"]}").andExpect(status().isBadRequest());
        putRole(adminToken, "QA_B7", "{\"actions\":[\"x.\"]}").andExpect(status().isBadRequest());
        putRole(adminToken, "QA_B8", "{\"actions\":[\"admin \"]}").andExpect(status().isBadRequest());
        putRole(adminToken, "QA_B9", "\"{}\"").andExpect(status().isBadRequest());
        putRole(adminToken, "QA_B10", "[]").andExpect(status().isBadRequest());
        putRole(adminToken, "QA_B11", "{\"actions\":[1]}").andExpect(status().isBadRequest());
        putRole(adminToken, "QA_OK", "{\"actions\":[\"a.b-c\",\"x_1\"]}").andExpect(status().isOk());
    }

    @Test
    @Disabled("QA: auth #9, отложено")
    void p27_duplicateKeysInGrantsAreRejected() throws Exception {
        // Ожидаемое для «строгого» разбора: дубль ключа — ошибка, а не тихое «последний победил».
        putRole(adminToken, "QA_DUP", "{\"tables\":{\"a\":[\"read\",\"write\"],\"a\":[\"read\"]}}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void p28_hugeGrantsDocumentIsAcceptedWithoutLimit() throws Exception {
        StringBuilder sb = new StringBuilder("{\"tables\":{");
        for (int i = 0; i < 20_000; i++) {
            sb.append(i == 0 ? "" : ",").append("\"t").append(i).append("\":[\"read\",\"write\"]");
        }
        sb.append("}}");
        long start = System.nanoTime();
        putRole(adminToken, "QA_HUGE", sb.toString()).andExpect(status().isOk());
        User u = user("qa_huge", PWD);
        assign(u, roleRepository.findByCode("QA_HUGE").orElseThrow());
        String token = tokenService.issue(u);
        long reqStart = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            me(token).andExpect(status().isOk());
        }
        System.out.printf("QA p28: put+setup %d ms, 5x /me %d ms%n",
                (reqStart - start) / 1_000_000, (System.nanoTime() - reqStart) / 1_000_000);
    }

    @Test
    void p29_malformedGrantsInDbDoNotBreakLoginOrMe() throws Exception {
        User u = user("qa_bad", PWD);
        Role bad = role("QA_BADDB", "{\"tables\":{\"Orders\":[\"read\"]},\"foo\":1}");
        Role good = role("QA_GOOD", "{\"tables\":{\"qa_t\":[\"write\"]}}");
        assign(u, bad);
        assign(u, good);
        String token = token(login("qa_bad", PWD).andExpect(status().isOk()));
        me(token).andExpect(status().isOk())
                .andExpect(jsonPath("$.grants.tables.qa_t[0]").value("write"))
                .andExpect(jsonPath("$.grants.tables.Orders").doesNotExist());
        mvc.perform(get("/api/roles/QA_BADDB").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- роли

    @Test
    void p30_limitedAdminEscalationAttempts() throws Exception {
        User lim = user("qa_lim", PWD);
        assign(lim, role("QA_LIMITED", LIMITED_GRANTS));
        String lt = tokenService.issue(lim);
        putRole(lt, "QA_STAR", "{\"tables\":{\"*\":[\"read\"]}}").andExpect(status().isForbidden());
        putRole(lt, "QA_B", "{\"tables\":{\"b\":[\"read\"]}}").andExpect(status().isForbidden());
        putRole(lt, "QA_LIMITED", "{\"tables\":{\"a\":[\"read\",\"write\"]},\"actions\":[\"admin\"]}")
                .andExpect(status().isForbidden());
        // Документировано: admin покрывает любые действия.
        putRole(lt, "QA_ACT", "{\"tables\":{\"a\":[\"read\"]},\"actions\":[\"admin\",\"documents.approve\"]}")
                .andExpect(status().isOk());
        mvc.perform(put("/api/users/" + lim.getId() + "/roles").header(HttpHeaders.AUTHORIZATION, "Bearer " + lt)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roleCodes\":[\"QA_LIMITED\",\"ADMIN\"]}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/users/" + admin.getId() + "/password").header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + lt).contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"hacked1\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/users/" + admin.getId() + "/revoke-sessions").header(HttpHeaders.AUTHORIZATION,
                "Bearer " + lt)).andExpect(status().isForbidden());
    }

    @Test
    @Disabled("QA: auth #12, отложено")
    void p31_limitedAdminCannotRenameSystemRole() throws Exception {
        User lim = user("qa_lim2", PWD);
        assign(lim, role("QA_LIMITED", LIMITED_GRANTS));
        String lt = tokenService.issue(lim);
        // Ожидаемое: правка роли, которой вызывающий не «покрывает» (ADMIN = * rw), запрещена.
        mvc.perform(put("/api/roles/ADMIN").header(HttpHeaders.AUTHORIZATION, "Bearer " + lt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Pwned\",\"description\":\"by limited\",\"grants\":{}}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void p32_limitedAdminCannotDeleteWiderRole() throws Exception {
        User lim = user("qa_lim3", PWD);
        assign(lim, role("QA_LIMITED", LIMITED_GRANTS));
        role("QA_WIDE", "{\"tables\":{\"*\":[\"read\",\"write\"]}}");
        String lt = tokenService.issue(lim);
        // Ожидаемое: удалить роль шире своих прав нельзя (создать такую он бы не смог).
        mvc.perform(delete("/api/roles/QA_WIDE").header(HttpHeaders.AUTHORIZATION, "Bearer " + lt))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Нельзя удалить роль с правами шире собственных"));
        assertThat(roleRepository.findByCode("QA_WIDE")).isPresent();
        // Роль в пределах собственных прав удалить можно.
        role("QA_NARROW", "{\"tables\":{\"a\":[\"read\"]}}");
        mvc.perform(delete("/api/roles/QA_NARROW").header(HttpHeaders.AUTHORIZATION, "Bearer " + lt))
                .andExpect(status().isNoContent());
    }

    @Test
    void p33_roleDeletionRules() throws Exception {
        mvc.perform(delete("/api/roles/ADMIN").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict());
        Role r = role("QA_ASSIGNED", "{}");
        assign(user("qa_holder", PWD), r);
        mvc.perform(delete("/api/roles/QA_ASSIGNED").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Роль назначена пользователям"));
        mvc.perform(delete("/api/roles/QA_NOPE").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound());
        putRole(adminToken, "qa_lower", "{}").andExpect(status().isBadRequest());
        putRole(adminToken, "QA_" + "X".repeat(60), "{}").andExpect(status().isBadRequest());
    }

    @Test
    void p34_systemRoleGrantsCannotBeNarrowedAndActionsOutsideCatalogAccepted() throws Exception {
        putRole(adminToken, "ADMIN", "{\"tables\":{}}").andExpect(status().isOk())
                .andExpect(jsonPath("$.grants.actions[0]").value("admin"));
        putRole(adminToken, "QA_CAT", "{\"actions\":[\"not.in.catalog\"]}").andExpect(status().isOk());
    }

    @Test
    void p35_roleNameIsTrimmedAndBlankRejected() throws Exception {
        mvc.perform(put("/api/roles/QA_N").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/roles/QA_N").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- пользователи

    @Test
    void p36_createUserDuplicateAndCaseVariant() throws Exception {
        createUser("qa_u1", "A", PWD).andExpect(status().isCreated());
        createUser("qa_u1", "A", PWD).andExpect(status().isBadRequest());
        createUser(" qa_u1 ", "A", PWD).andExpect(status().isBadRequest());
        // QA auth #1: логин сравнивается с учётом регистра (так задокументировано).
        createUser("QA_U1", "A", PWD).andExpect(status().isCreated());
        // QA auth #3: пароль длиннее 72 байт в UTF-8 (40 кириллических символов) — ошибка поля password.
        createUser("qa_u_cyr", "A", "п".repeat(40)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"))
                .andExpect(jsonPath("$.errors[0].message").value("Пароль не должен превышать 72 байта в UTF-8"));
        assertThat(userRepository.findByLogin("qa_u_cyr")).isEmpty();
    }

    @Test
    void p37_updateUserWithoutEnabledFieldKeepsUserEnabled() throws Exception {
        User u = user("qa_upd", PWD);
        // Клиент шлёт только displayName (PATCH-подобный PUT): ожидаем, что пользователь не отключится молча.
        mvc.perform(put("/api/users/" + u.getId()).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Новое имя\"}"))
                .andExpect(status().is4xxClientError());
        assertThat(userRepository.findById(u.getId()).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    void p38_setRolesEdgeCases() throws Exception {
        User u = user("qa_roles", PWD);
        role("QA_R1", "{\"tables\":{\"qa_t\":[\"read\"]}}");
        setRoles(u.getId(), "{\"roleCodes\":[\"QA_R1\",\"QA_R1\"]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(1));
        setRoles(u.getId(), "{\"roleCodes\":[\"QA_NOPE\"]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("roleCodes"));
        setRoles(u.getId(), "{\"roleCodes\":[null]}").andExpect(status().isBadRequest());
        // Ожидаемое: тело без roleCodes — ошибка, а не молчаливое снятие всех ролей.
        setRoles(u.getId(), "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("roleCodes"));
        setRoles(u.getId(), "{\"roles\":[\"QA_R1\"]}").andExpect(status().isBadRequest());
        assertThat(userRoleRepository.findByIdUserId(u.getId())).hasSize(1);
    }

    @Test
    void p39_selfProtection() throws Exception {
        mvc.perform(put("/api/users/" + admin.getId()).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"A\",\"enabled\":false}"))
                .andExpect(status().isConflict());
        setRoles(admin.getId(), "{\"roleCodes\":[]}").andExpect(status().isConflict());
        mvc.perform(post("/api/users/" + admin.getId() + "/revoke-sessions").header(HttpHeaders.AUTHORIZATION,
                "Bearer " + adminToken)).andExpect(status().isNoContent());
        me(adminToken).andExpect(status().isUnauthorized());
    }

    @Test
    @Disabled("QA: auth #10, отложено")
    void p40_errorShapes() throws Exception {
        String t = tokenService.issue(admin);
        mvc.perform(put("/api/users/not-a-uuid").header(HttpHeaders.AUTHORIZATION, "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"A\",\"enabled\":true}"))
                .andExpect(status().isBadRequest())
                // Ожидаемое: без внутренних деталей (имён Java-типов) в detail.
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).doesNotContain("java.util.UUID"));
        mvc.perform(put("/api/users/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"A\",\"enabled\":true}"))
                .andExpect(status().isNotFound());
        User plain = user("qa_plain", PWD);
        String pt = tokenService.issue(plain);
        mvc.perform(get("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + pt))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Недостаточно прав для выполнения операции."));
        mvc.perform(get("/api/grants/catalog").header(HttpHeaders.AUTHORIZATION, "Bearer " + pt))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/users").param("size", "0").param("page", "-5")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + t))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(1));
        mvc.perform(get("/api/users").param("size", "abc").header(HttpHeaders.AUTHORIZATION, "Bearer " + t))
                .andExpect(status().isBadRequest());
    }

    @Test
    void p41_adminSetPasswordCyrillicOver72Bytes() throws Exception {
        User u = user("qa_pw", PWD);
        mvc.perform(post("/api/users/" + u.getId() + "/password").header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + "п".repeat(40) + "\"}"))
                .andDo(r -> System.out.println("QA p41: " + r.getResponse().getStatus() + " "
                        + r.getResponse().getContentAsString(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
    }

    @Test
    void p42_userSearchWithLikeWildcards() throws Exception {
        user("qa_s1", PWD);
        mvc.perform(get("/api/users").param("search", "%").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void p47_duplicateKeyGrantsStoredValue() throws Exception {
        putRole(adminToken, "QA_DUP2", "{\"tables\":{\"a\":[\"read\",\"write\"],\"a\":[\"read\"]}}")
                .andDo(r -> System.out.println("QA p47: " + r.getResponse().getContentAsString(StandardCharsets.UTF_8)));
    }

    @Test
    void p48_nonAdminGets403BeforeExistenceCheck() throws Exception {
        String pt = tokenService.issue(user("qa_plain2", PWD));
        mvc.perform(put("/api/users/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, "Bearer " + pt)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"A\",\"enabled\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/roles/NOPE").header(HttpHeaders.AUTHORIZATION, "Bearer " + pt))
                .andExpect(status().isForbidden());
    }

    @Test
    void p49_limitedAdminCanStripGrantsOfStrongerUsersRole() throws Exception {
        User lim = user("qa_lim4", PWD);
        assign(lim, role("QA_LIMITED", LIMITED_GRANTS));
        User strong = user("qa_strong", PWD);
        assign(strong, role("QA_STRONGR", "{\"tables\":{\"*\":[\"read\",\"write\"]}}"));
        String lt = tokenService.issue(lim);
        // Документированное ограничение: сузить роль можно, хотя управлять её держателем нельзя.
        mvc.perform(put("/api/users/" + strong.getId() + "/roles").header(HttpHeaders.AUTHORIZATION, "Bearer " + lt)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roleCodes\":[]}"))
                .andExpect(status().isForbidden());
        putRole(lt, "QA_STRONGR", "{}").andExpect(status().isOk());
    }

    @Test
    void p50_registrationDoesNotLetUserChooseRoles() throws Exception {
        String body = "{\"login\":\"qa_reg2\",\"displayName\":\"A\",\"password\":\"" + PWD
                + "\",\"roles\":[\"ADMIN\"],\"roleCodes\":[\"ADMIN\"]}";
        String token = token(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isCreated()));
        me(token).andExpect(jsonPath("$.grants.actions").isEmpty());
    }

    // ---------------------------------------------------------------- файл ролей, bootstrap, импорт

    @Test
    @Disabled("QA: auth #9, отложено")
    void p43_rolesFileModes() throws Exception {
        Path file = Files.createTempFile("qa-roles", ".json");
        Files.writeString(file, "[{\"code\":\"QA_SEED\",\"name\":\"Файл\",\"grants\":{\"tables\":{\"qa_t\":[\"read\"]}}},"
                + "{\"code\":\"ADMIN\",\"name\":\"X\",\"grants\":{}}]");
        seeder(file, AuthProperties.RolesFileMode.CREATE).seed();
        Role seeded = roleRepository.findByCode("QA_SEED").orElseThrow();
        seeded.setName("Из UI");
        roleRepository.save(seeded);
        seeder(file, AuthProperties.RolesFileMode.CREATE).seed();
        assertThat(roleRepository.findByCode("QA_SEED").orElseThrow().getName()).isEqualTo("Из UI");
        seeder(file, AuthProperties.RolesFileMode.OVERWRITE).seed();
        assertThat(roleRepository.findByCode("QA_SEED").orElseThrow().getName()).isEqualTo("Файл");
        assertThat(roleRepository.findByCode("ADMIN").orElseThrow().getGrants()).contains("admin");

        Files.writeString(file, "[{\"code\":\"QA_D\",\"name\":\"a\"},{\"code\":\"QA_D\",\"name\":\"b\"}]");
        assertThatThrownBy(() -> seeder(file, AuthProperties.RolesFileMode.CREATE).seed())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("повторно");
        Files.writeString(file, "[{\"code\":\"QA_D\",\"name\":\"a\",\"grant\":{}}]");
        assertThatThrownBy(() -> seeder(file, AuthProperties.RolesFileMode.CREATE).seed())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("неизвестное поле grant");
        Files.writeString(file, "[{\"code\":\"QA_D\",\"name\":\"a\",\"grants\":{\"tables\":{\"a\":[\"read\"],\"a\":[\"write\"]}}}]");
        // Дубль ключа в файле: ожидаем отказ, наблюдаем ли «последний победил»?
        assertThatThrownBy(() -> seeder(file, AuthProperties.RolesFileMode.CREATE).seed())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void p44_rolesFileOverwriteCanStripAdminBypassingLastAdminRule() throws Exception {
        // Админ-роль, назначенная единственному активному «настоящему» админу помимо ADMIN — проверка обхода
        // правила «последний админ» в режиме overwrite (не выполняется для ADMIN — она системная).
        Role r = role("QA_SUPER", "{\"tables\":{\"*\":[\"read\",\"write\"]},\"actions\":[\"admin\"]}");
        User u = user("qa_super", PWD);
        assign(u, r);
        Path file = Files.createTempFile("qa-roles", ".json");
        Files.writeString(file, "[{\"code\":\"QA_SUPER\",\"name\":\"Обычная\",\"grants\":{}}]");
        seeder(file, AuthProperties.RolesFileMode.OVERWRITE).seed();
        // Документируем: файл — источник истины, admin снят без проверок.
        assertThat(roleRepository.findByCode("QA_SUPER").orElseThrow().getGrants()).doesNotContain("admin");
    }

    @Test
    void p45_bootstrapAdminEdgeCases() {
        // Нет такого пользователя, парольный режим, пустой пароль — запуск прерывается.
        assertThatThrownBy(() -> bootstrap("qa_boot", "", true).run(null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(userRepository.findByLogin("qa_boot")).isEmpty();
        // Существующий пользователь без пароля в парольном режиме: только WARN, роль ADMIN выдаётся.
        User nopass = user("qa_boot2", null);
        bootstrap("qa_boot2", "", true).run(null);
        assertThat(userRoleRepository.findByIdUserId(nopass.getId())).hasSize(1);
        // Существующий обычный пользователь с паролем молча получает ADMIN, если его логин указан в настройке.
        User regular = user("qa_victim", PWD);
        bootstrap("qa_victim", "other-pass", true).run(null);
        assertThat(userRoleRepository.findByIdUserId(regular.getId())).hasSize(1);
        // Отключённый пользователь получает ADMIN, но остаётся отключённым — система «с админом», войти нельзя.
        User off = user("qa_off", PWD);
        off.setEnabled(false);
        userRepository.save(off);
        bootstrap("qa_off", "x", true).run(null);
        assertThat(userRepository.findById(off.getId()).orElseThrow().isEnabled()).isFalse();
    }

    @Test
    @Disabled("QA: auth #11, отложено")
    void p46_provisioningRejectsBlankLogin() {
        UserProvisioningService provisioning = new UserProvisioningService(userRepository);
        // Ожидаемое: пустой логин после trim — ошибка, а не пользователь с login="".
        assertThatThrownBy(() -> provisioning.upsert("   ", "Пусто")).isInstanceOf(RuntimeException.class);
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions login(String login, String password) throws Exception {
        String body = JSON.writeValueAsString(password == null
                ? java.util.Map.of("login", login) : java.util.Map.of("login", login, "password", password));
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions register(String login, String displayName, String password) throws Exception {
        String body = JSON.writeValueAsString(java.util.Map.of("login", login, "displayName", displayName,
                "password", password));
        return mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions createUser(String login, String displayName, String password) throws Exception {
        String body = JSON.writeValueAsString(java.util.Map.of("login", login, "displayName", displayName,
                "password", password));
        return mvc.perform(post("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions setRoles(UUID id, String body) throws Exception {
        return mvc.perform(put("/api/users/" + id + "/roles").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions putRole(String token, String code, String grantsJson) throws Exception {
        return mvc.perform(put("/api/roles/" + code).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"QA\",\"grants\":" + grantsJson + "}"));
    }

    private ResultActions me(String token) throws Exception {
        return mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private static String token(ResultActions actions) throws Exception {
        MvcResult result = actions.andReturn();
        JsonNode node = JSON.readTree(result.getResponse().getContentAsString());
        return node.get("token").asString();
    }

    private User user(String login, String password) {
        User u = new User();
        u.setLogin(login);
        u.setDisplayName(login);
        u.setEnabled(true);
        if (password != null) {
            u.setPasswordHash(passwordEncoder.encode(password));
        }
        return userRepository.save(u);
    }

    private Role role(String code, String grants) {
        Role r = roleRepository.findByCode(code).orElseGet(Role::new);
        r.setCode(code);
        r.setName(code);
        r.setGrants(grants);
        return roleRepository.save(r);
    }

    private void assign(User u, Role r) {
        userRoleRepository.save(new UserRole(new UserRoleId(u.getId(), r.getId())));
    }

    private UserSession session(UUID userId, Instant expiresAt) {
        UserSession s = new UserSession();
        s.setUserId(userId);
        s.setExpiresAt(expiresAt);
        return sessionRepository.save(s);
    }

    private static String encode(JwtEncoder encoder, UUID subject, UUID sessionId, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(subject.toString())
                .issuedAt(expiresAt.minusSeconds(7200))
                .expiresAt(expiresAt)
                .claim("sessionId", sessionId.toString())
                .claim("login", "admin")
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }

    private RoleSeeder seeder(Path file, AuthProperties.RolesFileMode mode) {
        return new RoleSeeder("file:" + file.toAbsolutePath(), mode, resourceLoader, roleRepository,
                transactionManager);
    }

    private BootstrapAdminInitializer bootstrap(String login, String password, boolean passwordRequired) {
        return new BootstrapAdminInitializer(props(passwordRequired, login, password), userRepository,
                roleRepository, userRoleRepository, passwordEncoder, transactionManager);
    }

    private static AuthProperties props(boolean passwordRequired, String login, String password) {
        return new AuthProperties(passwordRequired, new AuthProperties.Registration(false, List.of()),
                new AuthProperties.BootstrapAdmin(login, password), Duration.ofDays(30),
                new AuthProperties.Jwt(Path.of("unused.pem")), "classpath:none.json",
                AuthProperties.RolesFileMode.CREATE);
    }
}
