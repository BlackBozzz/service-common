package com.argent.servicecommon.docs.openapi;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.argent.servicecommon.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Динамические роли по endpoints. В отличие от кэшируемого OpenAPI всегда читает текущие гранты. */
@RestController
@RequestMapping("/api/argent-docs")
@Tag(name = "Документация")
public class ArgentApiRolesController {
    private final ObjectProvider<RequestMappingHandlerMapping> routes;
    private final ArgentApiRoleResolver resolver;

    public ArgentApiRolesController(ObjectProvider<RequestMappingHandlerMapping> routes, ArgentApiRoleResolver resolver) {
        this.routes = routes;
        this.resolver = resolver;
    }

    public record AccessView(String path, String method, String action,
                             List<ArgentApiRoleResolver.RoleView> roles) { }

    @ArgentApiAction("admin")
    @GetMapping("/roles")
    @Operation(summary = "Получить действующие роли для методов API")
    public List<AccessView> roles() {
        if (!CurrentUser.hasAction("admin")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Необходимо действие admin");
        }
        List<AccessView> results = new ArrayList<>();
        routes.getObject().getHandlerMethods().forEach((mapping, handler) -> {
            String action = ArgentOpenApiSupport.action(handler);
            if (action.isBlank()) return;
            Set<String> paths = paths(mapping);
            Set<String> methods = new TreeSet<>();
            mapping.getMethodsCondition().getMethods().forEach(m -> methods.add(m.name()));
            if (methods.isEmpty()) methods.add("*");
            var roleViews = resolver.forAction(action);
            for (String path : paths) for (String method : methods) {
                results.add(new AccessView(path, method, action, roleViews));
            }
        });
        return results.stream().sorted(Comparator.comparing(AccessView::path)
                .thenComparing(AccessView::method)).toList();
    }

    private Set<String> paths(RequestMappingInfo info) {
        Set<String> result = new TreeSet<>();
        if (info.getPathPatternsCondition() != null) {
            result.addAll(info.getPathPatternsCondition().getPatternValues());
        }
        if (info.getPatternsCondition() != null) {
            result.addAll(info.getPatternsCondition().getPatterns());
        }
        return result;
    }
}
