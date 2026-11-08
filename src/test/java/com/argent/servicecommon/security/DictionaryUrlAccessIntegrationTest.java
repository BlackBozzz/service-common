package com.argent.servicecommon.security;

import com.argent.servicecommon.auth.entity.User;
import com.argent.servicecommon.auth.repository.UserRepository;
import com.argent.servicecommon.auth.repository.UserSessionRepository;
import com.argent.servicecommon.auth.service.TokenService;
import com.argent.servicecommon.dictionary.entity.CommonDictionary;
import com.argent.servicecommon.dictionary.repository.CommonDictionaryRepository;
import com.argent.servicecommon.security.entity.Role;
import com.argent.servicecommon.security.entity.UserRole;
import com.argent.servicecommon.security.entity.UserRoleId;
import com.argent.servicecommon.security.repository.RoleRepository;
import com.argent.servicecommon.security.repository.UserRoleRepository;
import com.argent.servicecommon.security.urlfixture.UrlCountry;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Проверяет правило доступа к справочникам по URL: список — любому вошедшему,
 * чтение ({@code GET}) — по гранту {@code read}, изменения — по гранту {@code write} на таблицу справочника.
 */
@SpringBootTest(
        classes = DictionaryUrlAccessIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@AutoConfigureMockMvc
class DictionaryUrlAccessIntegrationTest {

    private static final String ROOT = "/api/argent-dictionary";
    private static final String BASE = ROOT + "/url_country";
    private static final String RECORDS = BASE + "/records";

    private static final String READER = "{\"tables\":{\"url_country\":[\"read\"]}}";
    private static final String WRITER = "{\"tables\":{\"url_country\":[\"read\",\"write\"]}}";
    private static final String STAR = "{\"tables\":{\"*\":[\"read\"]}}";
    private static final String WRITE_ONLY = "{\"tables\":{\"*\":[\"write\"]}}";
    private static final String NOBODY = "{}";

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        SecurityTestContainer.register(registry, "it_dict_url");
    }

    @Autowired private MockMvc mvc;
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
    void shouldListForAnyAuthenticated() throws Exception {
        String nobody = tokenWith(NOBODY);

        expect(get(ROOT), nobody, status().isOk());
    }

    @Test
    void shouldRequireReadForRecordsSchemaAndOptions() throws Exception {
        String nobody = tokenWith(NOBODY);
        String reader = tokenWith(READER);
        long id = createCountry(tokenWith(WRITER));

        for (String path : List.of(RECORDS, RECORDS + "/" + id, BASE + "/schema", BASE + "/options",
                BASE + "/form")) {
            expect(get(path), nobody, status().isForbidden());
            expect(get(path), reader, status().isOk());
        }
    }

    @Test
    void shouldRequireWriteForMutations() throws Exception {
        String reader = tokenWith(READER);
        String writer = tokenWith(WRITER);

        expect(createRequest(), reader, status().isForbidden());
        expect(createRequest(), writer, status().isCreated());
    }

    @Test
    void shouldDenyPutAndDeleteWithReadOnly() throws Exception {
        String reader = tokenWith(READER);
        long id = createCountry(tokenWith(WRITER));
        String item = RECORDS + "/" + id;

        expect(put(item).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Италия\"}"),
                reader, status().isForbidden());
        expect(delete(item), reader, status().isForbidden());
        expect(get(item), reader, status().isOk());
    }

    @Test
    void shouldTreatHeadAsRead() throws Exception {
        String reader = tokenWith(READER);
        String nobody = tokenWith(NOBODY);

        expect(head(RECORDS), reader, status().isOk());
        expect(head(RECORDS), nobody, status().isForbidden());
    }

    @Test
    void shouldApplyStar() throws Exception {
        String star = tokenWith(STAR);

        expect(get(RECORDS), star, status().isOk());
        expect(createRequest(), star, status().isForbidden());
    }

    @Test
    void shouldNotImplyReadByWrite() throws Exception {
        String writeOnly = tokenWith(WRITE_ONLY);

        expect(get(RECORDS), writeOnly, status().isForbidden());
        expect(createRequest(), writeOnly, status().isCreated());
    }

    @Test
    void shouldDenyAnonymous() throws Exception {
        mvc.perform(get(ROOT)).andExpect(status().isUnauthorized());
        mvc.perform(get(RECORDS)).andExpect(status().isUnauthorized());
        mvc.perform(createRequest()).andExpect(status().isUnauthorized());
    }

    private long createCountry(String token) throws Exception {
        String body = mvc.perform(createRequest().header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonMapper.shared().readTree(body).get("id").asLong();
    }

    private static MockHttpServletRequestBuilder createRequest() {
        return post(RECORDS)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Франция\"}");
    }

    private void expect(MockHttpServletRequestBuilder request, String token, ResultMatcher expected)
            throws Exception {
        mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(expected);
    }

    private String tokenWith(String grants) {
        User user = new User();
        user.setLogin("dict-url-" + UUID.randomUUID());
        user.setDisplayName("Пользователь справочников");
        user = userRepository.save(user);
        createdUsers.add(user.getId());

        Role role = new Role();
        role.setCode("DICT_URL_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        role.setName("Роль справочников");
        role.setGrants(grants);
        role = roleRepository.save(role);
        createdRoles.add(role.getId());
        userRoleRepository.save(new UserRole(new UserRoleId(user.getId(), role.getId())));
        return tokenService.issue(user);
    }

    /** Тестовая конфигурация с фикстурой-справочником {@link UrlCountry}. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {CommonDictionary.class, User.class, Role.class, UrlCountry.class})
    @EnableJpaRepositories(basePackageClasses = {
            CommonDictionaryRepository.class, UserRepository.class, RoleRepository.class
    })
    static class TestApplication {
    }
}
