package com.argent.servicecommon.audit.config;

import com.argent.servicecommon.audit.AuditController;
import com.argent.servicecommon.audit.AuditEventListener;
import com.argent.servicecommon.audit.AuditHibernateIntegrator;
import com.argent.servicecommon.audit.AuditWriter;
import com.argent.servicecommon.audit.DatabaseAuditRecorder;
import com.argent.servicecommon.audit.repository.AuditEntryRepository;
import com.argent.servicecommon.config.ServiceCommonAutoConfiguration;
import com.argent.servicecommon.transfer.config.TransferAutoConfiguration;
import com.argent.servicecommon.transfer.imp.AuditRecorder;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.jpa.boot.spi.IntegratorProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Автоконфигурация аудита: слушатель Hibernate для сущностей с {@code @Audited}, запись REPLACE
 * импорта ({@link AuditRecorder}) и REST {@code GET /api/audit}.
 *
 * <p>Слушатель подключается через {@link HibernatePropertiesCustomizer}: в свойство
 * {@code hibernate.integrator_provider} кладётся провайдер с {@link AuditHibernateIntegrator}; интеграторы
 * провайдера, заданного приложением, сохраняются. Включается, только если в контексте есть
 * {@link AuditEntryRepository}. Обрабатывается до автоконфигурации импорта, чтобы заменить её
 * {@link AuditRecorder} без действий.</p>
 */
@AutoConfiguration(
        after = ServiceCommonAutoConfiguration.class,
        before = TransferAutoConfiguration.class,
        afterName = {
                "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration"
        }
)
@ConditionalOnBean(AuditEntryRepository.class)
public class AuditAutoConfiguration {

    /** Имя свойства Hibernate со списком интеграторов. */
    public static final String INTEGRATOR_PROVIDER = "hibernate.integrator_provider";

    @Bean
    @ConditionalOnMissingBean(AuditWriter.class)
    public AuditWriter auditWriter(JdbcTemplate jdbcTemplate) {
        return new AuditWriter(jdbcTemplate);
    }

    @Bean
    public HibernatePropertiesCustomizer auditHibernatePropertiesCustomizer(AuditWriter auditWriter) {
        Integrator integrator = new AuditHibernateIntegrator(new AuditEventListener(auditWriter));
        return properties -> {
            List<Integrator> integrators = new ArrayList<>();
            if (properties.get(INTEGRATOR_PROVIDER) instanceof IntegratorProvider existing) {
                integrators.addAll(existing.getIntegrators());
            }
            integrators.add(integrator);
            List<Integrator> all = List.copyOf(integrators);
            IntegratorProvider provider = () -> all;
            properties.put(INTEGRATOR_PROVIDER, provider);
        };
    }

    @Bean
    @ConditionalOnMissingBean(AuditRecorder.class)
    public AuditRecorder databaseAuditRecorder(AuditWriter auditWriter) {
        return new DatabaseAuditRecorder(auditWriter);
    }

    @Bean
    @ConditionalOnMissingBean(AuditController.class)
    public AuditController auditController(AuditEntryRepository auditEntryRepository) {
        return new AuditController(auditEntryRepository);
    }
}
