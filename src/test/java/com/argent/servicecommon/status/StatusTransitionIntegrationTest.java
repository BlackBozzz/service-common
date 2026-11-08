package com.argent.servicecommon.status;

import com.argent.servicecommon.auth.entity.User;
import com.argent.servicecommon.auth.repository.UserRepository;
import com.argent.servicecommon.auth.repository.UserSessionRepository;
import com.argent.servicecommon.auth.service.TokenService;
import com.argent.servicecommon.security.SecurityTestApplication;
import com.argent.servicecommon.security.SecurityTestContainer;
import com.argent.servicecommon.security.entity.Role;
import com.argent.servicecommon.security.entity.UserRole;
import com.argent.servicecommon.security.entity.UserRoleId;
import com.argent.servicecommon.security.repository.RoleRepository;
import com.argent.servicecommon.security.repository.UserRoleRepository;
import com.argent.servicecommon.status.dto.CommonStatusDto;
import com.argent.servicecommon.status.entity.CommonStatus;
import com.argent.servicecommon.status.entity.CommonStatusTransition;
import com.argent.servicecommon.status.entity.CommonStatusType;
import com.argent.servicecommon.status.repository.CommonStatusRepository;
import com.argent.servicecommon.status.repository.CommonStatusTransitionRepository;
import com.argent.servicecommon.status.repository.CommonStatusTypeRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Статусная машина: проверка переходов по справочнику, требуемые действия и список доступных
 * текущему пользователю переходов через REST.
 */
