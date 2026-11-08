package com.argent.servicecommon.auth;

import com.argent.servicecommon.auth.entity.User;
import com.argent.servicecommon.auth.repository.UserRepository;
import com.argent.servicecommon.auth.service.UserProvisioningService;
import com.argent.servicecommon.security.SecurityTestApplication;
import com.argent.servicecommon.security.SecurityTestContainer;
import com.argent.servicecommon.security.grants.GrantConstants;
import com.argent.servicecommon.security.repository.RoleRepository;
import com.argent.servicecommon.security.repository.UserRoleRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Проверяет вход только по логину, текущего пользователя, выход, настройки входа
 * и импорт пользователей в безпарольном режиме.
 */
@SpringBootTest(classes = SecurityTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class AuthControllerPasswordlessIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SecurityTestContainer.register(registry, "it_auth_pwdless");
        registry.add("argent.auth.password-required", () -> "false");
        registry.add("argent.auth.bootstrap-admin.password", () -> "");
        registry.add("argent.auth.registration.enabled", () -> "false");
    }

    @Autowired private MockMvc mvc;
    @Autowired private UserProvisioningService provisioningService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleRepository userRoleRepository;

    @AfterEach
    void cleanUp() {
        userRepository.findByLogin("ivan").ifPresent(userRepository::delete);
    }

    @Test
    void shouldLoginByLoginOnly() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"  admin \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(not(emptyString())));
    }

    @Test
    void shouldRejectUnknownLogin() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"nobody\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Пользователь не найден"));
    }

    @Test
    void shouldReturnMeWithGrants() throws Exception {
        String token = login("admin");

        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.login").value("admin"))
                .andExpect(jsonPath("$.grants.tables['*']", containsInAnyOrder("read", "write")))
                .andExpect(jsonPath("$.grants.actions", hasItem("admin")))
                .andExpect(jsonPath("$.permissions").doesNotExist());
    }

    @Test
    void shouldReturnConfig() throws Exception {
        mvc.perform(get("/api/auth/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordRequired").value(false))
                .andExpect(jsonPath("$.registrationEnabled").value(false));
    }

    @Test
    void shouldLogoutAndRejectToken() throws Exception {
        String token = login("admin");

        mvc.perform(post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectRegistrationWhenDisabled() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"newbie\",\"displayName\":\"Новичок\",\"password\":\"newbie-pass\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Регистрация отключена"));
    }

    @Test
    void shouldUpsertWithoutTouchingRoles() {
        UUID adminRoleId = roleRepository.findByCode(GrantConstants.ADMIN_ROLE_CODE).orElseThrow().getId();
        String originalName = userRepository.findByLogin("admin").orElseThrow().getDisplayName();
        try {
            User admin = provisioningService.upsert("admin", "Новое имя");

            assertThat(userRepository.findById(admin.getId()).orElseThrow().getDisplayName())
                    .isEqualTo("Новое имя");
            assertThat(userRoleRepository.findByIdUserId(admin.getId()))
                    .anyMatch(link -> link.getId().getRoleId().equals(adminRoleId));

            User ivan = provisioningService.upsert("ivan", "Иван");

            User saved = userRepository.findById(ivan.getId()).orElseThrow();
            assertThat(saved.getLogin()).isEqualTo("ivan");
            assertThat(saved.getDisplayName()).isEqualTo("Иван");
            assertThat(saved.isEnabled()).isTrue();
            assertThat(saved.getPasswordHash()).isNull();
            assertThat(userRoleRepository.findByIdUserId(ivan.getId())).isEmpty();
        } finally {
            provisioningService.upsert("admin", originalName);
        }
    }

    private String login(String login) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"" + login + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }
}
