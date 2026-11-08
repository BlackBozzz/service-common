package com.argent.servicecommon.error.config;

import com.argent.servicecommon.error.ArgentExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Автоконфигурация единого обработчика ошибок.
 */
@AutoConfiguration
public class ErrorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ArgentExceptionHandler.class)
    public ArgentExceptionHandler argentExceptionHandler() {
        return new ArgentExceptionHandler();
    }
}
