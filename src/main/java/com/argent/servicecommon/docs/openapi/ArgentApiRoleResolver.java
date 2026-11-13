package com.argent.servicecommon.docs.openapi;

import com.argent.servicecommon.security.entity.Role;
import com.argent.servicecommon.security.grants.GrantConstants;
import com.argent.servicecommon.security.grants.GrantsCodec;
import com.argent.servicecommon.security.repository.RoleRepository;
import java.util.Comparator;
import java.util.List;

/** Список ролей, имеющих требуемое действие в момент запроса (не учитывает другие условия доступа). */
public class ArgentApiRoleResolver {
    private final RoleRepository repository;

    public ArgentApiRoleResolver(RoleRepository repository) {
        this.repository = repository;
    }

    public record RoleView(String code, String name) { }

    public List<RoleView> forAction(String action) {
        if (action == null || action.isBlank()) return List.of();
        return repository.findAll().stream()
                .filter(role -> hasAction(role, action))
                .map(role -> new RoleView(role.getCode(), role.getName()))
                .sorted(Comparator.comparing(RoleView::name).thenComparing(RoleView::code))
                .toList();
    }

    private boolean hasAction(Role role, String action) {
        try {
            var actions = GrantsCodec.parse(role.getGrants()).actions();
            return actions.contains(action) || actions.contains(GrantConstants.ADMIN_ACTION);
        } catch (RuntimeException invalidRoleGrants) {
            // Не раскрываем исключение БД пользователю Swagger: поврежденную роль пропускаем.
            return false;
        }
    }
}
