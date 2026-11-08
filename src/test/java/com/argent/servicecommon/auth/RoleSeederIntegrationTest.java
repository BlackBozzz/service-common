package com.argent.servicecommon.auth;

import com.argent.servicecommon.auth.service.BootstrapAdminInitializer;
import com.argent.servicecommon.auth.service.RoleSeeder;
import com.argent.servicecommon.security.SecurityTestApplication;
import com.argent.servicecommon.security.SecurityTestContainer;
import com.argent.servicecommon.security.entity.Role;
import com.argent.servicecommon.security.grants.GrantConstants;
import com.argent.servicecommon.security.grants.GrantsCodec;
import com.argent.servicecommon.security.grants.RoleGrants;
import com.argent.servicecommon.security.repository.RoleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ResourceLoader;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Проверяет загрузку стартовых ролей из {@code argent.auth.roles-file} и выравнивание роли {@code ADMIN}.
 */
@SpringBootTest(classes = SecurityTestApplication.class)
class RoleSeederIntegrationTest {

    private static final String READER_GRANTS = "{\"tables\":{\"*\":[\"read\"]}}";
    private static final String EDITOR_GRANTS =
            "{\"tables\":{\"*\":[\"read\",\"write\"]},\"actions\":[\"documents.approve\"]}";
    private static final String TAMPERED_GRANTS = "{\"tables\":{\"secret\":[\"write\"]}}";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SecurityTestContainer.register(registry, "it_seed");
        registry.add("argent.auth.roles-file", () -> "classpath:argent-roles-test.json");
    }

    @Autowired private RoleSeeder roleSeeder;
    @Autowired private BootstrapAdminInitializer bootstrapAdminInitializer;
    @Autowired private RoleRepository roleRepository;
    @Autowired private ResourceLoader resourceLoader;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void shouldCreateRolesFromFile() {
        Role reader = roleRepository.findByCode("READER").orElseThrow();
        assertThat(reader.getName()).isEqualTo("Читатель");
        assertThat(reader.getDescription()).isEqualTo("Читает все таблицы");
        assertThat(reader.isSystem()).isFalse();
        assertThat(GrantsCodec.parse(reader.getGrants())).isEqualTo(GrantsCodec.parse(READER_GRANTS));

        Role editor = roleRepository.findByCode("EDITOR").orElseThrow();
        assertThat(editor.getName()).isEqualTo("Редактор");
        assertThat(editor.getDescription()).isNull();
        assertThat(editor.isSystem()).as("system из файла игнорируется").isFalse();
        assertThat(GrantsCodec.parse(editor.getGrants())).isEqualTo(GrantsCodec.parse(EDITOR_GRANTS));
    }

    @Test
    void shouldNotOverwriteEditedRoleInCreateMode() {
        Role reader = roleRepository.findByCode("READER").orElseThrow();
        reader.setName("Изменено админом");
        reader.setGrants(TAMPERED_GRANTS);
        roleRepository.save(reader);
        try {
            roleSeeder.run(null);

            Role after = roleRepository.findByCode("READER").orElseThrow();
            assertThat(after.getName()).isEqualTo("Изменено админом");
            assertThat(GrantsCodec.parse(after.getGrants())).isEqualTo(GrantsCodec.parse(TAMPERED_GRANTS));
        } finally {
            restoreReader();
        }
    }

    @Test
    void shouldOverwriteInOverwriteMode() {
        Role reader = roleRepository.findByCode("READER").orElseThrow();
        reader.setName("Изменено админом");
        reader.setDescription(null);
        reader.setGrants(TAMPERED_GRANTS);
        roleRepository.save(reader);
        try {
            seeder("classpath:argent-roles-test.json", AuthProperties.RolesFileMode.OVERWRITE).seed();

            Role after = roleRepository.findByCode("READER").orElseThrow();
            assertThat(after.getName()).isEqualTo("Читатель");
            assertThat(after.getDescription()).isEqualTo("Читает все таблицы");
            assertThat(GrantsCodec.parse(after.getGrants())).isEqualTo(GrantsCodec.parse(READER_GRANTS));
        } finally {
            restoreReader();
        }
    }

    @Test
    void shouldAlwaysAlignAdminGrants() {
        RoleGrants expected = GrantsCodec.parse(GrantConstants.ADMIN_GRANTS);
        Role admin = roleRepository.findByCode(GrantConstants.ADMIN_ROLE_CODE).orElseThrow();
        assertThat(admin.isSystem()).isTrue();
        assertThat(GrantsCodec.parse(admin.getGrants())).isEqualTo(expected);

        seeder("classpath:argent-roles-with-admin.json", AuthProperties.RolesFileMode.OVERWRITE).seed();
        Role afterFile = roleRepository.findByCode(GrantConstants.ADMIN_ROLE_CODE).orElseThrow();
        assertThat(afterFile.getName()).as("ADMIN из файла игнорируется").isEqualTo(admin.getName());
        assertThat(GrantsCodec.parse(afterFile.getGrants())).isEqualTo(expected);

        afterFile.setGrants(TAMPERED_GRANTS);
        afterFile.setSystem(false);
        roleRepository.save(afterFile);

        bootstrapAdminInitializer.run(null);

        Role restored = roleRepository.findByCode(GrantConstants.ADMIN_ROLE_CODE).orElseThrow();
        assertThat(restored.isSystem()).isTrue();
        assertThat(GrantsCodec.parse(restored.getGrants())).isEqualTo(expected);
    }

    @Test
    void shouldFailOnInvalidRoleInFile() {
        RoleSeeder invalid = seeder("classpath:argent-roles-invalid.json", AuthProperties.RolesFileMode.CREATE);

        assertThatThrownBy(invalid::seed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("argent-roles-invalid.json")
                .hasMessageContaining("BROKEN");
        assertThat(roleRepository.findByCode("BROKEN")).isEmpty();
    }

    @Test
    void shouldIgnoreMissingFile() {
        long before = roleRepository.count();

        seeder("classpath:no-such-roles.json", AuthProperties.RolesFileMode.OVERWRITE).seed();

        assertThat(roleRepository.count()).isEqualTo(before);
    }

    private RoleSeeder seeder(String location, AuthProperties.RolesFileMode mode) {
        return new RoleSeeder(location, mode, resourceLoader, roleRepository, transactionManager);
    }

    private void restoreReader() {
        Role reader = roleRepository.findByCode("READER").orElseThrow();
        reader.setName("Читатель");
        reader.setDescription("Читает все таблицы");
        reader.setGrants(READER_GRANTS);
        roleRepository.save(reader);
    }
}