@SpringBootTest(
        classes = {SecurityTestApplication.class, StatusTransitionIntegrationTest.CheckController.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureMockMvc
class StatusTransitionIntegrationTest {

    private static final String CLERK = "{\"tables\":{\"*\":[\"read\"]}}";
    private static final String APPROVER = "{\"actions\":[\"orders.approve\"]}";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SecurityTestContainer.register(registry, "it_status");
    }

    @Autowired private MockMvc mvc;
    @Autowired private StatusTransitionService service;
    @Autowired private CommonStatusTypeRepository typeRepository;
    @Autowired private CommonStatusRepository statusRepository;
    @Autowired private CommonStatusTransitionRepository transitionRepository;
    @Autowired private TokenService tokenService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository userSessionRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleRepository userRoleRepository;
    @Autowired private TransactionTemplate transactions;
    @Autowired private JdbcTemplate jdbc;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdRoles = new ArrayList<>();

    @BeforeEach
    void seed() {
        transitionRepository.deleteAllInBatch();
        statusRepository.deleteAllInBatch();
        typeRepository.deleteAllInBatch();

        CommonStatusType type = new CommonStatusType();
        type.setCode("ORDER");
        type.setName("Заказ");
        type = typeRepository.save(type);

        CommonStatus created = saveStatus(type, "NEW", "Новый", 1, true, false);
        CommonStatus review = saveStatus(type, "REVIEW", "На проверке", 2, false, false);
        CommonStatus done = saveStatus(type, "DONE", "Выполнен", 3, false, true);

        transition(type, null, created, null);
        transition(type, created, review, null);
        transition(type, review, done, "orders.approve");
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
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
    void shouldAllowConfiguredTransition() throws Exception {
        actAs("table:*:read");
        assertThatCode(() -> service.assertAllowed("ORDER", "NEW", "REVIEW")).doesNotThrowAnyException();

        mvc.perform(post("/test/status/check").param("type", "ORDER").param("from", "NEW").param("to", "REVIEW")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWith(CLERK)))
                .andExpect(status().isOk());
    }

    @Test
    void shouldRejectUnknownTransition() throws Exception {
        actAs("table:*:read");
        assertThatThrownBy(() -> service.assertAllowed("ORDER", "NEW", "DONE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Переход NEW→DONE не предусмотрен");
        assertThatThrownBy(() -> service.assertAllowed("ORDER", null, "REVIEW"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Переход (создание)→REVIEW не предусмотрен");

        mvc.perform(post("/test/status/check").param("type", "ORDER").param("from", "NEW").param("to", "DONE")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWith(CLERK)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Переход NEW→DONE не предусмотрен"));
    }

    @Test
    void shouldRequireActionForTransition() throws Exception {
        actAs("table:*:read");
        assertThatThrownBy(() -> service.assertAllowed("ORDER", "REVIEW", "DONE"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Для перехода требуется действие orders.approve");

        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> service.assertAllowed("ORDER", "REVIEW", "DONE"))
                .isInstanceOf(AccessDeniedException.class);

        actAs("action:orders.approve");
        assertThatCode(() -> service.assertAllowed("ORDER", "REVIEW", "DONE")).doesNotThrowAnyException();

        mvc.perform(post("/test/status/check").param("type", "ORDER").param("from", "REVIEW").param("to", "DONE")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWith(CLERK)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Для перехода требуется действие orders.approve"));
        mvc.perform(post("/test/status/check").param("type", "ORDER").param("from", "REVIEW").param("to", "DONE")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWith(APPROVER)))
                .andExpect(status().isOk());
    }

    @Test
    void shouldListAllowedForCurrentUser() throws Exception {
        String clerk = tokenWith(CLERK);
        String approver = tokenWith(APPROVER);

        mvc.perform(get("/api/status/ORDER/transitions").param("from", "REVIEW")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clerk))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", empty()));
        mvc.perform(get("/api/status/ORDER/transitions").param("from", "REVIEW")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + approver))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", contains("DONE")))
                .andExpect(jsonPath("$[0].finalStatus").value(true))
                .andExpect(jsonPath("$[0].statusType.code").value("ORDER"));
        mvc.perform(get("/api/status/ORDER/transitions").param("from", "NEW")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clerk))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", contains("REVIEW")));
        mvc.perform(get("/api/status/ORDER/transitions").param("from", "NEW"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldAllowInitialTransitionWithNullFrom() throws Exception {
        actAs("table:*:read");
        assertThatCode(() -> service.assertAllowed("ORDER", null, "NEW")).doesNotThrowAnyException();

        mvc.perform(get("/api/status/ORDER/transitions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWith(CLERK)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", contains("NEW")))
                .andExpect(jsonPath("$[0].initialStatus").value(true));
    }

    @Test
    void shouldReturn404ForUnknownType() throws Exception {
        actAs("table:*:read");
        assertThatThrownBy(() -> service.assertAllowed("MISSING", null, "NEW"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("Тип статуса не найден: MISSING");
        assertThatThrownBy(() -> service.assertAllowed("ORDER", "NEW", "LOST"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("Статус не найден: LOST");

        String clerk = tokenWith(CLERK);
        mvc.perform(get("/api/status/MISSING/transitions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clerk))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Тип статуса не найден: MISSING"));
        mvc.perform(get("/api/status/ORDER/transitions").param("from", "LOST")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + clerk))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Статус не найден: LOST"));
    }

    @Test
    void callerMayCatchRejectedTransitionAndCommit() {
        actAs("table:*:read");
        transactions.executeWithoutResult(status -> {
            assertThatThrownBy(() -> service.assertAllowed("ORDER", "NEW", "DONE"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> service.assertAllowed("ORDER", "NEW", "LOST"))
                    .isInstanceOf(EntityNotFoundException.class);
            assertThatThrownBy(() -> service.assertAllowed("ORDER", "REVIEW", "DONE"))
                    .isInstanceOf(AccessDeniedException.class);
            CommonStatusType other = new CommonStatusType();
            other.setCode("INVOICE");
            other.setName("Счёт");
            typeRepository.save(other);
        });

        assertThat(typeRepository.findByCode("INVOICE")).isPresent();
    }

    @Test
    void crossTypeTransitionIsIgnored() throws Exception {
        CommonStatusType orderType = typeRepository.findByCode("ORDER").orElseThrow();
        CommonStatusType other = new CommonStatusType();
        other.setCode("INVOICE");
        other.setName("Счёт");
        other = typeRepository.save(other);
        saveStatus(orderType, "ARCHIVED", "В архиве", 4, false, true);
        CommonStatus foreign = saveStatus(other, "ARCHIVED", "В архиве", 1, false, true);
        CommonStatus fromNew = statusRepository.findByStatusTypeIdAndCode(orderType.getId(), "NEW").orElseThrow();
        transition(orderType, fromNew, foreign, null);

        actAs("table:*:read");
        assertThatThrownBy(() -> service.assertAllowed("ORDER", "NEW", "ARCHIVED"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Переход NEW→ARCHIVED не предусмотрен");
        assertThat(service.allowedFrom("ORDER", "NEW")).extracting(CommonStatusDto::code).containsExactly("REVIEW");

        mvc.perform(get("/api/status/ORDER/transitions").param("from", "NEW")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenWith(CLERK)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", contains("REVIEW")));
    }

    @Test
    void adminPassesRequiredAction() throws Exception {
        actAs("action:admin");
        assertThatCode(() -> service.assertAllowed("ORDER", "REVIEW", "DONE")).doesNotThrowAnyException();

        String admin = tokenWith("{\"actions\":[\"admin\"]}");
        mvc.perform(post("/test/status/check").param("type", "ORDER").param("from", "REVIEW").param("to", "DONE")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/status/ORDER/transitions").param("from", "REVIEW")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", contains("DONE")));
        mvc.perform(get("/api/status/ORDER/transitions").param("from", "")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", contains("NEW")));
    }

    @Test
    void adminEditOfTransitionShouldBeAudited() throws Exception {
        CommonStatusTransition transition = transitionRepository.findAll().stream()
                .filter(candidate -> "orders.approve".equals(candidate.getRequiredAction()))
                .findFirst().orElseThrow();
        String id = transition.getId().toString();
        jdbc.update("DELETE FROM common_audit_entry");
        String token = adminToken();
        String body = mvc.perform(get("/api/argent-dictionary/common_status_transition/records/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        ObjectNode record = (ObjectNode) JsonMapper.shared().readTree(body);
        record.put("requiredAction", "orders.sign");

        mvc.perform(put("/api/argent-dictionary/common_status_transition/records/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(record.toString()))
                .andExpect(status().isOk());

        List<java.util.Map<String, Object>> rows = jdbc.queryForList("SELECT entity_type, action, field, old_value,"
                + " new_value, actor_login FROM common_audit_entry WHERE entity_id = ?", id);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("entity_type", "common_status_transition")
                    .containsEntry("action", "UPDATE")
                    .containsEntry("field", "requiredAction")
                    .containsEntry("old_value", "orders.approve")
                    .containsEntry("new_value", "orders.sign")
                    .containsEntry("actor_login", "admin");
        });
    }

    @Test
    void actorFilledOverHttp() throws Exception {
        String id = transitionRepository.findAll().stream()
                .filter(candidate -> "orders.approve".equals(candidate.getRequiredAction()))
                .findFirst().orElseThrow().getId().toString();
        String body = mvc.perform(get("/api/argent-dictionary/common_status_transition/records/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        ObjectNode record = (ObjectNode) JsonMapper.shared().readTree(body);
        record.put("requiredAction", "orders.sign");
        jdbc.update("DELETE FROM common_audit_entry");

        String editor = tokenWith("{\"tables\":{\"common_status_transition\":[\"read\",\"write\"]}}");
        String login = userRepository.findById(createdUsers.getLast()).orElseThrow().getLogin();
        mvc.perform(put("/api/argent-dictionary/common_status_transition/records/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(record.toString()))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForList("SELECT action, actor_login, field, old_value, new_value FROM common_audit_entry WHERE entity_id = ?", id))
                .singleElement().satisfies(row -> assertThat(row)
                        .containsEntry("action", "UPDATE").containsEntry("actor_login", login)
                        .containsEntry("field", "requiredAction")
                        .containsEntry("old_value", "orders.approve")
                        .containsEntry("new_value", "orders.sign"));
    }

    private String adminToken() throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"admin\",\"password\":\"test-admin-pass\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonMapper.shared().readTree(body).get("token").asString();
    }

    private CommonStatus saveStatus(CommonStatusType type, String code, String name, int sortOrder,
                                boolean initial, boolean last) {
        CommonStatus entity = new CommonStatus();
        entity.setStatusType(type);
        entity.setCode(code);
        entity.setName(name);
        entity.setSortOrder(sortOrder);
        entity.setInitialStatus(initial);
        entity.setFinalStatus(last);
        return statusRepository.save(entity);
    }

    private void transition(CommonStatusType type, CommonStatus from, CommonStatus to, String requiredAction) {
        CommonStatusTransition transition = new CommonStatusTransition();
        transition.setStatusType(type);
        transition.setFromStatus(from);
        transition.setToStatus(to);
        transition.setRequiredAction(requiredAction);
        transitionRepository.save(transition);
    }

    /** Ставит в контекст безопасности пользователя с указанными authorities для проверок на уровне сервиса. */
    private static void actAs(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                UUID.randomUUID().toString(), null, AuthorityUtils.createAuthorityList(authorities)));
    }

    private String tokenWith(String grants) {
        User user = new User();
        user.setLogin("status-" + UUID.randomUUID());
        user.setDisplayName("Пользователь статусов");
        user = userRepository.save(user);
        createdUsers.add(user.getId());

        Role role = new Role();
        role.setCode("STATUS_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        role.setName("Роль статусов");
        role.setGrants(grants);
        role = roleRepository.save(role);
        createdRoles.add(role.getId());
        userRoleRepository.save(new UserRole(new UserRoleId(user.getId(), role.getId())));
        return tokenService.issue(user);
    }

    /** Тестовая точка входа, через которую «приложение» вызывает проверку перехода. */
    @RestController
    static class CheckController {

        private final StatusTransitionService service;

        CheckController(StatusTransitionService service) {
            this.service = service;
        }

        @PostMapping("/test/status/check")
        void check(@RequestParam("type") String type, @RequestParam(name = "from", required = false) String from,
                   @RequestParam("to") String to) {
            service.assertAllowed(type, from, to);
        }
    }
}
