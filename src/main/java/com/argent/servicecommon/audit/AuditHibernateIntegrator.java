package com.argent.servicecommon.audit;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;

import java.util.Objects;

/**
 * Подключает {@link AuditEventListener} к событиям post-insert/update/delete; передаётся в Hibernate через
 * {@code hibernate.integrator_provider}, см. {@link com.argent.servicecommon.audit.config.AuditAutoConfiguration}.
 */
public class AuditHibernateIntegrator implements Integrator {

    private final AuditEventListener listener;

    public AuditHibernateIntegrator(AuditEventListener listener) {
        this.listener = Objects.requireNonNull(listener);
    }

    @Override
    public void integrate(Metadata metadata, BootstrapContext bootstrapContext,
                          SessionFactoryImplementor sessionFactory) {
        EventListenerRegistry registry = sessionFactory.getServiceRegistry().getService(EventListenerRegistry.class);
        registry.appendListeners(EventType.POST_INSERT, listener);
        registry.appendListeners(EventType.POST_UPDATE, listener);
        registry.appendListeners(EventType.POST_DELETE, listener);
    }
}
