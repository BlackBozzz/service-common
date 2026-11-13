package com.argent.servicecommon.docs.openapi;

import com.argent.servicecommon.security.entity.Role;
import com.argent.servicecommon.security.repository.RoleRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArgentApiRoleResolverTest {
    @Test
    void resolvesCurrentRoleGrantsAndAdminOverride() {
        Role admin = role("ADMIN", "Администратор", "{\"actions\":[\"admin\"]}");
        Role editor = role("DOC_EDITOR", "Редактор", "{\"actions\":[\"docs.publish\"]}");
        Role reader = role("READER", "Читатель", "{\"actions\":[\"docs.read\"]}");
        RoleRepository repo = mock(RoleRepository.class);
        when(repo.findAll()).thenReturn(List.of(admin, editor, reader));

        ArgentApiRoleResolver resolver = new ArgentApiRoleResolver(repo);
        assertThat(resolver.forAction("docs.publish")).extracting(ArgentApiRoleResolver.RoleView::code)
                .containsExactly("ADMIN", "DOC_EDITOR");
        assertThat(resolver.forAction("not-granted")).extracting(ArgentApiRoleResolver.RoleView::code)
                .containsExactly("ADMIN");
    }

    private Role role(String code, String name, String grants) {
        Role role = new Role();
        role.setCode(code);
        role.setName(name);
        role.setGrants(grants);
        return role;
    }
}
