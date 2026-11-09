package com.argent.servicecommon.audit;

import com.argent.servicecommon.audit.audfixture.AudAddress;
import com.argent.servicecommon.audit.audfixture.AudLocation;
import com.argent.servicecommon.audit.audfixture.AudLongId;
import com.argent.servicecommon.audit.audfixture.AudNoTable;
import com.argent.servicecommon.audit.audfixture.AudOrder;
import com.argent.servicecommon.audit.audfixture.AudPlain;
import com.argent.servicecommon.audit.audfixture.AudPlace;
import com.argent.servicecommon.audit.audfixture.AudPoint;
import com.argent.servicecommon.audit.audfixture.AudRegion;
import com.argent.servicecommon.audit.audfixture.AudSecret;
import com.argent.servicecommon.audit.audfixture.AudUpper;
import com.argent.servicecommon.audit.entity.AuditEntry;
import com.argent.servicecommon.audit.repository.AuditEntryRepository;
import com.argent.servicecommon.auth.entity.User;
import com.argent.servicecommon.auth.repository.UserRepository;
import com.argent.servicecommon.auth.repository.UserSessionRepository;
import com.argent.servicecommon.auth.service.TokenService;
import com.argent.servicecommon.dictionary.entity.CommonDictionary;
import com.argent.servicecommon.dictionary.repository.CommonDictionaryRepository;
import com.argent.servicecommon.security.SecurityTestContainer;
import com.argent.servicecommon.security.entity.Role;
import com.argent.servicecommon.security.entity.UserRole;
import com.argent.servicecommon.security.entity.UserRoleId;
import com.argent.servicecommon.security.repository.RoleRepository;
import com.argent.servicecommon.security.repository.UserRoleRepository;
import com.argent.servicecommon.transfer.imp.AuditRecorder;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Аудит: запись INSERT/UPDATE/DELETE для сущностей с {@code @Audited}, маскирование
 * {@code @AuditIgnore}, автор изменения, ссылки как id, REST журнала и запись REPLACE импорта.
 */
