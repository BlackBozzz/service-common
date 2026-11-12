package com.argent.servicecommon.audit.config;

import com.argent.servicecommon.audit.AuditController;
import com.argent.servicecommon.live.ArgentLivePublisher;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Автоконфигурация аудита: слушатель Hibernate для {@code @Audited}, {@link AuditRecorder} для REPLACE и REST
 * {@code GET /api/audit}; включается, только если есть {@link AuditEntryRepository}.
 *
 * <p>Слушатель кладётся в {@code hibernate.integrator_provider} через {@link HibernatePropertiesCustomizer}
 * (интеграторы провайдера приложения сохраняются), поэтому собственный {@code EntityManagerFactory} приложения
 * обходит аудит. Обрабатывается до автоконфигурации импорта ({@code before = TransferAutoConfiguration}), чтобы
 * его {@link AuditRecorder} заменил рекордер импорта без действий.</p>
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
    public AuditWriter auditWriter(JdbcTemplate jdbcTemplate, ObjectProvider<ArgentLivePublisher> live) {
        AuditWriter writer = new AuditWriter(jdbcTemplate);
        live.ifAvailable(publisher -> writer.setOnAuditChanged(publisher::auditChanged));
        return writer;
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
