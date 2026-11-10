package com.argent.servicecommon.queue.service;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Помечает публичный метод Spring-бина с одним аргументом DTO как обработчик сохраняемой задачи. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ArgentQueueHandler {
    /** Постоянный ключ обработчика; изменение ключа нарушит восстановление ранее поставленных задач. */
    String value();
}
