package com.argent.servicecommon.queue.service;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Реестр Java-методов, устойчивых к перезапуску: в БД хранится только их постоянный ключ. */
public class ArgentQueueRegistry implements SmartInitializingSingleton {
    private static final Pattern KEY = Pattern.compile("[a-zA-Z][a-zA-Z0-9._-]{0,159}");
    private final ApplicationContext context;
    private final JsonMapper mapper;
    private volatile Map<String, RegisteredHandler> handlers = Map.of();

    public ArgentQueueRegistry(ApplicationContext context, JsonMapper mapper) {
        this.context = Objects.requireNonNull(context);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public void afterSingletonsInstantiated() {
        Map<String, RegisteredHandler> found = new HashMap<>();
        for (String name : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(name);
            if (type == null) continue;
            boolean candidate = false;
            for (Method method : ReflectionUtils.getAllDeclaredMethods(type)) {
                if (AnnotatedElementUtils.findMergedAnnotation(method, ArgentQueueHandler.class) != null) {
                    candidate = true;
                    break;
                }
            }
            if (!candidate) continue;
            Object bean = context.getBean(name);
            Class<?> target = AopUtils.getTargetClass(bean);
            for (Method method : ReflectionUtils.getAllDeclaredMethods(target)) {
                ArgentQueueHandler annotation = AnnotatedElementUtils.findMergedAnnotation(method, ArgentQueueHandler.class);
                if (annotation == null) continue;
                String key = annotation.value();
                if (!KEY.matcher(key).matches()) {
                    throw new IllegalStateException("Некорректный ключ обработчика очереди: " + key);
                }
                if (!Modifier.isPublic(method.getModifiers()) || method.getParameterCount() != 1
                        || !method.getReturnType().equals(Void.TYPE)) {
                    throw new IllegalStateException("Обработчик очереди должен быть public void методом с одним DTO: " + method);
                }
                if (!method.getDeclaringClass().isAssignableFrom(bean.getClass())) {
                    throw new IllegalStateException("Обработчик очереди должен быть доступен через Spring-прокси: " + method);
                }
                RegisteredHandler previous = found.putIfAbsent(key, new RegisteredHandler(bean, method));
                if (previous != null) throw new IllegalStateException("Дублируется обработчик Argent Queue: " + key);
            }
        }
        handlers = Map.copyOf(found);
    }

    /** Позволяет проверить наличие зарегистрированного обработчика при создании задания. */
    public boolean contains(String key) {
        return handlers.containsKey(key);
    }

    /** Вызывает обычный метод Spring-бина, восстанавливая DTO из сохранённого JSON. */
    public void execute(String key, String json) throws Exception {
        RegisteredHandler handler = handlers.get(key);
        if (handler == null) throw new IllegalStateException("Не зарегистрирован обработчик очереди: " + key);
        Object dto = mapper.readValue(json, handler.method().getParameterTypes()[0]);
        try {
            handler.method().invoke(handler.bean(), dto);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) throw checked;
            if (cause instanceof Error fatal) throw fatal;
            throw new IllegalStateException(cause);
        }
    }

    private record RegisteredHandler(Object bean, Method method) { }
}
