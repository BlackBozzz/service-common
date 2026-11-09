package com.argent.servicecommon.security;

import com.argent.servicecommon.auth.entity.User;
import com.argent.servicecommon.auth.repository.UserRepository;
import com.argent.servicecommon.auth.service.TokenService;
import com.argent.servicecommon.dictionary.entity.CommonDictionary;
import com.argent.servicecommon.dictionary.repository.CommonDictionaryRepository;
import com.argent.servicecommon.security.adminfixture.AdminCatalogItem;
import com.argent.servicecommon.security.entity.Role;
import com.argent.servicecommon.security.entity.UserRole;
import com.argent.servicecommon.security.entity.UserRoleId;
import com.argent.servicecommon.security.grants.GrantConstants;
import com.argent.servicecommon.security.repository.RoleRepository;
import com.argent.servicecommon.security.repository.UserRoleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Проверяет REST администрирования ролей и пользователей под действием {@code admin}: гранты ролей,
 * защиту системной роли, запрет эскалации, доминирование, «последнего администратора» и каталог.
 */
@SpringBootTest(
        classes = AdminControllersIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureMockMvc
class AdminControllersIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.shared();

    private static final List<String> TEST_LOGINS = List.of(
            "ivanov", "petrov", "sidorov", "limited", "target", "admin2", "super", "nobody");
    private static final List<String> TEST_ROLES = List.of(
            "MANAGER", "HELPER", "HELPER2", "LIMITED", "WIDE", "NARROW", "SUPER", "EDITOR");

    /** Ограниченный администратор: действие {@code admin} и чтение одной таблицы {@code a}. */
    private static final String LIMITED_GRANTS = "{\"tables\":{\"a\":[\"read\"]},\"actions\":[\"admin\"]}";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SecurityTestContainer.register(registry, "it_admin");
        registry.add("argent.auth.password-required", () -> "false");
        registry.add("argent.auth.bootstrap-admin.password", () -> "");
        registry.add("argent.auth.registration.enabled", () -> "false");
        registry.add("argent.security.actions", () -> "documents.approve");
    }

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleRepository userRoleRepository;
    @Autowired private TokenService tokenService;
    @Autowired private PasswordEncoder passwordEncoder;

    private User admin;
    private String adminToken;
    private String adminRoleName;
    private String adminRoleDescription;

    @BeforeEach
    void setUp() {
        admin = userRepository.findByLogin("admin").orElseThrow();
        adminToken = tokenService.issue(admin);
        Role adminRole = roleRepository.findByCode(GrantConstants.ADMIN_ROLE_CODE).orElseThrow();
        adminRoleName = adminRole.getName();
        adminRoleDescription = adminRole.getDescription();
    }

    @AfterEach
    void cleanUp() {
        TEST_LOGINS.forEach(login -> userRepository.findByLogin(login).ifPresent(userRepository::delete));
        TEST_ROLES.forEach(code -> roleRepository.findByCode(code).ifPresent(roleRepository::delete));
        // Восстанавливает администратора и системную роль, если тест их изменил или упал.
        User current = userRepository.findById(admin.getId()).orElseThrow();
        if (!current.isEnabled()) {
            current.setEnabled(true);
            userRepository.save(current);
        }
        Role adminRole = roleRepository.findByCode(GrantConstants.ADMIN_ROLE_CODE).orElseThrow();
        adminRole.setName(adminRoleName);
        adminRole.setDescription(adminRoleDescription);
        adminRole.setGrants(GrantConstants.ADMIN_GRANTS);
        roleRepository.save(adminRole);
        userRoleRepository.save(new UserRole(new UserRoleId(admin.getId(), adminRole.getId())));
    }

    @Test
    void shouldUpsertRoleWithGrants() throws Exception {
        String grants = "{\"tables\":{\"dict_x\":[\"write\",\"read\"],\"*\":[\"read\"]},"
                + "\"actions\":[\"admin\"]}";
        JsonNode created = body(putRole(adminToken, "MANAGER", "Руководитель", "Ведёт справочники", grants)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.code").value("MANAGER"))
                .andExpect(jsonPath("$.name").value("Руководитель"))
                .andExpect(jsonPath("$.description").value("Ведёт справочники"))
                .andExpect(jsonPath("$.system").value(false)));
        assertThat(created.get("grants")).isEqualTo(json(
                "{\"tables\":{\"*\":[\"read\"],\"dict_x\":[\"read\",\"write\"]},\"actions\":[\"admin\"]}"));

        JsonNode updated = body(putRole(adminToken, "MANAGER", "Начальник", null,
                "{\"tables\":{\"dict_y\":[\"read\"]}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Начальник")));
        assertThat(updated.get("id")).isEqualTo(created.get("id"));
        assertThat(updated.get("grants")).isEqualTo(json("{\"tables\":{\"dict_y\":[\"read\"]},\"actions\":[]}"));

        JsonNode fetched = body(mvc.perform(asAdmin(get("/api/roles/{code}", "MANAGER")))
                .andExpect(status().isOk()));
        assertThat(fetched).isEqualTo(updated);
        mvc.perform(asAdmin(get("/api/roles")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", hasItems(GrantConstants.ADMIN_ROLE_CODE, "MANAGER")));

        putRole(adminToken, "HELPER", "Помощник", null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grants.tables").isEmpty())
                .andExpect(jsonPath("$.grants.actions").isEmpty());

        User ivanov = userWithRoles("ivanov", "MANAGER");
        assertThat(ivanov.getId()).isNotNull();
        mvc.perform(asAdmin(delete("/api/roles/{code}", "MANAGER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Роль назначена пользователям"));
        mvc.perform(asAdmin(delete("/api/roles/{code}", "HELPER")))
                .andExpect(status().isNoContent());
        mvc.perform(asAdmin(get("/api/roles/{code}", "HELPER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldRejectInvalidGrants() throws Exception {
        putRole(adminToken, "HELPER", "Помощник", null, "{\"tables\":{\"Dict-X\":[\"read\"]}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("grants.tables"));
        putRole(adminToken, "HELPER", "Помощник", null, "{\"tables\":{\"dict_x\":[\"delete\"]}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("grants.tables"));
        putRole(adminToken, "HELPER", "Помощник", null, "{\"actions\":[\"Bad Action\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("grants.actions"));
        putRole(adminToken, "HELPER", "Помощник", null, "{\"foo\":1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("grants"))
                .andExpect(jsonPath("$.errors[0].message").value("Неизвестное поле грантов: foo"));
        putRole(adminToken, "HELPER", "Помощник", null, "\"{}\"")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("grants"));

        putRole(adminToken, "bad-code", "Помощник", null, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("code"))
                .andExpect(jsonPath("$.errors[0].message").value("Недопустимый код роли"));
        putRole(adminToken, "HELPER", " ", null, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));

        assertThat(roleRepository.findByCode("HELPER")).isEmpty();
    }

    @Test
    void shouldProtectSystemRole() throws Exception {
        mvc.perform(asAdmin(delete("/api/roles/{code}", GrantConstants.ADMIN_ROLE_CODE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Системную роль нельзя удалить"));

        JsonNode updated = body(putRole(adminToken, GrantConstants.ADMIN_ROLE_CODE, "Главные", "Все права", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.system").value(true))
                .andExpect(jsonPath("$.name").value("Главные"))
                .andExpect(jsonPath("$.description").value("Все права")));
        assertThat(updated.get("grants")).isEqualTo(json(GrantConstants.ADMIN_GRANTS));

        putRole(adminToken, GrantConstants.ADMIN_ROLE_CODE, "Главные", null, "{\"tables\":{\"a\":[\"read\"]}}")
                .andExpect(status().isOk());
        assertThat(json(roleRepository.findByCode(GrantConstants.ADMIN_ROLE_CODE).orElseThrow().getGrants()))
                .isEqualTo(json(GrantConstants.ADMIN_GRANTS));
    }

    @Test
    void shouldNotEscalateThroughRoleGrants() throws Exception {
        String limited = token(userWithRoles("limited", role("LIMITED", LIMITED_GRANTS)));

        putRole(limited, "HELPER", "Помощник", null, "{\"tables\":{\"b\":[\"write\"]}}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Нельзя выдать права, которых нет у вас"));
        putRole(limited, "HELPER", "Помощник", null, "{\"tables\":{\"*\":[\"read\"]}}")
                .andExpect(status().isForbidden());
        assertThat(roleRepository.findByCode("HELPER")).isEmpty();
        // Действие admin покрывает любые действия, но не таблицы.
        putRole(limited, "EDITOR", "Редактор", null,
                "{\"tables\":{\"b\":[\"read\"]},\"actions\":[\"documents.approve\"]}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Нельзя выдать права, которых нет у вас"));
        putRole(limited, "EDITOR", "Редактор", null, "{\"actions\":[\"documents.approve\"]}")
                .andExpect(status().isOk());
        // Bootstrap-администратор выдаёт действия приложения.
        putRole(adminToken, "HELPER", "Помощник", null, "{\"actions\":[\"documents.approve\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grants.actions", contains("documents.approve")));

        putRole(limited, "HELPER2", "Помощник", null, "{\"tables\":{\"a\":[\"read\"]}}")
                .andExpect(status().isOk());
        putRole(limited, "HELPER2", "Помощник", null, "{\"tables\":{\"a\":[\"read\",\"write\"]}}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Нельзя выдать права, которых нет у вас"));

        // Уже имеющиеся у роли гранты не проверяются: проверяются только добавленные.
        putRole(adminToken, "HELPER2", "Помощник", null, "{\"tables\":{\"a\":[\"read\"],\"c\":[\"write\"]}}")
                .andExpect(status().isOk());
        putRole(limited, "HELPER2", "Помощник 2", null, "{\"tables\":{\"c\":[\"write\"]}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Помощник 2"));
    }

    @Test
    void shouldNotEscalateThroughRoleAssignment() throws Exception {
        String limited = token(userWithRoles("limited", role("LIMITED", LIMITED_GRANTS)));
        role("WIDE", "{\"tables\":{\"b\":[\"write\"]}}");
        role("NARROW", "{\"tables\":{\"a\":[\"read\"]}}");
        User target = userWithRoles("target");

        mvc.perform(as(limited, put("/api/users/{id}/roles", target.getId()))
                        .content("{\"roleCodes\":[\"ADMIN\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Нельзя выдать роль с правами, которых нет у вас: ADMIN"));
        mvc.perform(as(limited, put("/api/users/{id}/roles", target.getId()))
                        .content("{\"roleCodes\":[\"NARROW\",\"WIDE\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Нельзя выдать роль с правами, которых нет у вас: WIDE"));
        assertThat(userRoleRepository.findByIdUserId(target.getId())).isEmpty();

        mvc.perform(as(limited, put("/api/users/{id}/roles", target.getId()))
                        .content("{\"roleCodes\":[\"NARROW\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", contains("NARROW")));
    }

    @Test
    void shouldNotManageUsersWithMorePermissions() throws Exception {
        User limitedUser = userWithRoles("limited", role("LIMITED", LIMITED_GRANTS));
        String limited = token(limitedUser);
        String message = "Нельзя управлять пользователем с большими правами";

        mvc.perform(as(limited, post("/api/users/{id}/password", admin.getId())).content("{\"password\":\"hacked1\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(message));
        mvc.perform(as(limited, put("/api/users/{id}", admin.getId()))
                        .content("{\"displayName\":\"Взломан\",\"enabled\":false}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(message));
        mvc.perform(as(limited, post("/api/users/{id}/revoke-sessions", admin.getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(message));
        mvc.perform(as(limited, put("/api/users/{id}/roles", admin.getId())).content("{\"roleCodes\":[\"ADMIN\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(message));
        User adminAfter = userRepository.findById(admin.getId()).orElseThrow();
        assertThat(adminAfter.isEnabled()).isTrue();
        assertThat(adminAfter.getDisplayName()).isEqualTo(admin.getDisplayName());

        // Над пользователем с правами не шире своих — можно.
        User target = userWithRoles("target", role("NARROW", "{\"tables\":{\"a\":[\"read\"]}}"));
        mvc.perform(as(limited, put("/api/users/{id}", target.getId()))
                        .content("{\"displayName\":\"Цель\",\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(as(limited, post("/api/users/{id}/revoke-sessions", target.getId())))
                .andExpect(status().isNoContent());
        // Над собой — всегда (смена пароля отзывает и собственные сессии).
        mvc.perform(as(limited, post("/api/users/{id}/password", limitedUser.getId()))
                        .content("{\"password\":\"own-pass\"}"))
                .andExpect(status().isNoContent());

        // Полный администратор управляет ограниченным.
        mvc.perform(asAdmin(post("/api/users/{id}/password", limitedUser.getId())).content("{\"password\":\"reset-1\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(asAdmin(put("/api/users/{id}/roles", limitedUser.getId())).content("{\"roleCodes\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", empty()));
    }

    @Test
    void shouldKeepAtLeastOneAdmin() throws Exception {
        String lastAdmin = "Нельзя оставить систему без активного администратора";

        mvc.perform(asAdmin(put("/api/users/{id}", admin.getId()))
                        .content("{\"displayName\":\"" + admin.getDisplayName() + "\",\"enabled\":false}"))
                .andExpect(status().isConflict());
        mvc.perform(asAdmin(put("/api/users/{id}/roles", admin.getId())).content("{\"roleCodes\":[]}"))
                .andExpect(status().isConflict());

        User secondAdmin = userWithRoles("admin2", GrantConstants.ADMIN_ROLE_CODE);
        mvc.perform(asAdmin(put("/api/users/{id}/roles", secondAdmin.getId())).content("{\"roleCodes\":[]}"))
                .andExpect(status().isOk());

        // SUPER — несистемная роль с правами администратора; её держатель отключает bootstrap-администратора
        // и остаётся единственным активным администратором.
        putRole(adminToken, "SUPER", "Все права", null, GrantConstants.ADMIN_GRANTS)
                .andExpect(status().isOk());
        User superUser = userWithRoles("super", "SUPER");
        String superToken = token(superUser);
        mvc.perform(as(superToken, put("/api/users/{id}", admin.getId()))
                        .content("{\"displayName\":\"" + admin.getDisplayName() + "\",\"enabled\":false}"))
                .andExpect(status().isOk());

        putRole(superToken, "SUPER", "Все права", null, "{\"tables\":{\"*\":[\"read\",\"write\"]}}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(lastAdmin));
        mvc.perform(as(superToken, put("/api/users/{id}/roles", superUser.getId())).content("{\"roleCodes\":[]}"))
                .andExpect(status().isConflict());
        mvc.perform(as(superToken, put("/api/users/{id}", superUser.getId()))
                        .content("{\"displayName\":\"Супер\",\"enabled\":false}"))
                .andExpect(status().isConflict());
        assertThat(json(roleRepository.findByCode("SUPER").orElseThrow().getGrants()))
                .isEqualTo(json(GrantConstants.ADMIN_GRANTS));

        // После возврата bootstrap-администратора действие можно снять с роли SUPER.
        mvc.perform(as(superToken, put("/api/users/{id}", admin.getId()))
                        .content("{\"displayName\":\"" + admin.getDisplayName() + "\",\"enabled\":true}"))
                .andExpect(status().isOk());
        putRole(adminToken, "SUPER", "Все права", null, "{\"tables\":{\"*\":[\"read\",\"write\"]}}")
                .andExpect(status().isOk());
    }

    @Test
    void shouldRevokeSessionsOnPasswordReset() throws Exception {
        User sidorov = userWithRoles("sidorov");
        String userToken = token(sidorov);
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk());

        mvc.perform(asAdmin(post("/api/users/{id}/revoke-sessions", sidorov.getId())))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isUnauthorized());

        String freshToken = token(sidorov);
        mvc.perform(asAdmin(post("/api/users/{id}/password", sidorov.getId())).content("{\"password\":\"secret1\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + freshToken))
                .andExpect(status().isUnauthorized());
        String hash = userRepository.findById(sidorov.getId()).orElseThrow().getPasswordHash();
        assertThat(hash).isNotEqualTo("secret1");
        assertThat(passwordEncoder.matches("secret1", hash)).isTrue();

        mvc.perform(asAdmin(post("/api/users/{id}/password", sidorov.getId())).content("{\"password\":\"123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
    }

    @Test
    void shouldListCatalog() throws Exception {
        JsonNode catalog = body(mvc.perform(asAdmin(get("/api/grants/catalog")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables", hasItem("admin_catalog_item")))
                .andExpect(jsonPath("$.actions", hasItems(GrantConstants.ADMIN_ACTION, "documents.approve"))));
        List<String> tables = strings(catalog.get("tables"));
        List<String> actions = strings(catalog.get("actions"));
        assertThat(tables).isSorted().doesNotHaveDuplicates();
        assertThat(actions).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void shouldForbidWithoutAdminAction() throws Exception {
        String editor = token(userWithRoles("nobody", role("EDITOR", "{\"tables\":{\"*\":[\"read\",\"write\"]}}")));

        for (MockHttpServletRequestBuilder request : List.of(
                get("/api/users"), get("/api/roles"), get("/api/roles/{code}", GrantConstants.ADMIN_ROLE_CODE),
                get("/api/grants/catalog"), delete("/api/roles/{code}", "EDITOR"),
                post("/api/users/{id}/revoke-sessions", admin.getId()))) {
            mvc.perform(as(editor, request)).andExpect(status().isForbidden());
        }
        putRole(editor, "HELPER", "Помощник", null, "{}").andExpect(status().isForbidden());
        mvc.perform(as(editor, post("/api/users")).content("{\"login\":\"ivanov\",\"displayName\":\"Иванов\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/roles")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/grants/catalog")).andExpect(status().isUnauthorized());
        assertThat(roleRepository.findByCode("HELPER")).isEmpty();
        assertThat(userRepository.findByLogin("ivanov")).isEmpty();
    }

    @Test
    void shouldCreateAndListUsers() throws Exception {
        mvc.perform(asAdmin(post("/api/users")).content("{\"login\":\"ivanov\",\"displayName\":\"Иванов Иван\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.login").value("ivanov"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.hasPassword").value(false))
                .andExpect(jsonPath("$.roles", empty()));
        mvc.perform(asAdmin(post("/api/users"))
                        .content("{\"login\":\"petrov\",\"displayName\":\"Петров\",\"password\":\"secret1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hasPassword").value(true));

        mvc.perform(asAdmin(get("/api/users")).param("search", "ива"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].login", contains("ivanov")))
                .andExpect(jsonPath("$.size").value(20));
        mvc.perform(asAdmin(get("/api/users")).param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.items[*].login", hasItems("admin", "ivanov", "petrov")));

        mvc.perform(asAdmin(post("/api/users")).content("{\"login\":\"ivanov\",\"displayName\":\"Другой\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("login"));
        mvc.perform(asAdmin(post("/api/users"))
                        .content("{\"login\":\"sidorov\",\"displayName\":\"Сидоров\",\"password\":\"123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
    }

    @Test
    void shouldAssignRolesAndRejectUnknown() throws Exception {
        role("HELPER", "{\"tables\":{\"a\":[\"read\"]}}");
        User petrov = userWithRoles("petrov");

        mvc.perform(asAdmin(put("/api/users/{id}/roles", petrov.getId()))
                        .content("{\"roleCodes\":[\"HELPER\",\"ADMIN\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", contains(GrantConstants.ADMIN_ROLE_CODE, "HELPER")));

        mvc.perform(asAdmin(put("/api/users/{id}/roles", petrov.getId())).content("{\"roleCodes\":[\"GHOST\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("roleCodes"))
                .andExpect(jsonPath("$.errors[0].message").value("Неизвестная роль: GHOST"));
        assertThat(userRoleRepository.findByIdUserId(petrov.getId())).hasSize(2);

        mvc.perform(asAdmin(put("/api/users/{id}/roles", petrov.getId())).content("{\"roleCodes\":[\"HELPER\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", contains("HELPER")));
        mvc.perform(asAdmin(put("/api/users/{id}/roles", UUID.randomUUID())).content("{\"roleCodes\":[]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldRequireRoleCodesField() throws Exception {
        // QA auth #2: тело без roleCodes (или с опечаткой в имени поля) не снимает роли молча.
        role("HELPER", "{\"tables\":{\"a\":[\"read\"]}}");
        User petrov = userWithRoles("petrov", "HELPER");

        for (String content : List.of("{}", "{\"roles\":[\"HELPER\"]}", "{\"roleCodes\":null}")) {
            mvc.perform(asAdmin(put("/api/users/{id}/roles", petrov.getId())).content(content))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("roleCodes"));
        }
        assertThat(userRoleRepository.findByIdUserId(petrov.getId())).hasSize(1);

        mvc.perform(asAdmin(put("/api/users/{id}/roles", petrov.getId())).content("{\"roleCodes\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", empty()));
    }

    @Test
    void shouldNotDeleteRoleWiderThanCaller() throws Exception {
        // QA auth #4: удалить роль можно только в пределах собственных прав, как и создать.
        String limited = token(userWithRoles("limited", role("LIMITED", LIMITED_GRANTS)));
        role("WIDE", "{\"tables\":{\"*\":[\"read\",\"write\"]}}");
        role("NARROW", "{\"tables\":{\"a\":[\"read\"]},\"actions\":[\"documents.approve\"]}");

        mvc.perform(as(limited, delete("/api/roles/{code}", "WIDE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Нельзя удалить роль с правами шире собственных"));
        assertThat(roleRepository.findByCode("WIDE")).isPresent();

        mvc.perform(as(limited, delete("/api/roles/{code}", "NARROW"))).andExpect(status().isNoContent());
        mvc.perform(asAdmin(delete("/api/roles/{code}", "WIDE"))).andExpect(status().isNoContent());
        assertThat(roleRepository.findByCode("WIDE")).isEmpty();
    }

    @Test
    void shouldLimitPasswordTo72Utf8Bytes() throws Exception {
        // QA auth #3: BCrypt принимает не больше 72 байт; 40 кириллических символов — 80 байт.
        String cyrillic = "п".repeat(40);
        User sidorov = userWithRoles("sidorov");

        mvc.perform(asAdmin(post("/api/users/{id}/password", sidorov.getId()))
                        .content("{\"password\":\"" + cyrillic + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"))
                .andExpect(jsonPath("$.errors[0].message").value("Пароль не должен превышать 72 байта в UTF-8"));
        mvc.perform(asAdmin(post("/api/users"))
                        .content("{\"login\":\"petrov\",\"displayName\":\"Петров\",\"password\":\"" + cyrillic + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
        assertThat(userRepository.findByLogin("petrov")).isEmpty();
        // 36 кириллических символов — ровно 72 байта.
        mvc.perform(asAdmin(post("/api/users/{id}/password", sidorov.getId()))
                        .content("{\"password\":\"" + "п".repeat(36) + "\"}"))
                .andExpect(status().isNoContent());
    }

    private ResultActions putRole(String token, String code, String name, String description, String grants)
            throws Exception {
        StringBuilder content = new StringBuilder("{\"name\":").append(JSON.writeValueAsString(name));
        if (description != null) {
            content.append(",\"description\":").append(JSON.writeValueAsString(description));
        }
        if (grants != null) {
            content.append(",\"grants\":").append(grants);
        }
        content.append('}');
        return mvc.perform(as(token, put("/api/roles/{code}", code)).content(content.toString()));
    }

    /** Создаёт роль напрямую в базе, минуя проверки REST. */
    private String role(String code, String grants) {
        Role role = new Role();
        role.setCode(code);
        role.setName(code);
        role.setGrants(grants);
        roleRepository.save(role);
        return code;
    }

    /** Создаёт пользователя напрямую в базе и назначает ему роли по кодам. */
    private User userWithRoles(String login, String... roleCodes) {
        User user = new User();
        user.setLogin(login);
        user.setDisplayName(login);
        User saved = userRepository.save(user);
        for (String code : roleCodes) {
            UUID roleId = roleRepository.findByCode(code).orElseThrow().getId();
            userRoleRepository.save(new UserRole(new UserRoleId(saved.getId(), roleId)));
        }
        return saved;
    }

    private String token(User user) {
        return tokenService.issue(userRepository.findById(user.getId()).orElseThrow());
    }

    private static JsonNode body(ResultActions result) throws Exception {
        return json(result.andReturn().getResponse().getContentAsString());
    }

    private static JsonNode json(String value) {
        return JSON.readTree(value);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.stringValue()));
        return values;
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return as(adminToken, request);
    }

    private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON);
    }

    /** Тестовая конфигурация с фикстурой-справочником {@link AdminCatalogItem} для каталога грантов. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {CommonDictionary.class, User.class, Role.class, AdminCatalogItem.class})
    @EnableJpaRepositories(basePackageClasses = {
            CommonDictionaryRepository.class, UserRepository.class, RoleRepository.class
    })
    static class TestApplication {
    }
}
