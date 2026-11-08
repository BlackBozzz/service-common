package com.argent.servicecommon.security;

import com.argent.servicecommon.auth.entity.User;
import com.argent.servicecommon.auth.repository.UserRepository;
import com.argent.servicecommon.security.entity.Role;
import com.argent.servicecommon.security.entity.UserRole;
import com.argent.servicecommon.security.entity.UserRoleId;
import com.argent.servicecommon.security.repository.RoleRepository;
import com.argent.servicecommon.security.repository.UserRoleRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет сохранение и выборки пользователей и ролей с грантами на реальном PostgreSQL.
 */
@SpringBootTest(classes = SecurityTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class SecurityRepositoriesIntegrationTest {

    private static final String GRANTS = "{\"tables\":{\"dict_x\":[\"read\"]},\"actions\":[]}";

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        SecurityTestContainer.register(registry, "it_security");
    }

    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleRepository userRoleRepository;
    @Autowired private EntityManager entityManager;

    @Test
    void shouldPersistUserWithRole() {
        Role role = new Role();
        role.setCode("ANALYST");
        role.setName("Аналитик");
        role.setGrants(GRANTS);
        role = roleRepository.saveAndFlush(role);

        User user = new User();
        user.setLogin("ivan");
        user.setDisplayName("Иван");
        user = userRepository.saveAndFlush(user);

        userRoleRepository.saveAndFlush(new UserRole(new UserRoleId(user.getId(), role.getId())));

        assertThat(user.isEnabled()).isTrue();
        assertThat(user.getCreatedAt()).isNotNull();
        assertThat(userRoleRepository.findByIdUserId(user.getId())).hasSize(1);
        assertThat(userRepository.findByLogin("ivan")).isPresent();
        assertThat(roleRepository.findByCode("ANALYST")).isPresent();
        // Единственная системная роль — ADMIN, созданная при старте BootstrapAdminInitializer.
        assertThat(roleRepository.findBySystemTrue()).extracting(Role::getCode).containsExactly("ADMIN");
        assertThat(userRoleRepository.existsByIdRoleId(role.getId())).isTrue();
    }

    @Test
    void shouldRoundTripRoleGrantsThroughJsonbColumn() {
        Role role = new Role();
        role.setCode("READER_X");
        role.setName("Читатель");
        role.setGrants(GRANTS);
        role = roleRepository.saveAndFlush(role);
        entityManager.clear();

        Role loaded = roleRepository.findById(role.getId()).orElseThrow();

        assertThat(loaded.getGrants()).isEqualToIgnoringWhitespace(GRANTS);
    }

    @Test
    void shouldDefaultRoleGrantsToEmptyObject() {
        Role role = new Role();
        role.setCode("EMPTY_X");
        role.setName("Без прав");
        role = roleRepository.saveAndFlush(role);
        entityManager.clear();

        assertThat(roleRepository.findById(role.getId()).orElseThrow().getGrants()).isEqualTo("{}");
    }
}
