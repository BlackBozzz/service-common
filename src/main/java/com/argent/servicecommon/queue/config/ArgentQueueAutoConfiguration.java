package com.argent.servicecommon.queue.config;

import com.argent.servicecommon.queue.controller.ArgentQueueController;
import com.argent.servicecommon.live.ArgentLivePublisher;
import com.argent.servicecommon.queue.service.ArgentQueueRegistry;
import com.argent.servicecommon.queue.service.ArgentQueueService;
import com.argent.servicecommon.queue.service.ArgentQueueStore;
import com.argent.servicecommon.queue.service.ArgentQueueWorker;
import com.argent.servicecommon.security.config.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/** Автоматически подключает очередь ко всем приложениям, использующим service-common. */
@AutoConfiguration(after = SecurityAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties(ArgentQueueProperties.class)
public class ArgentQueueAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ArgentQueueStore.class)
    ArgentQueueStore argentQueueStore(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                                      ObjectProvider<ArgentLivePublisher> live) {
        ArgentQueueStore store = new ArgentQueueStore(jdbc, txManager);
        live.ifAvailable(publisher -> store.setOnQueueChanged(publisher::queueChanged));
        return store;
    }

    @Bean
    @ConditionalOnMissingBean(ArgentQueueRegistry.class)
    ArgentQueueRegistry argentQueueRegistry(ApplicationContext context, JsonMapper mapper) {
        return new ArgentQueueRegistry(context, mapper);
    }

    @Bean
    @ConditionalOnMissingBean(ArgentQueueService.class)
    ArgentQueueService argentQueueService(ArgentQueueStore store, ArgentQueueRegistry registry, JsonMapper mapper) {
        return new ArgentQueueService(store, registry, mapper);
    }

    @Bean
    @ConditionalOnMissingBean(ArgentQueueWorker.class)
    @ConditionalOnProperty(name = "argent.queue.enabled", havingValue = "true", matchIfMissing = true)
    ArgentQueueWorker argentQueueWorker(ArgentQueueStore store, ArgentQueueRegistry registry,
                                       ArgentQueueProperties properties) {
        return new ArgentQueueWorker(store, registry, properties);
    }

    @Bean
    @ConditionalOnMissingBean(ArgentQueueController.class)
    @ConditionalOnProperty(name = "argent.security.enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    ArgentQueueController argentQueueController(ArgentQueueService service) {
        return new ArgentQueueController(service);
    }
}
