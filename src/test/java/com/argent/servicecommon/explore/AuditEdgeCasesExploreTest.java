package com.argent.servicecommon.explore;

import com.argent.servicecommon.attachment.AttachmentService;
import com.argent.servicecommon.attachment.dto.AttachmentDto;
import com.argent.servicecommon.attachment.entity.Attachment;
import com.argent.servicecommon.attachment.repository.AttachmentRepository;
import com.argent.servicecommon.audit.AuditIgnore;
import com.argent.servicecommon.audit.AuditWriter;
import com.argent.servicecommon.audit.Audited;
import com.argent.servicecommon.audit.audfixture.AudOrder;
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
import com.argent.servicecommon.sequence.entity.CommonSequence;
import com.argent.servicecommon.sequence.repository.CommonSequenceRepository;
import com.argent.servicecommon.status.entity.CommonStatus;
import com.argent.servicecommon.status.repository.CommonStatusRepository;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.SessionFactory;
import org.hibernate.StatelessSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypesScanner;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Исследовательские пробы аудита (QA, одноразовый класс). */
@SpringBootTest(classes = AuditEdgeCasesExploreTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class AuditEdgeCasesExploreTest {

    private static final String SCHEMA = "it_explore_audit";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SecurityTestContainer.register(registry, SCHEMA);
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;
    @Autowired private TransactionTemplate tx;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private AuditEntryRepository auditRepository;
    @Autowired private AuditWriter auditWriter;
    @Autowired private AttachmentService attachmentService;
    @Autowired private TokenService tokenService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository userSessionRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleRepository userRoleRepository;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdRoles = new ArrayList<>();

    @BeforeEach
    void prepare() {
        jdbc.execute("create table if not exists aux_item (id uuid primary key, name varchar(255), "
                + "secret varchar(255), blob bytea, day date, amount numeric(12,2), at timestamptz, "
                + "kind varchar(20), city varchar(255), pin varchar(255), lat double precision, "
                + "lon double precision, long_text text, version bigint)");
        jdbc.execute("create table if not exists aux_item_tag (item_id uuid references aux_item(id), "
                + "tag varchar(255))");
        jdbc.execute("create table if not exists aux_parent (id bigserial primary key, name varchar(255))");
        jdbc.execute("create table if not exists aux_child (id bigserial primary key, name varchar(255), "
                + "parent_id bigint references aux_parent(id))");
        jdbc.execute("create table if not exists aux_comp (a varchar(200), b varchar(200), val varchar(255), "
                + "primary key (a, b))");
        jdbc.execute("create table if not exists aux_str (id text primary key, val varchar(255))");
        jdbc.execute("create table if not exists aux_upper (id uuid primary key, name varchar(255))");
        jdbc.execute("create table if not exists aux_animal (id uuid primary key, kind varchar(20), "
                + "name varchar(255), bark varchar(255))");
        jdbc.execute("create schema if not exists it_explore_audit_other");
        jdbc.execute("create table if not exists it_explore_audit_other.aux_item (id uuid primary key, "
                + "name varchar(255))");
        cleanTables();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        cleanTables();
        jdbc.update("delete from common_sequence where code like 'AUXQA%'");
        jdbc.update("delete from common_attachment");
        for (UUID userId : createdUsers) {
            userRoleRepository.deleteAll(userRoleRepository.findByIdUserId(userId));
            userSessionRepository.deleteAll(userSessionRepository.findAll().stream()
                    .filter(session -> session.getUserId().equals(userId)).toList());
            userRepository.deleteById(userId);
        }
        createdRoles.forEach(roleRepository::deleteById);
        createdUsers.clear();
        createdRoles.clear();
        jdbc.update("delete from common_audit_entry");
    }

    private void cleanTables() {
        jdbc.update("delete from aux_item_tag");
        jdbc.update("delete from aux_item");
        jdbc.update("delete from aux_child");
        jdbc.update("delete from aux_parent");
        jdbc.update("delete from aux_comp");
        jdbc.update("delete from aux_str");
        jdbc.update("delete from aux_upper");
        jdbc.update("delete from aux_animal");
        jdbc.update("delete from it_explore_audit_other.aux_item");
        jdbc.update("delete from common_audit_entry");
    }

    // ---------------------------------------------------------------- listener

    @Test
    void p01_updateWithoutChangeWritesNothing() {
        UUID id = createItem();
        updateItem(id, item -> item.setName(item.getName()));
        assertThat(updates()).isEmpty();
    }

    @Test
    void p02_onlyIgnoredFieldChangedWritesMaskedRow() {
        UUID id = createItem();
        updateItem(id, item -> item.setSecret("новый-секрет"));
        assertThat(updates()).extracting(AuditEntry::getField, AuditEntry::getOldValue, AuditEntry::getNewValue)
                .containsExactly(tuple("secret", "***", "***"));
    }

    @Test
    void p03_nullTransitions() {
        UUID id = createItem();
        updateItem(id, item -> item.setName(null));
        updateItem(id, item -> item.setName("снова"));
        assertThat(updates()).extracting(AuditEntry::getOldValue, AuditEntry::getNewValue)
                .containsExactlyInAnyOrder(tuple("item", null), tuple(null, "снова"));
    }

    @Test
    void p04_twiceInOneTransactionWithoutFlushIsOneRow() {
        UUID id = createItem();
        updateItem(id, item -> {
            item.setName("x1");
            item.setName("x2");
        });
        assertThat(updates()).extracting(AuditEntry::getOldValue, AuditEntry::getNewValue)
                .containsExactly(tuple("item", "x2"));
    }

    @Test
    void p05_twiceWithFlushIsTwoRows() {
        UUID id = createItem();
        updateItem(id, item -> {
            item.setName("x1");
            em.flush();
            item.setName("x2");
        });
        assertThat(updates()).extracting(AuditEntry::getOldValue, AuditEntry::getNewValue)
                .containsExactlyInAnyOrder(tuple("item", "x1"), tuple("x1", "x2"));
    }

    @Test
    void p06_changedAndRevertedWritesNothing() {
        UUID id = createItem();
        updateItem(id, item -> {
            item.setName("tmp");
            item.setName("item");
        });
        assertThat(updates()).isEmpty();
    }

    @Test
    void p07_mergeDetached() {
        UUID id = createItem();
        AuxItem detached = tx.execute(s -> em.find(AuxItem.class, id));
        detached.setName("merged");
        tx.executeWithoutResult(s -> em.merge(detached));
        assertThat(updates()).extracting(AuditEntry::getField, AuditEntry::getOldValue, AuditEntry::getNewValue)
                .containsExactly(tuple("name", "item", "merged"));
    }

    @Test
    void p08_bulkJpqlNotAudited_documented() {
        UUID id = createItem();
        tx.executeWithoutResult(s -> em.createQuery("update " + AuxItem.class.getName() + " i set i.name = 'bulk' where i.id = :id")
                .setParameter("id", id).executeUpdate());
        assertThat(updates()).isEmpty();
    }

    @Test
    void p09_cascadeDeleteWritesChildDeletes() {
        Long parentId = tx.execute(s -> {
            AuxParent parent = new AuxParent();
            parent.setName("p");
            for (int i = 0; i < 2; i++) {
                AuxChild child = new AuxChild();
                child.setName("c" + i);
                child.setParent(parent);
                parent.getChildren().add(child);
            }
            em.persist(parent);
            return parent.getId();
        });
        jdbc.update("delete from common_audit_entry");
        tx.executeWithoutResult(s -> em.remove(em.find(AuxParent.class, parentId)));
        assertThat(all()).extracting(AuditEntry::getEntityType, AuditEntry::getAction)
                .containsExactlyInAnyOrder(tuple("aux_parent", "DELETE"), tuple("aux_child", "DELETE"),
                        tuple("aux_child", "DELETE"));
    }

    @Test
    void p10_orphanRemovalNoParentNoise() {
        Long parentId = tx.execute(s -> {
            AuxParent parent = new AuxParent();
            parent.setName("p");
            AuxChild child = new AuxChild();
            child.setName("c");
            child.setParent(parent);
            parent.getChildren().add(child);
            em.persist(parent);
            return parent.getId();
        });
        jdbc.update("delete from common_audit_entry");
        tx.executeWithoutResult(s -> em.find(AuxParent.class, parentId).getChildren().clear());
        assertThat(all()).extracting(AuditEntry::getEntityType, AuditEntry::getAction)
                .containsExactly(tuple("aux_child", "DELETE"));
    }

    @Test
    void p11_elementCollectionOnlyChangeNoNoise() {
        UUID id = createItem();
        updateItem(id, item -> item.getTags().add("новый-тег"));
        assertThat(updates()).as("изменение только @ElementCollection версионируемой сущности")
                .extracting(AuditEntry::getField, AuditEntry::getOldValue, AuditEntry::getNewValue)
                .isEmpty();
    }

    @Test
    void p12_embeddedIgnoredInnerFieldNotLeaked() {
        UUID id = createItem();
        updateItem(id, item -> item.setAddress(new AuxAddress("Москва", "PIN-SECRET-2")));
        // QA audit #1: встроенный объект раскладывается на подполя, @AuditIgnore на подполе → ***.
        assertThat(updates())
                .extracting(AuditEntry::getField, AuditEntry::getOldValue, AuditEntry::getNewValue)
                .containsExactlyInAnyOrder(
                        tuple("address.city", "Тула", "Москва"),
                        tuple("address.pin", "***", "***"));
        // Изменилось только скрытое подполе — одна строка с маской.
        updateItem(id, item -> item.setAddress(new AuxAddress("Москва", "PIN-SECRET-3")));
        assertThat(updates()).extracting(AuditEntry::getField, AuditEntry::getOldValue, AuditEntry::getNewValue)
                .contains(tuple("address.pin", "***", "***"))
                .noneMatch(t -> String.valueOf(t).contains("PIN-SECRET"));
    }

    @Test
    void p13_embeddedWithoutToStringIsReadable() {
        UUID id = createItem();
        updateItem(id, item -> item.setGeo(new AuxGeo(1.0, 2.0)));
        assertThat(updates())
                .extracting(AuditEntry::getField, AuditEntry::getOldValue, AuditEntry::getNewValue)
                .containsExactlyInAnyOrder(tuple("geo.lat", "0.0", "1.0"), tuple("geo.lon", "0.0", "2.0"));
    }

    @Test
    void p14_scalarFormatting() {
        UUID id = createItem();
        updateItem(id, item -> {
            item.setKind(Kind.B);
            item.setDay(LocalDate.of(2026, 11, 9));
            item.setAmount(new BigDecimal("12.50"));
            item.setAt(Instant.parse("2026-11-09T10:00:00Z"));
        });
        Map<String, String> byField = new java.util.HashMap<>();
        updates().forEach(e -> byField.put(e.getField(), e.getNewValue()));
        assertThat(byField).containsEntry("kind", "B").containsEntry("day", "2026-11-09")
                .containsEntry("amount", "12.50").containsEntry("at", "2026-11-09T10:00:00Z");
    }

    @Test
    @Disabled("QA: audit #5, отложено")
    void p15_byteArrayRenderedMeaningfully() {
        UUID id = createItem();
        updateItem(id, item -> item.setBlob(new byte[]{9, 9, 9}));
        assertThat(updates()).singleElement().satisfies(e ->
                assertThat(e.getNewValue()).doesNotStartWith("[B@"));
    }

    @Test
    void p16_veryLongValueStoredFully() {
        UUID id = createItem();
        String big = "я".repeat(100_000);
        updateItem(id, item -> item.setLongText(big));
        assertThat(updates()).singleElement().satisfies(e -> assertThat(e.getNewValue()).hasSize(100_000));
    }

    @Test
    void p17_longIdentityId() {
        Long id = tx.execute(s -> {
            AuxParent parent = new AuxParent();
            parent.setName("p");
            em.persist(parent);
            return parent.getId();
        });
        assertThat(all()).singleElement().satisfies(e -> assertThat(e.getEntityId()).isEqualTo(id.toString()));
    }

    @Test
    @Disabled("QA: audit #4, отложено")
    void p18_compositeIdReadable() {
        tx.executeWithoutResult(s -> {
            AuxComp comp = new AuxComp();
            comp.setId(new AuxCompKey("A1", "B1"));
            comp.setVal("v");
            em.persist(comp);
        });
        assertThat(all()).singleElement().satisfies(e ->
                assertThat(e.getEntityId()).as("entity_id составного ключа").contains("A1").contains("B1"));
    }

    @Test
    void p19_stringIdLongerThan255DoesNotBreakBusinessWrite() {
        String longId = "k".repeat(300);
        assertThatCode(() -> tx.executeWithoutResult(s -> {
            AuxStr str = new AuxStr();
            str.setId(longId);
            str.setVal("v");
            em.persist(str);
        })).as("бизнес-вставка с id > 255 символов").doesNotThrowAnyException();
        assertThat(all()).extracting(AuditEntry::getAction, AuditEntry::getEntityId)
                .containsExactly(tuple("INSERT", longId));
    }

    @Test
    void p20_uppercaseTableNameReadableViaRest() throws Exception {
        tx.executeWithoutResult(s -> {
            AuxUpper upper = new AuxUpper();
            upper.setName("u");
            em.persist(upper);
        });
        String recorded = all().get(0).getEntityType();
        String admin = tokenWith("{\"actions\":[\"admin\"]}");
        mvc.perform(get("/api/audit").param("entityType", recorded).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk());
        assertThat(recorded).as("entity_type = физическое имя таблицы").isEqualTo("aux_upper");
    }

    @Test
    @Disabled("QA: audit #9, отложено")
    void p21_sameTableNameInOtherSchemaDistinguishable() {
        tx.executeWithoutResult(s -> {
            AuxOther other = new AuxOther();
            other.setName("o");
            em.persist(other);
        });
        createItem();
        assertThat(all()).extracting(AuditEntry::getEntityType).doesNotHaveDuplicates();
    }

    @Test
    void p22_versionNotWritten() {
        UUID id = createItem();
        updateItem(id, item -> item.setName("v2"));
        assertThat(updates()).extracting(AuditEntry::getField).containsExactly("name");
    }

    @Test
    void p23_singleTableSubclassInheritsAudited() {
        UUID id = tx.execute(s -> {
            AuxDog dog = new AuxDog();
            dog.setName("rex");
            dog.setBark("woof");
            em.persist(dog);
            return dog.getId();
        });
        tx.executeWithoutResult(s -> em.find(AuxDog.class, id).setBark("WOOF"));
        assertThat(all()).extracting(AuditEntry::getEntityType, AuditEntry::getAction, AuditEntry::getField)
                .containsExactlyInAnyOrder(tuple("aux_animal", "INSERT", null), tuple("aux_animal", "UPDATE", "bark"));
    }

    @Test
    void p24_rollbackDropsRows() {
        tx.executeWithoutResult(s -> {
            em.persist(newItem());
            em.flush();
            s.setRollbackOnly();
        });
        assertThat(all()).isEmpty();
    }

    @Test
    void p25_requiresNewInnerSurvivesOuterRollback() {
        TransactionTemplate requiresNew = new TransactionTemplate(txManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        AtomicReference<UUID> inner = new AtomicReference<>();
        tx.executeWithoutResult(s -> {
            em.persist(newItem());
            em.flush();
            requiresNew.executeWithoutResult(s2 -> {
                AuxItem item = newItem();
                em.persist(item);
                inner.set(item.getId());
            });
            s.setRollbackOnly();
        });
        assertThat(all()).extracting(AuditEntry::getEntityId).containsExactly(inner.get().toString());
    }

    @Test
    void p26_otherThreadHasNoActor() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                Jwt.withTokenValue("t").header("alg", "none").subject(UUID.randomUUID().toString())
                        .claim(TokenService.LOGIN_CLAIM, "who").build(),
                AuthorityUtils.createAuthorityList("table:*:read")));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(this::createItem).get();
        } finally {
            executor.shutdownNow();
        }
        assertThat(all()).singleElement().satisfies(e -> {
            assertThat(e.getActorId()).isNull();
            assertThat(e.getActorLogin()).isNull();
        });
    }

    @Test
    void p27_writerOutsideTransactionAutocommits_documented() {
        auditWriter.write("aux_item", "x", "INSERT", null, null, null);
        assertThat(all()).hasSize(1);
    }

    @Test
    void p28_concurrentInserts() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<UUID>> futures = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                futures.add(executor.submit(this::createItem));
            }
            for (Future<UUID> f : futures) {
                f.get();
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(all()).hasSize(24);
    }

    @Test
    @Disabled("QA: audit #8, отложено")
    void p29_statelessSessionUpdateAudited() {
        UUID id = createItem();
        SessionFactory sf = em.getEntityManagerFactory().unwrap(SessionFactory.class);
        try (StatelessSession ss = sf.openStatelessSession()) {
            ss.getTransaction().begin();
            AuxItem item = ss.get(AuxItem.class, id);
            item.setName("stateless");
            ss.update(item);
            ss.getTransaction().commit();
        }
        assertThat(updates()).extracting(AuditEntry::getField).contains("name");
    }

    @Test
    void p30_statelessSessionInsertAudited() {
        SessionFactory sf = em.getEntityManagerFactory().unwrap(SessionFactory.class);
        try (StatelessSession ss = sf.openStatelessSession()) {
            ss.getTransaction().begin();
            ss.insert(newItem());
            ss.getTransaction().commit();
        }
        assertThat(all()).extracting(AuditEntry::getAction).containsExactly("INSERT");
    }

    // ---------------------------------------------------------------- REST

    @Test
    void p31_restUppercaseEntityType400() throws Exception {
        mvc.perform(get("/api/audit").param("entityType", "AUX_ITEM").header(HttpHeaders.AUTHORIZATION,
                        bearer(tokenWith("{\"actions\":[\"admin\"]}"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void p32_restUnknownActionEmpty() throws Exception {
        createItem();
        mvc.perform(get("/api/audit").param("entityType", "aux_item").param("action", "BOGUS")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenWith("{\"tables\":{\"aux_item\":[\"read\"]}}"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void p33_restSizeClampAndNegativePage() throws Exception {
        createItem();
        createItem();
        String t = bearer(tokenWith("{\"tables\":{\"aux_item\":[\"read\"]}}"));
        mvc.perform(get("/api/audit").param("entityType", "aux_item").param("size", "0")
                        .header(HttpHeaders.AUTHORIZATION, t))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.items", hasSize(1)));
        mvc.perform(get("/api/audit").param("entityType", "aux_item").param("size", "10000")
                        .header(HttpHeaders.AUTHORIZATION, t))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(500));
        mvc.perform(get("/api/audit").param("entityType", "aux_item").param("page", "-1")
                        .header(HttpHeaders.AUTHORIZATION, t))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0));
    }

    @Test
    @Disabled("QA: audit #7, отложено")
    void p34_restHugePageNot500() throws Exception {
        String t = bearer(tokenWith("{\"tables\":{\"aux_item\":[\"read\"]}}"));
        MvcResult r = mvc.perform(get("/api/audit").param("entityType", "aux_item")
                        .param("page", String.valueOf(Integer.MAX_VALUE)).param("size", "500")
                        .header(HttpHeaders.AUTHORIZATION, t)).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isIn(200, 400);
    }

    @Test
    @Disabled("QA: audit #7, отложено")
    void p35_restPageOverflowSmall() throws Exception {
        String t = bearer(tokenWith("{\"tables\":{\"aux_item\":[\"read\"]}}"));
        MvcResult r = mvc.perform(get("/api/audit").param("entityType", "aux_item")
                .param("page", "5000000").param("size", "500")
                .header(HttpHeaders.AUTHORIZATION, t)).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isIn(200, 400);
    }

    @Test
    @Disabled("QA: audit #10, отложено")
    void p36_restBadFromIs400WithoutJavaInternals() throws Exception {
        String t = bearer(tokenWith("{\"tables\":{\"aux_item\":[\"read\"]}}"));
        mvc.perform(get("/api/audit").param("entityType", "aux_item").param("from", "2026-11-08")
                        .header(HttpHeaders.AUTHORIZATION, t))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", not(containsString("java."))));
    }

    @Test
    void p37_restLocalDateTimeWithoutOffset() throws Exception {
        String t = bearer(tokenWith("{\"tables\":{\"aux_item\":[\"read\"]}}"));
        MvcResult r = mvc.perform(get("/api/audit").param("entityType", "aux_item")
                .param("from", "2026-11-08T00:00:00").header(HttpHeaders.AUTHORIZATION, t)).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isIn(200, 400);
        System.out.println("P37 " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString());
    }

    @Test
    void p38_restOffsetFromOnlyToOnly() throws Exception {
        createItem();
        String t = bearer(tokenWith("{\"tables\":{\"aux_item\":[\"read\"]}}"));
        mvc.perform(get("/api/audit").param("entityType", "aux_item").param("from", "2000-01-01T03:00:00+03:00")
                        .header(HttpHeaders.AUTHORIZATION, t))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/audit").param("entityType", "aux_item").param("to", "2000-01-01T00:00:00Z")
                        .header(HttpHeaders.AUTHORIZATION, t))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/audit").param("entityType", "aux_item").param("to", "2999-01-01T00:00:00Z")
                        .header(HttpHeaders.AUTHORIZATION, t))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void p39_restOrderOfFieldRowsWithinOneFlushStable() throws Exception {
        UUID id = createItem();
        updateItem(id, item -> {
            item.setName("n2");
            item.setKind(Kind.B);
            item.setLongText("t");
            item.setDay(LocalDate.of(2020, 1, 1));
        });
        String t = bearer(tokenWith("{\"tables\":{\"aux_item\":[\"read\"]}}"));
        List<List<String>> orders = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String body = mvc.perform(get("/api/audit").param("entityType", "aux_item").param("action", "UPDATE")
                    .header(HttpHeaders.AUTHORIZATION, t)).andReturn().getResponse().getContentAsString();
            orders.add(JsonPath.read(body, "$.items[*].field"));
        }
        Set<Instant> stamps = new HashSet<>();
        updates().forEach(e -> stamps.add(e.getCreatedAt()));
        System.out.println("P39 orders=" + orders + " distinctStamps=" + stamps.size());
        // Порядок строк одного flush не гарантирован (created_at может совпасть), но стабилен между запросами.
        assertThat(orders.get(0)).containsExactlyInAnyOrder("name", "longText", "kind", "day");
        assertThat(orders).allSatisfy(order -> assertThat(order).isEqualTo(orders.get(0)));
    }

    @Test
    void p40_restWildcardReadSeesAnyTable_design() throws Exception {
        createItem();
        mvc.perform(get("/api/audit").param("entityType", "aux_item")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenWith("{\"tables\":{\"*\":[\"read\"]}}"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/audit").param("entityType", "aux_item")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenWith("{\"tables\":{\"*\":[\"write\"]}}"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void p41_restNoEntityLeakAcrossTables() throws Exception {
        Long parentId = tx.execute(s -> {
            AuxParent p = new AuxParent();
            p.setName("secret-parent");
            em.persist(p);
            return p.getId();
        });
        String t = bearer(tokenWith("{\"tables\":{\"aux_item\":[\"read\"]}}"));
        mvc.perform(get("/api/audit").param("entityType", "aux_item").param("entityId", parentId.toString())
                        .header(HttpHeaders.AUTHORIZATION, t))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/audit").param("entityId", parentId.toString()).header(HttpHeaders.AUTHORIZATION, t))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/audit").param("entityType", "aux_parent").header(HttpHeaders.AUTHORIZATION, t))
                .andExpect(status().isForbidden());
    }

    @Test
    void p42_attachDetachVisibleToOwnerReader() throws Exception {
        UUID id = createItem();
        AttachmentDto dto = attachmentService.store("aux_item", id.toString(),
                new MockMultipartFile("file", "dog.pdf", "application/pdf", "%PDF-1.4 test".getBytes()));
        attachmentService.delete(dto.id());
        String t = bearer(tokenWith("{\"tables\":{\"aux_item\":[\"read\"]}}"));
        mvc.perform(get("/api/audit").param("entityType", "aux_item").param("entityId", id.toString())
                        .header(HttpHeaders.AUTHORIZATION, t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items[?(@.action == 'ATTACH')].newValue").value("dog.pdf"))
                .andExpect(jsonPath("$.items[?(@.action == 'DETACH')].oldValue").value("dog.pdf"));
    }

    @Test
    void p43_attachmentEntityItselfNotDoubleAudited() {
        UUID id = createItem();
        attachmentService.store("aux_item", id.toString(),
                new MockMultipartFile("file", "x.pdf", "application/pdf", "%PDF-1.4 t".getBytes()));
        assertThat(all()).extracting(AuditEntry::getEntityType).doesNotContain("common_attachment");
    }

    @Test
    void p44_dictionaryRestActorAndNoOpPut() throws Exception {
        String token = tokenWith("{\"tables\":{\"common_sequence\":[\"read\",\"write\"]}}");
        String login = userRepository.findById(createdUsers.get(0)).orElseThrow().getLogin();
        String created = mvc.perform(post("/api/argent-dictionary/common_sequence/records")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"AUXQA1\",\"pattern\":\"Q-%2$d\",\"resetPeriod\":\"NEVER\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        mvc.perform(put("/api/argent-dictionary/common_sequence/records/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(created))
                .andExpect(status().isOk());
        assertThat(all()).as("PUT без изменений").extracting(AuditEntry::getAction).containsExactly("INSERT");
        assertThat(all().get(0).getActorLogin()).isEqualTo(login);
        mvc.perform(put("/api/argent-dictionary/common_sequence/records/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + id + "\",\"code\":\"AUXQA1\",\"pattern\":\"Q2-%2$d\","
                                + "\"resetPeriod\":\"NEVER\"}"))
                .andExpect(status().isOk());
        assertThat(updates()).extracting(AuditEntry::getField, AuditEntry::getOldValue, AuditEntry::getNewValue,
                AuditEntry::getActorLogin).containsExactly(tuple("pattern", "Q-%2$d", "Q2-%2$d", login));
        mvc.perform(get("/api/audit").param("entityType", "common_sequence").param("entityId", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void p45_entityIdBlankIgnoredAndAdminFullScanPlan() throws Exception {
        createItem();
        String admin = bearer(tokenWith("{\"actions\":[\"admin\"]}"));
        mvc.perform(get("/api/audit").param("entityId", " ").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        List<String> plan = jdbc.queryForList("explain select * from common_audit_entry where created_at >= now() - "
                + "interval '1 day' order by created_at desc, id desc limit 20", String.class);
        System.out.println("P45 plan=" + plan);
    }

    @Test
    void p46_statelessSessionDeleteAudited() {
        UUID id = createItem();
        jdbc.update("delete from aux_item_tag");
        SessionFactory sf = em.getEntityManagerFactory().unwrap(SessionFactory.class);
        try (StatelessSession ss = sf.openStatelessSession()) {
            ss.getTransaction().begin();
            ss.delete(ss.get(AuxItem.class, id));
            ss.getTransaction().commit();
        }
        assertThat(all()).extracting(AuditEntry::getAction).contains("DELETE");
    }

    @Test
    void p47_insertThenUpdateSameTxNoFlush() {
        UUID id = tx.execute(s -> {
            AuxItem item = newItem();
            em.persist(item);
            item.setName("changed-before-flush");
            return item.getId();
        });
        // QA audit И-1: Hibernate вставляет состояние на момент persist, затем dirty-check даёт UPDATE.
        assertThat(all()).extracting(AuditEntry::getAction).as("persist+изменение до flush")
                .containsExactly("INSERT", "UPDATE");
        assertThat(id).isNotNull();
    }

    // ---------------------------------------------------------------- helpers

    private AuxItem newItem() {
        AuxItem item = new AuxItem();
        item.setName("item");
        item.setSecret("s0");
        item.setKind(Kind.A);
        item.setAddress(new AuxAddress("Тула", "PIN-SECRET-1"));
        item.setGeo(new AuxGeo(0.0, 0.0));
        item.setBlob(new byte[]{1, 2});
        item.getTags().add("t0");
        return item;
    }

    private UUID createItem() {
        return tx.execute(s -> {
            AuxItem item = newItem();
            em.persist(item);
            return item.getId();
        });
    }

    private void updateItem(UUID id, Consumer<AuxItem> change) {
        tx.executeWithoutResult(s -> change.accept(em.find(AuxItem.class, id)));
    }

    private List<AuditEntry> all() {
        return auditRepository.findAll(org.springframework.data.domain.Sort.by("createdAt"));
    }

    private List<AuditEntry> updates() {
        List<AuditEntry> result = all().stream().filter(e -> "UPDATE".equals(e.getAction())).toList();
        System.out.println("UPDATES " + result.stream()
                .map(e -> e.getEntityType() + "." + e.getField() + ": " + abbreviate(e.getOldValue()) + " -> "
                        + abbreviate(e.getNewValue())).toList());
        return result;
    }

    private static String abbreviate(String s) {
        return s == null ? null : s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private String tokenWith(String grants) {
        User user = new User();
        user.setLogin("aux-" + UUID.randomUUID());
        user.setDisplayName("QA");
        user = userRepository.save(user);
        createdUsers.add(user.getId());
        Role role = new Role();
        role.setCode("AUXQA_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        role.setName("QA");
        role.setGrants(grants);
        role = roleRepository.save(role);
        createdRoles.add(role.getId());
        userRoleRepository.save(new UserRole(new UserRoleId(user.getId(), role.getId())));
        return tokenService.issue(user);
    }

    // ---------------------------------------------------------------- fixtures

    enum Kind { A, B }

    @Embeddable
    public static class AuxAddress {
        private String city;
        @AuditIgnore
        private String pin;

        public AuxAddress() {
        }

        AuxAddress(String city, String pin) {
            this.city = city;
            this.pin = pin;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof AuxAddress a && Objects.equals(city, a.city) && Objects.equals(pin, a.pin);
        }

        @Override
        public int hashCode() {
            return Objects.hash(city, pin);
        }

        /** Как у Lombok {@code @Data}. */
        @Override
        public String toString() {
            return "AuxAddress(city=" + city + ", pin=" + pin + ")";
        }
    }

    @Embeddable
    public static class AuxGeo {
        private Double lat;
        private Double lon;

        public AuxGeo() {
        }

        AuxGeo(Double lat, Double lon) {
            this.lat = lat;
            this.lon = lon;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof AuxGeo g && Objects.equals(lat, g.lat) && Objects.equals(lon, g.lon);
        }

        @Override
        public int hashCode() {
            return Objects.hash(lat, lon);
        }
    }

    @Entity
    @Audited
    @Table(name = "aux_item")
    @lombok.Getter
    @lombok.Setter
    public static class AuxItem {
        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;
        private String name;
        @AuditIgnore
        private String secret;
        private byte[] blob;
        private LocalDate day;
        private BigDecimal amount;
        private Instant at;
        @Enumerated(EnumType.STRING)
        private Kind kind;
        @Embedded
        private AuxAddress address;
        @Embedded
        private AuxGeo geo;
        @Column(name = "long_text")
        private String longText;
        @ElementCollection(fetch = FetchType.EAGER)
        @CollectionTable(name = "aux_item_tag", joinColumns = @JoinColumn(name = "item_id"))
        @Column(name = "tag")
        private Set<String> tags = new HashSet<>();
        @Version
        private Long version;
    }

    @Entity
    @Audited
    @Table(name = "aux_parent")
    @lombok.Getter
    @lombok.Setter
    public static class AuxParent {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;
        @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, orphanRemoval = true)
        private List<AuxChild> children = new ArrayList<>();
    }

    @Entity
    @Audited
    @Table(name = "aux_child")
    @lombok.Getter
    @lombok.Setter
    public static class AuxChild {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;
        @ManyToOne
        @JoinColumn(name = "parent_id")
        private AuxParent parent;
    }

    @Embeddable
    public static class AuxCompKey implements Serializable {
        private String a;
        private String b;

        public AuxCompKey() {
        }

        AuxCompKey(String a, String b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof AuxCompKey k && Objects.equals(a, k.a) && Objects.equals(b, k.b);
        }

        @Override
        public int hashCode() {
            return Objects.hash(a, b);
        }
    }

    @Entity
    @Audited
    @Table(name = "aux_comp")
    @lombok.Getter
    @lombok.Setter
    public static class AuxComp {
        @EmbeddedId
        private AuxCompKey id;
        private String val;
    }

    @Entity
    @Audited
    @Table(name = "aux_str")
    @lombok.Getter
    @lombok.Setter
    public static class AuxStr {
        @Id
        private String id;
        private String val;
    }

    @Entity
    @Audited
    @Table(name = "AuxUpper")
    @lombok.Getter
    @lombok.Setter
    public static class AuxUpper {
        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;
        private String name;
    }

    @Entity
    @Audited
    @Table(name = "aux_item", schema = "it_explore_audit_other")
    @lombok.Getter
    @lombok.Setter
    public static class AuxOther {
        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;
        private String name;
    }

    @Entity
    @Audited
    @Table(name = "aux_animal")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    @DiscriminatorColumn(name = "kind")
    @DiscriminatorValue("ANIMAL")
    @lombok.Getter
    @lombok.Setter
    public static class AuxAnimal {
        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;
        private String name;
    }

    @Entity
    @DiscriminatorValue("DOG")
    @lombok.Getter
    @lombok.Setter
    public static class AuxDog extends AuxAnimal {
        private String bark;
    }

    /** Тестовое приложение: список сущностей задан явно, чтобы не сканировать чужие explore-классы. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackageClasses = {
            CommonDictionaryRepository.class, UserRepository.class, RoleRepository.class,
            CommonStatusRepository.class, CommonSequenceRepository.class, AuditEntryRepository.class,
            AttachmentRepository.class
    })
    static class TestApplication {

        @Bean
        PersistenceManagedTypes persistenceManagedTypes(ResourceLoader resourceLoader) {
            PersistenceManagedTypes scanned = new PersistenceManagedTypesScanner(resourceLoader).scan(
                    CommonDictionary.class.getPackageName(), User.class.getPackageName(),
                    Role.class.getPackageName(), CommonStatus.class.getPackageName(),
                    CommonSequence.class.getPackageName(), AuditEntry.class.getPackageName(),
                    Attachment.class.getPackageName(), AudOrder.class.getPackageName());
            List<String> names = new ArrayList<>(scanned.getManagedClassNames());
            for (Class<?> c : List.of(AuxItem.class, AuxAddress.class, AuxGeo.class, AuxParent.class,
                    AuxChild.class, AuxCompKey.class, AuxComp.class, AuxStr.class, AuxUpper.class, AuxOther.class,
                    AuxAnimal.class, AuxDog.class)) {
                names.add(c.getName());
            }
            return PersistenceManagedTypes.of(names, scanned.getManagedPackages());
        }
    }
}
