package com.argent.servicecommon.security.web;

import com.argent.servicecommon.security.grants.GrantOp;
import com.argent.servicecommon.security.grants.GrantsAuthorities;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.function.Supplier;

/**
 * Правило доступа к справочникам по URL {@code /api/argent-dictionary/{code}/**}.
 *
 * <p>Код справочника берётся из переменной пути {@code code}. Запросы {@code GET} и {@code HEAD}
 * требуют гранта {@code read} на таблицу справочника, остальные методы — гранта {@code write};
 * гранты на {@code *} учитываются. {@code write} не подразумевает {@code read}.
 * Анонимный или неаутентифицированный запрос и запрос без переменной {@code code} отклоняются.</p>
 */
public class DictionaryAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    /** Имя переменной пути с кодом справочника. */
    public static final String CODE_VARIABLE = "code";

    private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
            RequestAuthorizationContext context) {
        Authentication current = authentication.get();
        if (current == null || !current.isAuthenticated() || trustResolver.isAnonymous(current)) {
            return new AuthorizationDecision(false);
        }
        String code = context.getVariables().get(CODE_VARIABLE);
        if (code == null || code.isBlank()) {
            return new AuthorizationDecision(false);
        }
        return new AuthorizationDecision(
                GrantsAuthorities.allowsTable(current.getAuthorities(), code, requiredOp(context)));
    }

    private static GrantOp requiredOp(RequestAuthorizationContext context) {
        String method = context.getRequest().getMethod();
        return HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method) ? GrantOp.READ : GrantOp.WRITE;
    }
}
