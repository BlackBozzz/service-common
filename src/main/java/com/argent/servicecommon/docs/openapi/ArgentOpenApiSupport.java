package com.argent.servicecommon.docs.openapi;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Единое разрешение кода действия: явная @ArgentApiAction или простое @PreAuthorize. */
public final class ArgentOpenApiSupport {
    private static final Pattern ACTION = Pattern.compile("@argentAccess\\.action\\(['\"]([a-z0-9_.-]+)['\"]\\)");

    private ArgentOpenApiSupport() { }

    public static String action(HandlerMethod handler) {
        return action(handler.getMethod(), handler.getBeanType());
    }

    public static String action(Method method, Class<?> controllerClass) {
        ArgentApiAction explicit = AnnotatedElementUtils.findMergedAnnotation(method, ArgentApiAction.class);
        if (explicit == null) {
            explicit = AnnotatedElementUtils.findMergedAnnotation(controllerClass, ArgentApiAction.class);
        }
        if (explicit != null) return explicit.value();
        PreAuthorize security = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
        if (security == null) {
            security = AnnotatedElementUtils.findMergedAnnotation(controllerClass, PreAuthorize.class);
        }
        if (security == null) return "";
        Matcher match = ACTION.matcher(security.value());
        // Сложные SpEL выражения не интерпретируем как гарантию доступа.
        return match.find() ? match.group(1) : "";
    }
}
