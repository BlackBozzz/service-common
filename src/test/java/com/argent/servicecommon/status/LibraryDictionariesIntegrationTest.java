package com.argent.servicecommon.status;

import com.argent.servicecommon.auth.entity.User;
import com.argent.servicecommon.auth.repository.UserRepository;
import com.argent.servicecommon.auth.repository.UserSessionRepository;
import com.argent.servicecommon.auth.service.TokenService;
import com.argent.servicecommon.security.SecurityTestApplication;
import com.argent.servicecommon.security.entity.Role;
import com.argent.servicecommon.security.entity.UserRole;
import com.argent.servicecommon.security.entity.UserRoleId;
import com.argent.servicecommon.security.repository.RoleRepository;
import com.argent.servicecommon.security.repository.UserRoleRepository;
import com.argent.servicecommon.security.SecurityTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Проверяет, что библиотечные справочники статусов и нумерации, зарегистрированные
 * миграцией V4, принимаются регистрацией как существующие и доступны через админку справочников.
 */
@SpringBootTest(classes = SecurityTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class LibraryDictionariesIntegrationTest {

    private static final List<String> CODES = List.of(
            "common_status_type", "common_status", "common_status_transition", "common_sequence");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SecurityTestContainer.register(registry, "it_libdict");
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TokenService tokenService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository userSessionRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleRepository userRoleRepository;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdRoles = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID userId : createdUsers) {
            userRoleRepository.deleteAll(userRoleRepository.findByIdUserId(userId));
            userSessionRepository.deleteAll(userSessionRepository.findAll().stream()
                    .filter(session -> session.getUserId().equals(userId))
                    .toList());
            userRepository.deleteById(userId);
        }
        createdRoles.forEach(roleRepository::deleteById);
        createdUsers.clear();
        createdRoles.clear();
    }

    @Test
    void shouldKeepMigrationRegistryRows() {
        // Регистрация не создаёт новых записей и не меняет существующие строки миграции.
        Integer count = jdbc.queryForObject(
                "select count(*) from common_dictionaries where code in (?, ?, ?, ?)",
                Integer.class, CODES.toArray());
        assertThat(count).isEqualTo(4);
    }

    @Test
    void shouldExposeLibraryDictionariesToAdmin() throws Exception {
        String token = adminToken();

        var list = mvc.perform(get("/api/argent-dictionary")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        for (String code : CODES) {
            list.andExpect(jsonPath("$[*].code", hasItem(code)));
        }

        for (String code : CODES) {
            mvc.perform(get("/api/argent-dictionary/" + code + "/schema")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void shouldDescribeStatusReferenceToStatusType() throws Exception {
        mvc.perform(get("/api/argent-dictionary/common_status/schema")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayField").value("name"))
                .andExpect(jsonPath("$.fields[?(@.name == 'statusType')].reference.dictionaryCode")
                        .value(hasItem("common_status_type")));
    }

    @Test
    void libraryDictionaryWriteRequiresGrant() throws Exception {
        String token = tokenWith("{\"tables\":{\"x\":[\"read\",\"write\"]}}");
        for (String code : List.of("common_status_transition", "common_sequence")) {
            mvc.perform(post("/api/argent-dictionary/" + code + "/records")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }
    }

    private String tokenWith(String grants) {
        User user = new User();
        user.setLogin("libdict-" + UUID.randomUUID());
        user.setDisplayName("Пользователь справочников");
        user = userRepository.save(user);
        createdUsers.add(user.getId());

        Role role = new Role();
        role.setCode("LIBDICT_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        role.setName("Роль справочников");
        role.setGrants(grants);
        role = roleRepository.save(role);
        createdRoles.add(role.getId());
        userRoleRepository.save(new UserRole(new UserRoleId(user.getId(), role.getId())));
        return tokenService.issue(user);
    }

    private String adminToken() throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"admin\",\"password\":\"test-admin-pass\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonMapper.shared().readTree(body).get("token").asString();
    }
}
