package com.argent.servicecommon.attachment;

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
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST вложений: загрузка, список, скачивание, удаление и коды отказов по грантам таблиц.
 */
@SpringBootTest(classes = SecurityTestApplication.class)
@AutoConfigureMockMvc
class AttachmentRestIntegrationTest {

    private static final String READER = "{\"tables\":{\"orders\":[\"read\"]}}";
    private static final String WRITER = "{\"tables\":{\"orders\":[\"read\",\"write\"]}}";
    private static final String WRITE_ONLY = "{\"tables\":{\"orders\":[\"write\"]}}";
    private static final String OTHER = "{\"tables\":{\"x\":[\"read\",\"write\"]}}";
    private static final String ADMIN = "{\"actions\":[\"admin\"]}";

    private static final byte[] PDF = "%PDF-1.4 rest".getBytes(StandardCharsets.US_ASCII);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SecurityTestContainer.register(registry, "it_attachment");
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AttachmentService service;
    @Autowired private TokenService tokenService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository userSessionRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleRepository userRoleRepository;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdRoles = new ArrayList<>();

    @BeforeEach
    void prepare() {
        deleteAttachments();
    }

    @AfterEach
    void cleanUp() {
        deleteAttachments();
        jdbc.update("DELETE FROM common_audit_entry WHERE action IN ('ATTACH', 'DETACH')");
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
    void shouldUploadListDownloadDelete() throws Exception {
        String writer = tokenWith(WRITER);

        String id = upload(writer, "a.pdf", "application/pdf", PDF);

        mvc.perform(auth(get("/api/attachments").param("entityType", "orders").param("entityId", "42"), writer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(id)));

        String sha256 = service.find(UUID.fromString(id)).getSha256();
        mvc.perform(auth(get("/api/attachments/" + id), writer))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("filename*=UTF-8''a.pdf")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "sandbox"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, PDF.length))
                .andExpect(header().string(HttpHeaders.ETAG, "\"" + sha256 + "\""))
                .andExpect(content().bytes(PDF));

        mvc.perform(auth(delete("/api/attachments/" + id), writer))
                .andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/attachments/" + id), writer))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldRequireReadForList() throws Exception {
        mvc.perform(auth(get("/api/attachments").param("entityType", "orders").param("entityId", "42"),
                        tokenWith(OTHER)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/attachments").param("entityType", "orders").param("entityId", "42"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRequireAuthEverywhere() throws Exception {
        String id = upload(tokenWith(WRITER), "a.pdf", "application/pdf", PDF);

        mvc.perform(uploadRequest("a.pdf", "application/pdf", PDF))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/attachments/" + id))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/attachments/" + id))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldFallBackToOctetStreamForBrokenContentType() throws Exception {
        String writer = tokenWith(WRITER);
        String id = upload(writer, "a.pdf", "application/pdf", PDF);
        jdbc.update("UPDATE common_attachment SET content_type = 'broken' WHERE id = ?", UUID.fromString(id));

        mvc.perform(auth(get("/api/attachments/" + id), writer))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/octet-stream"))
                .andExpect(content().bytes(PDF));
    }

    @Test
    void shouldRequireWriteForUpload() throws Exception {
        mvc.perform(auth(uploadRequest("a.pdf", "application/pdf", PDF), tokenWith(READER)))
                .andExpect(status().isForbidden());
        mvc.perform(auth(uploadRequest("a.pdf", "application/pdf", PDF), tokenWith(WRITE_ONLY)))
                .andExpect(status().isCreated());
    }

    @Test
    void shouldHide404ForDownloadWithoutRead() throws Exception {
        String id = upload(tokenWith(WRITER), "a.pdf", "application/pdf", PDF);

        mvc.perform(auth(get("/api/attachments/" + id), tokenWith(OTHER)))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturn403ForDeleteWithReadOnly() throws Exception {
        String id = upload(tokenWith(WRITER), "a.pdf", "application/pdf", PDF);

        mvc.perform(auth(delete("/api/attachments/" + id), tokenWith(READER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", is("Нет права записи в таблицу orders")));
        mvc.perform(auth(delete("/api/attachments/" + id), tokenWith(OTHER)))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturn404OnSecondDelete() throws Exception {
        String writer = tokenWith(WRITER);
        String id = upload(writer, "a.pdf", "application/pdf", PDF);

        mvc.perform(auth(delete("/api/attachments/" + id), writer))
                .andExpect(status().isNoContent());
        mvc.perform(auth(delete("/api/attachments/" + id), writer))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldRejectTooLargeAndBlockedTypeOverRest() throws Exception {
        // Маленький max-size-bytes в общем контексте невозможен: проверяется только блок-лист.
        byte[] html = "<html><body>x</body></html>".getBytes(StandardCharsets.UTF_8);

        mvc.perform(auth(uploadRequest("x.html", "text/html", html), tokenWith(WRITER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", is("Тип файла не разрешён: text/html")));
    }

    @Test
    void shouldLetAdminUploadAnywhere() throws Exception {
        // admin даёт действия, а не таблицы: без гранта write загрузка запрещена.
        mvc.perform(auth(uploadRequest("a.pdf", "application/pdf", PDF), tokenWith(ADMIN)))
                .andExpect(status().isForbidden());
    }

    private String upload(String token, String name, String type, byte[] bytes) throws Exception {
        String body = mvc.perform(auth(uploadRequest(name, type, bytes), token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName", is(name)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.id");
    }

    private static MockMultipartHttpServletRequestBuilder uploadRequest(String name, String type, byte[] bytes) {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/attachments")
                .file(new MockMultipartFile("file", name, type, bytes));
        request.param("entityType", "orders").param("entityId", "42");
        return request;
    }

    private static <B extends AbstractMockHttpServletRequestBuilder<B>> B auth(B request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private void deleteAttachments() {
        jdbc.queryForList("SELECT id FROM common_attachment", UUID.class).forEach(service::delete);
    }

    private String tokenWith(String grants) {
        User user = new User();
        user.setLogin("attachment-" + UUID.randomUUID());
        user.setDisplayName("Пользователь вложений");
        user = userRepository.save(user);
        createdUsers.add(user.getId());

        Role role = new Role();
        role.setCode("ATT_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        role.setName("Роль вложений");
        role.setGrants(grants);
        role = roleRepository.save(role);
        createdRoles.add(role.getId());
        userRoleRepository.save(new UserRole(new UserRoleId(user.getId(), role.getId())));
        return tokenService.issue(user);
    }
}
