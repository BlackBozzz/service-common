package com.argent.servicecommon.querydsl.config;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Общая JPAQueryFactory для приложений, подключающих service-common. */
@AutoConfiguration(afterName = "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration")
@ConditionalOnClass(JPAQueryFactory.class)
public class ArgentQuerydslAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(JPAQueryFactory.class)
    public JPAQueryFactory argentQuerydslQueryFactory(EntityManager entityManager) {
        return new JPAQueryFactory(entityManager);
    }
}
