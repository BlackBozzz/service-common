package com.argent.servicecommon.docs.config;

import com.argent.servicecommon.docs.ArgentDocsController;
import jakarta.persistence.EntityManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/** Отключается argent.docs.enabled=false (например, в production). */
@AutoConfiguration
@ConditionalOnProperty(prefix = "argent.docs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ArgentDocsAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ArgentDocsController.class)
    ArgentDocsController argentDocsController(JdbcTemplate jdbc, EntityManager em) {
        return new ArgentDocsController(jdbc, em);
    }
}
