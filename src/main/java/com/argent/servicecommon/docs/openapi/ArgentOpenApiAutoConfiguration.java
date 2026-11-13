package com.argent.servicecommon.docs.openapi;

import com.argent.servicecommon.security.repository.RoleRepository;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Интеграция современного Swagger/OpenAPI с моделью грантов Argent. */
@AutoConfiguration
@ConditionalOnClass(name = "org.springdoc.core.customizers.OperationCustomizer")
@ConditionalOnProperty(prefix = "argent.docs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ArgentOpenApiAutoConfiguration {
    public static final String ACTION_KEY = "x-argent-action";
    public static final String ROLES_KEY = "x-argent-roles";
    /** Сдержанные русские подписи только для однозначных типовых параметров. */
    private static final Map<String, String> DEFAULT_PARAMETERS = Map.ofEntries(
            Map.entry("id", "Идентификатор записи"),
            Map.entry("code", "Код объекта"),
            Map.entry("page", "Номер страницы (с нуля)"),
            Map.entry("size", "Число записей на странице"),
            Map.entry("search", "Строка поиска"),
            Map.entry("sort", "Поле сортировки"),
            Map.entry("direction", "Направление сортировки"),
            Map.entry("format", "Формат ответа"),
            Map.entry("queueId", "Идентификатор очереди"),
            Map.entry("taskId", "Идентификатор задачи"),
            Map.entry("entityType", "Код типа сущности"),
            Map.entry("file", "Загружаемый файл"));

    @Bean
    @ConditionalOnMissingBean(ArgentApiRoleResolver.class)
    ArgentApiRoleResolver argentApiRoleResolver(RoleRepository roles) {
        return new ArgentApiRoleResolver(roles);
    }

    @Bean
    @ConditionalOnMissingBean(ArgentApiRolesController.class)
    ArgentApiRolesController argentApiRolesController(
            ObjectProvider<RequestMappingHandlerMapping> mapping, ArgentApiRoleResolver resolver) {
        return new ArgentApiRolesController(mapping, resolver);
    }

    @Bean
    OperationCustomizer argentActionOperationCustomizer() {
        return (operation, handler) -> {
            String action = ArgentOpenApiSupport.action(handler);
            if (!action.isBlank()) operation.addExtension(ACTION_KEY, action);
            return operation;
        };
    }

    @Bean
    OpenApiCustomizer argentRolesOpenApiCustomizer(ArgentApiRoleResolver resolver) {
        return api -> {
            if (api.getComponents() == null) api.setComponents(new io.swagger.v3.oas.models.Components());
            api.getComponents().addSecuritySchemes("argentBearer",
                    new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"));
            if (api.getInfo() == null) {
                api.setInfo(new io.swagger.v3.oas.models.info.Info()
                        .title("API приложения AtomSkills").version("1.0")
                        .description("Документация на русском языке; технические названия сохранены."));
            }
            if (api.getPaths() == null) return;
            Map<String, List<ArgentApiRoleResolver.RoleView>> rolesByAction = new java.util.HashMap<>();
            api.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
                if (operation.getParameters() != null) operation.getParameters().forEach(parameter -> {
                    if ((parameter.getDescription() == null || parameter.getDescription().isBlank())
                            && parameter.getName() != null) {
                        String description = DEFAULT_PARAMETERS.get(parameter.getName());
                        if (description != null) parameter.setDescription(description);
                    }
                });
                String action = extensionAction(operation);
                if (action == null) return;
                List<ArgentApiRoleResolver.RoleView> roles;
                try {
                    roles = rolesByAction.computeIfAbsent(action, resolver::forAction);
                } catch (RuntimeException unavailableDatabase) {
                    // Swagger должен оставаться доступен, даже если БД временно недоступна.
                    return;
                }
                List<Map<String, String>> values = roles.stream().map(role -> {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("code", role.code());
                    item.put("name", role.name());
                    return item;
                }).toList();
                operation.addExtension(ROLES_KEY, values);
                operation.addSecurityItem(new SecurityRequirement().addList("argentBearer"));
                String detail = "**Доступ (действие):** `" + action + "`\n\n"
                        + "**Роли с этим действием:** "
                        + (roles.isEmpty() ? "нет назначенных ролей"
                        : roles.stream().map(role -> role.name() + " (`" + role.code() + "`)")
                            .collect(Collectors.joining(", ")))
                        + "\n\n_Роли вычислены по грантам; дополнительные проверки могут ограничивать доступ._";
                String original = operation.getDescription();
                if (original != null) {
                    int index = original.indexOf("**Доступ (действие):**");
                    if (index >= 0) original = original.substring(0, index).stripTrailing();
                }
                operation.setDescription((original == null || original.isBlank() ? "" : original + "\n\n") + detail);
            }));
        };
    }

    private static String extensionAction(Operation operation) {
        Object value = operation.getExtensions() == null ? null : operation.getExtensions().get(ACTION_KEY);
        return value instanceof String action && !action.isBlank() ? action : null;
    }
}
