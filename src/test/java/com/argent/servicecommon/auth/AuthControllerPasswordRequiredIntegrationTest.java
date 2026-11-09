package com.argent.servicecommon.auth;

import com.argent.servicecommon.auth.repository.UserRepository;
import com.argent.servicecommon.auth.service.UserProvisioningService;
import com.argent.servicecommon.security.SecurityTestApplication;
import com.argent.servicecommon.security.SecurityTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Проверяет вход по паролю, регистрацию и обязательность пароля bootstrap-администратора.
 */
@SpringBootTest(classes = SecurityTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class AuthControllerPasswordRequiredIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SecurityTestContainer.register(registry, "it_auth_pwd");
        registry.add("argent.auth.password-required", () -> "true");
        registry.add("argent.auth.bootstrap-admin.password", () -> "secret123");
        registry.add("argent.auth.registration.enabled", () -> "true");
        registry.add("argent.auth.registration.default-roles", () -> "ADMIN");
    }

    @Autowired private MockMvc mvc;
    @Autowired private UserProvisioningService provisioningService;
    @Autowired private UserRepository userRepository;

    @AfterEach
    void cleanUp() {
        userRepository.findByLogin("ivan").ifPresent(userRepository::delete);
        userRepository.findByLogin("newbie").ifPresent(userRepository::delete);
    }

    @Test
    void shouldLoginWithPassword() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"admin\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(not(emptyString())));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"admin\",\"password\":\"wrong-pass\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Неверный логин или пароль"));
    }

    @Test
    void shouldRejectUserWithoutPassword() throws Exception {
        provisioningService.upsert("ivan", "Иван");

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"ivan\",\"password\":\"anything\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Пароль не задан, обратитесь к администратору"));
    }

    @Test
    void shouldRegisterAndIssueToken() throws Exception {
        String body = "{\"login\":\"newbie\",\"displayName\":\"Новичок\",\"password\":\"newbie-pass\"}";

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value(not(emptyString())));

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("login"));
    }

    @Test
    void shouldRejectRegistrationPasswordOver72Utf8Bytes() throws Exception {
        // QA auth #3: 40 кириллических символов проходят @Size(max = 72), но занимают 80 байт.
        String body = "{\"login\":\"newbie\",\"displayName\":\"Новичок\",\"password\":\"" + "п".repeat(40) + "\"}";

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"))
                .andExpect(jsonPath("$.errors[0].message").value("Пароль не должен превышать 72 байта в UTF-8"));
        assertThat(userRepository.findByLogin("newbie")).isEmpty();
    }

    @Test
    void loginIsCaseSensitive() throws Exception {
        // QA auth #1: так задумано — «Admin» и «admin» разные логины.
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"Admin\",\"password\":\"secret123\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldFailStartWithoutAdminPassword() {
        Map<String, Object> properties = new LinkedHashMap<>();
        SecurityTestContainer.register((name, value) -> properties.put(name, value.get()), "it_auth_nopwd");
        properties.put("argent.auth.password-required", "true");
        properties.put("argent.auth.bootstrap-admin.password", "");

        Throwable thrown = catchThrowable(() -> new SpringApplicationBuilder(SecurityTestApplication.class)
                .web(WebApplicationType.NONE)
                .properties(properties)
                .run()
                .close());

        assertThat(thrown).isNotNull();
        assertThat(NestedExceptionUtils.getMostSpecificCause(thrown).getMessage())
                .contains("bootstrap-admin.password");
    }
}