@SpringBootTest(
        classes = AuditIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureMockMvc
class AuditIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SecurityTestContainer.register(registry, "it_audit");
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate tx;
    @Autowired private AuditEntryRepository auditRepository;
    @Autowired private AuditRecorder auditRecorder;
    @Autowired private TokenService tokenService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository userSessionRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleRepository userRoleRepository;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdRoles = new ArrayList<>();

    @BeforeEach
    void prepare() {
        jdbc.execute("create table if not exists aud_region (id bigserial primary key, name varchar(255))");
        jdbc.execute("create table if not exists aud_order (id uuid primary key, number varchar(255), "
                + "secret varchar(255), status varchar(20), region_id bigint references aud_region(id), "
                + "version bigint)");
        jdbc.execute("create table if not exists aud_plain (id uuid primary key, name varchar(255))");
        jdbc.execute("create table if not exists aud_no_table (id uuid primary key, name varchar(255))");
        jdbc.execute("create table if not exists aud_place (id uuid primary key, name varchar(255), "
                + "city varchar(255), pin varchar(255), region_id bigint references aud_region(id), "
                + "code varchar(255), label varchar(255), lat double precision, lon double precision)");
        jdbc.execute("create table if not exists aud_long_id (id text primary key, name varchar(255))");
        jdbc.execute("create table if not exists aud_upper (id uuid primary key, name varchar(255))");
        jdbc.update("delete from aud_place");
        jdbc.update("delete from aud_long_id");
        jdbc.update("delete from aud_upper");
        jdbc.update("delete from aud_no_table");
        jdbc.update("delete from aud_order");
        jdbc.update("delete from aud_region");
        jdbc.update("delete from aud_plain");
        jdbc.update("delete from common_audit_entry");
        SecurityContextHolder.clearContext();
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
    void shouldWriteInsertUpdateDelete() {
        UUID id = createOrder("A-1", AudOrder.Status.NEW, null);

        assertThat(entries()).singleElement().satisfies(entry -> {
            assertThat(entry.getEntityType()).isEqualTo("aud_order");
            assertThat(entry.getEntityId()).isEqualTo(id.toString());
            assertThat(entry.getAction()).isEqualTo("INSERT");
            assertThat(entry.getField()).isNull();
            assertThat(entry.getCreatedAt()).isNotNull();
        });

        updateOrder(id, order -> {
            order.setNumber("A-2");
            order.setStatus(AudOrder.Status.DONE);
        });
        assertThat(entries()).filteredOn(entry -> entry.getAction().equals("UPDATE"))
                .extracting(AuditEntry::getField, AuditEntry::getOldValue, AuditEntry::getNewValue)
                .containsExactlyInAnyOrder(tuple("number", "A-1", "A-2"), tuple("status", "NEW", "DONE"));
        assertThat(jdbc.queryForObject("select version from aud_order where id = ?", Long.class, id))
                .isEqualTo(1L);
        assertThat(entries()).noneMatch(entry -> "version".equals(entry.getField()));

        tx.executeWithoutResult(status -> entityManager.remove(entityManager.find(AudOrder.class, id)));
        assertThat(entries()).filteredOn(entry -> entry.getAction().equals("DELETE"))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.getEntityId()).isEqualTo(id.toString());
                    assertThat(entry.getField()).isNull();
                });
        assertThat(entries()).hasSize(4);
    }

    @Test
    void shouldNotAuditUnannotated() {
        UUID id = tx.execute(status -> {
            AudPlain plain = new AudPlain();
            plain.setName("один");
            entityManager.persist(plain);
            return plain.getId();
        });
        tx.executeWithoutResult(status -> entityManager.find(AudPlain.class, id).setName("два"));
        tx.executeWithoutResult(status -> entityManager.remove(entityManager.find(AudPlain.class, id)));
        createRegion("Не аудируется");

        assertThat(entries()).isEmpty();
    }

    @Test
    void shouldUsePhysicalTableNameWithoutTableAnnotation() {
        UUID id = tx.execute(status -> {
            AudNoTable entity = new AudNoTable();
            entity.setName("без @Table");
            entityManager.persist(entity);
            return entity.getId();
        });

        assertThat(entries()).singleElement().satisfies(entry -> {
            assertThat(entry.getEntityType()).isEqualTo("aud_no_table");
            assertThat(entry.getEntityId()).isEqualTo(id.toString());
            assertThat(entry.getAction()).isEqualTo("INSERT");
        });
    }

    @Test
    void shouldMaskIgnoredField() {
        UUID id = createOrder("M-1", AudOrder.Status.NEW, null);
        updateOrder(id, order -> order.setSecret("пароль"));

        assertThat(entries()).filteredOn(entry -> entry.getAction().equals("UPDATE"))
                .extracting(AuditEntry::getField, AuditEntry::getOldValue, AuditEntry::getNewValue)
                .containsExactly(tuple("secret", "***", "***"));
        assertThat(entries()).noneMatch(entry -> String.valueOf(entry.getOldValue()).contains("пароль")
                || String.valueOf(entry.getNewValue()).contains("пароль"));
    }

    @Test
    void shouldExpandEmbeddedAndMaskIgnoredSubfield() {
        // QA audit #1: @AuditIgnore внутри @Embeddable; toString() встроенного объекта в журнал не попадает.
        UUID id = tx.execute(status -> {
            AudPlace place = new AudPlace();
            place.setName("Склад");
            place.setAddress(new AudAddress("Тула", "PIN-1"));
            entityManager.persist(place);
            return place.getId();
        });
        tx.executeWithoutResult(status ->
                entityManager.find(AudPlace.class, id).setAddress(new AudAddress("Москва", "PIN-2")));

        assertThat(entries()).filteredOn(entry -> entry.getAction().equals("UPDATE"))
                .extracting(AuditEntry::getField, AuditEntry::getOldValue, AuditEntry::getNewValue)
                .containsExactlyInAnyOrder(tuple("address.city", "Тула", "Москва"),
                        tuple("address.pin", "***", "***"));
        assertThat(entries()).noneMatch(entry -> String.valueOf(entry.getOldValue()).contains("PIN")
                || String.valueOf(entry.getNewValue()).contains("PIN"));

        tx.executeWithoutResult(status -> entityManager.find(AudPlace.class, id).setAddress(null));
        assertThat(entries()).filteredOn(entry -> entry.getAction().equals("UPDATE"))
                .extracting(AuditEntry::getField, AuditEntry::getOldValue, AuditEntry::getNewValue)
                .containsExactlyInAnyOrder(tuple("address.city", "Тула", "Москва"),
                        tuple("address.pin", "***", "***"), tuple("address.city", "Москва", null),
                        tuple("address.pin", "***", "***"));
    }

    @Test
    void shouldMaskWholeIgnoredEmbeddedAndExpandNestedAndReferences() {
        Long north = createRegion("Север");
        Long south = createRegion("Юг");
        UUID id = tx.execute(status -> {
            AudPlace place = new AudPlace();
            place.setName("Склад");
            place.setAddress(new AudAddress("Тула", "PIN-1", entityManager.find(AudRegion.class, north)));
            place.setSecret(new AudSecret("CODE-1"));
            place.setLocation(new AudLocation("центр", new AudPoint(1.0, 2.0)));
            entityManager.persist(place);
            return place.getId();
        });
        tx.executeWithoutResult(status -> {
            AudPlace place = entityManager.find(AudPlace.class, id);
            // Ссылка внутри встроенного объекта пишется как id; @AuditIgnore на всём @Embedded скрывает подполя;
            // вложенный встроенный объект раскладывается до a.b.c.
            place.setAddress(new AudAddress("Тула", "PIN-1", entityManager.find(AudRegion.class, south)));
            place.setSecret(new AudSecret("CODE-2"));
            place.setLocation(new AudLocation("центр", new AudPoint(1.0, 3.5)));
        });

        assertThat(entries()).filteredOn(entry -> entry.getAction().equals("UPDATE"))
                .extracting(AuditEntry::getField, AuditEntry::getOldValue, AuditEntry::getNewValue)
                .containsExactlyInAnyOrder(
                        tuple("address.region", north.toString(), south.toString()),
                        tuple("secret.code", "***", "***"),
                        tuple("location.point.lon", "2.0", "3.5"));
        assertThat(entries()).noneMatch(entry -> String.valueOf(entry.getOldValue()).contains("CODE")
                || String.valueOf(entry.getNewValue()).contains("CODE"));
    }

    @Test
    void shouldAuditStringIdLongerThan255() {
        // QA audit #2: entity_id — text; длинный ключ не ломает бизнес-запись.
        String longId = "k".repeat(300);
        tx.executeWithoutResult(status -> {
            AudLongId entity = new AudLongId();
            entity.setId(longId);
            entity.setName("v");
            entityManager.persist(entity);
        });

        assertThat(entries()).extracting(AuditEntry::getEntityType, AuditEntry::getEntityId, AuditEntry::getAction)
                .containsExactly(tuple("aud_long_id", longId, "INSERT"));
    }

    @Test
    void shouldUsePhysicalTableNameForCamelCaseTableAnnotation() throws Exception {
        // QA audit #3: @Table(name = "AudUpper") → таблица aud_upper; журнал читается через REST.
        tx.executeWithoutResult(status -> {
            AudUpper entity = new AudUpper();
            entity.setName("u");
            entityManager.persist(entity);
        });

        assertThat(entries()).extracting(AuditEntry::getEntityType).containsExactly("aud_upper");
        String reader = tokenWith("{\"tables\":{\"aud_upper\":[\"read\"]}}");
        mvc.perform(get("/api/audit").param("entityType", "aud_upper")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + reader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void shouldRecordActor() {
        UUID actorId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(actorId.toString())
                .claim(TokenService.LOGIN_CLAIM, "auditor")
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("table:*:read")));
        UUID withActor = createOrder("U-1", AudOrder.Status.NEW, null);

        SecurityContextHolder.clearContext();
        UUID withoutActor = createOrder("U-2", AudOrder.Status.NEW, null);

        assertThat(entries())
                .extracting(AuditEntry::getEntityId, AuditEntry::getActorId, AuditEntry::getActorLogin)
                .containsExactlyInAnyOrder(
                        tuple(withActor.toString(), actorId, "auditor"),
                        tuple(withoutActor.toString(), null, null));
    }

    @Test
    void rollbackDropsAuditRows() {
        tx.executeWithoutResult(status -> {
            AudOrder order = new AudOrder();
            order.setNumber("R-1");
            order.setSecret("начальный");
            order.setStatus(AudOrder.Status.NEW);
            entityManager.persist(order);
            entityManager.flush();
            assertThat(jdbc.queryForObject(
                    "select count(*) from common_audit_entry where entity_type = 'aud_order'", Long.class))
                    .isEqualTo(1L);
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("select count(*) from aud_order", Long.class)).isZero();
        assertThat(entries()).isEmpty();
    }

    @Test
    void shouldRecordReferenceAsId() {
        Long north = createRegion("Северный");
        Long south = createRegion("Южный");
        UUID id = createOrder("R-1", AudOrder.Status.NEW, north);

        updateOrder(id, order -> order.setRegion(entityManager.getReference(AudRegion.class, south)));

        assertThat(entries()).filteredOn(entry -> entry.getAction().equals("UPDATE"))
                .extracting(AuditEntry::getField, AuditEntry::getOldValue, AuditEntry::getNewValue)
                .containsExactly(tuple("region", north.toString(), south.toString()));
    }

    @Test
    void shouldReadAuditByTableGrant() throws Exception {
        UUID first = createOrder("G-1", AudOrder.Status.NEW, null);
        UUID second = createOrder("G-2", AudOrder.Status.NEW, null);
        updateOrder(first, order -> order.setNumber("G-1a"));

        String reader = tokenWith("{\"tables\":{\"aud_order\":[\"read\"]}}");
        String stranger = tokenWith("{\"tables\":{\"aud_plain\":[\"read\"]}}");
        String admin = tokenWith("{\"actions\":[\"admin\"]}");

        mvc.perform(get("/api/audit").param("entityType", "aud_order")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + reader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items[0].action").value("UPDATE"))
                .andExpect(jsonPath("$.items[0].entityId").value(first.toString()))
                .andExpect(jsonPath("$.items[0].field").value("number"))
                .andExpect(jsonPath("$.items[0].oldValue").value("G-1"))
                .andExpect(jsonPath("$.items[0].newValue").value("G-1a"));
        mvc.perform(get("/api/audit").param("entityType", "aud_order")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + stranger))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/audit").param("entityType", "aud_order"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/audit").param("entityType", "aud_order")
                        .param("entityId", second.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].entityId").value(second.toString()))
                .andExpect(jsonPath("$.items[0].action").value("INSERT"));
        mvc.perform(get("/api/audit").param("entityType", "aud_order").param("action", "INSERT")
                        .param("size", "1").param("page", "1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + reader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[*].action", everyItem(is("INSERT"))));
        mvc.perform(get("/api/audit").param("entityType", "aud_order")
                        .param("from", "2000-01-01T00:00:00Z").param("to", "2000-01-02T00:00:00Z")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + reader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void shouldListAllTablesForAdminWithoutEntityType() throws Exception {
        createOrder("ALL-1", AudOrder.Status.NEW, null);
        tx.executeWithoutResult(status -> {
            AudNoTable entity = new AudNoTable();
            entity.setName("другая таблица");
            entityManager.persist(entity);
        });
        String admin = tokenWith("{\"actions\":[\"admin\"]}");

        mvc.perform(get("/api/audit").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.entityType == 'aud_order')]").isNotEmpty())
                .andExpect(jsonPath("$.items[?(@.entityType == 'aud_no_table')]").isNotEmpty());
    }

    @Test
    void shouldRejectMissingEntityTypeForNonAdmin() throws Exception {
        String reader = tokenWith("{\"tables\":{\"aud_order\":[\"read\"]}}");

        mvc.perform(get("/api/audit").header(HttpHeaders.AUTHORIZATION, "Bearer " + reader))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Укажите entityType"));
    }

    @Test
    void shouldRejectInvalidEntityType() throws Exception {
        String admin = tokenWith("{\"actions\":[\"admin\"]}");

        mvc.perform(get("/api/audit").param("entityType", "Bad Name")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Недопустимое имя таблицы: Bad Name"));
        mvc.perform(get("/api/audit").param("entityType", "")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldAllowWildcardRead() throws Exception {
        createOrder("W-1", AudOrder.Status.NEW, null);
        String wildcard = tokenWith("{\"tables\":{\"*\":[\"read\"]}}");

        mvc.perform(get("/api/audit").param("entityType", "aud_order")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + wildcard))
                .andExpect(status().isOk());
    }

    @Test
    void shouldDenyWriteOnlyGrant() throws Exception {
        String writer = tokenWith("{\"tables\":{\"aud_order\":[\"write\"]}}");

        mvc.perform(get("/api/audit").param("entityType", "aud_order")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + writer))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturnEmptyWhenFromAfterTo() throws Exception {
        createOrder("E-1", AudOrder.Status.NEW, null);
        String admin = tokenWith("{\"actions\":[\"admin\"]}");

        mvc.perform(get("/api/audit").param("from", "2030-01-01T00:00:00Z").param("to", "2020-01-01T00:00:00Z")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void shouldRecordReplaceFromImport() {
        tx.executeWithoutResult(status -> auditRecorder.recordReplace("aud_order", 3));

        assertThat(auditRecorder).isInstanceOf(DatabaseAuditRecorder.class);
        assertThat(entries()).singleElement().satisfies(entry -> {
            assertThat(entry.getEntityType()).isEqualTo("aud_order");
            assertThat(entry.getEntityId()).isNull();
            assertThat(entry.getAction()).isEqualTo("REPLACE");
            assertThat(entry.getNewValue()).isEqualTo("3");
        });
    }

    private List<AuditEntry> entries() {
        return auditRepository.findAll(Sort.by("createdAt"));
    }

    private Long createRegion(String name) {
        return tx.execute(status -> {
            AudRegion region = new AudRegion();
            region.setName(name);
            entityManager.persist(region);
            return region.getId();
        });
    }

    private UUID createOrder(String number, AudOrder.Status orderStatus, Long regionId) {
        return tx.execute(status -> {
            AudOrder order = new AudOrder();
            order.setNumber(number);
            order.setSecret("начальный");
            order.setStatus(orderStatus);
            if (regionId != null) {
                order.setRegion(entityManager.getReference(AudRegion.class, regionId));
            }
            entityManager.persist(order);
            return order.getId();
        });
    }

    private void updateOrder(UUID id, Consumer<AudOrder> change) {
        tx.executeWithoutResult(status -> change.accept(entityManager.find(AudOrder.class, id)));
    }

    private String tokenWith(String grants) {
        User user = new User();
        user.setLogin("audit-" + UUID.randomUUID());
        user.setDisplayName("Пользователь аудита");
        user = userRepository.save(user);
        createdUsers.add(user.getId());

        Role role = new Role();
        role.setCode("AUDIT_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        role.setName("Роль аудита");
        role.setGrants(grants);
        role = roleRepository.save(role);
        createdRoles.add(role.getId());
        userRoleRepository.save(new UserRole(new UserRoleId(user.getId(), role.getId())));
        return tokenService.issue(user);
    }

    /** Тестовая конфигурация с аудируемыми фикстурами. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {
            CommonDictionary.class, User.class, Role.class, AuditEntry.class, AudOrder.class
    })
    @EnableJpaRepositories(basePackageClasses = {
            CommonDictionaryRepository.class, UserRepository.class, RoleRepository.class,
            AuditEntryRepository.class
    })
    static class TestApplication {
    }
}
