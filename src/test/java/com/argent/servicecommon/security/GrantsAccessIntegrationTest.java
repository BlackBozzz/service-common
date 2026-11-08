package com.argent.servicecommon.security;

import com.argent.servicecommon.auth.entity.User;
import com.argent.servicecommon.auth.repository.UserRepository;
import com.argent.servicecommon.auth.repository.UserSessionRepository;
import com.argent.servicecommon.auth.service.TokenService;
import com.argent.servicecommon.security.entity.Role;
import com.argent.servicecommon.security.entity.UserRole;
import com.argent.servicecommon.security.entity.UserRoleId;
import com.argent.servicecommon.security.repository.RoleGrantsRow;
import com.argent.servicecommon.security.repository.RoleRepository;
import com.argent.servicecommon.security.repository.UserRoleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Проверяет authorities из JSON-грантов ролей и бин {@code argentAccess} в {@code @PreAuthorize}.
 */
@SpringBootTest(
        classes = {SecurityTestApplication.class, GrantsAccessIntegrationTest.StubConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureMockMvc
class GrantsAccessIntegrationTest {

    private static final String APPROVE = "/api/test-grants/approve";
    private static final String DOCUMENTS_WRITE = "/api/test-grants/documents-write";
    private static final String DOCUMENTS_READ = "/api/test-grants/documents-read";

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        SecurityTestContainer.register(registry, "it_grants");
    }

    @Autowired private MockMvc mvc;
    @Autowired private TokenService tokenService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository userSessionRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleRepository userRoleRepository;

    private User user;
    private String token;
    private final List<UUID> createdRoles = new ArrayList<>();

    @BeforeEach
    void createUser() {
        User created = new User();
        created.setLogin("grants-" + UUID.randomUUID());
        created.setDisplayName("Пользователь грантов");
        user = userRepository.save(created);
        token = tokenService.issue(user);
    }

    @AfterEach
    void cleanUp() {
        userRoleRepository.deleteAll(userRoleRepository.findByIdUserId(user.getId()));
        for (UUID roleId : createdRoles) {
            roleRepository.deleteById(roleId);
        }
        createdRoles.clear();
        userSessionRepository.deleteAll(userSessionRepository.findAll().stream()
                .filter(session -> session.getUserId().equals(user.getId()))
                .toList());
        userRepository.deleteById(user.getId());
    }

    @Test
    void shouldAllowActionByGrants() throws Exception {
        perform(APPROVE, status().isForbidden());

        assignRole("{\"actions\":[\"documents.approve\"]}");

        perform(APPROVE, status().isOk());
        perform(DOCUMENTS_WRITE, status().isForbidden());
    }

    @Test
    void shouldAllowAnyActionForAdminAction() throws Exception {
        perform(APPROVE, status().isForbidden());

        assignRole("{\"actions\":[\"admin\"]}");

        perform(APPROVE, status().isOk());
        perform(DOCUMENTS_WRITE, status().isForbidden());
    }

    @Test
    void shouldAllowTableByGrants() throws Exception {
        assignRole("{\"tables\":{\"documents\":[\"write\"]}}");

        perform(DOCUMENTS_WRITE, status().isOk());
        perform(DOCUMENTS_READ, status().isForbidden());
        perform(APPROVE, status().isForbidden());
    }

    @Test
    void shouldAllowTableThroughStar() throws Exception {
        assignRole("{\"tables\":{\"*\":[\"read\"]}}");

        perform(DOCUMENTS_READ, status().isOk());
        perform(DOCUMENTS_WRITE, status().isForbidden());
    }

    @Test
    void shouldMergeGrantsOfTwoRoles() throws Exception {
        assignRole("{\"tables\":{\"documents\":[\"read\"]}}");
        assignRole("{\"tables\":{\"documents\":[\"write\"]},\"actions\":[\"documents.approve\"]}");

        perform(DOCUMENTS_READ, status().isOk());
        perform(DOCUMENTS_WRITE, status().isOk());
        mvc.perform(get(APPROVE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", containsInAnyOrder(
                        "table:documents:read", "table:documents:write", "action:documents.approve")));
    }

    @Test
    void shouldReturnEmptyAuthoritiesForNoRoles() throws Exception {
        perform(APPROVE, status().isForbidden());
        perform(DOCUMENTS_WRITE, status().isForbidden());
        perform(DOCUMENTS_READ, status().isForbidden());

        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.grants.tables").isEmpty())
                .andExpect(jsonPath("$.grants.actions", empty()));
    }

    @Test
    void shouldSkipRoleWithMalformedGrants() throws Exception {
        Role malformed = assignRole("{\"foo\":1}");
        Role valid = assignRole("{\"actions\":[\"documents.approve\"]}");

        assertThat(roleRepository.findGrantsByUserId(user.getId()))
                .extracting(RoleGrantsRow::code)
                .containsExactlyInAnyOrder(malformed.getCode(), valid.getCode());
        perform(APPROVE, status().isOk());
    }

    private Role assignRole(String grants) {
        Role role = new Role();
        role.setCode("GRANTS_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        role.setName("Роль грантов");
        role.setGrants(grants);
        role = roleRepository.save(role);
        createdRoles.add(role.getId());
        userRoleRepository.save(new UserRole(new UserRoleId(user.getId(), role.getId())));
        return role;
    }

    private void perform(String path, ResultMatcher expected) throws Exception {
        mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(expected);
    }

    /** Контроллер-заглушка с проверками {@code @argentAccess}. */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubConfiguration {

        @Bean
        GrantsStubController grantsStubController() {
            return new GrantsStubController();
        }
    }

    @RestController
    public static class GrantsStubController {

        @GetMapping(APPROVE)
        @PreAuthorize("@argentAccess.action('documents.approve')")
        public Set<String> approve() {
            return CurrentUser.authorities();
        }

        @GetMapping(DOCUMENTS_WRITE)
        @PreAuthorize("@argentAccess.table('documents', 'write')")
        public String documentsWrite() {
            return "ok";
        }

        @GetMapping(DOCUMENTS_READ)
        @PreAuthorize("@argentAccess.table('documents', 'READ')")
        public String documentsRead() {
            return "ok";
        }
    }
}
