package com.argent.servicecommon.mail.config;

import com.argent.servicecommon.auth.repository.UserRepository;
import com.argent.servicecommon.mail.ArgentMailService;
import com.argent.servicecommon.mail.ArgentQueuedMailService;
import com.argent.servicecommon.queue.service.ArgentQueueService;
import com.argent.servicecommon.queue.config.ArgentQueueAutoConfiguration;
import com.argent.servicecommon.security.repository.RoleRepository;
import com.argent.servicecommon.security.repository.UserRoleRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.mail.javamail.JavaMailSender;

/** Подключается только если argent.mail.enabled=true. При отключении не требует SMTP. */
@AutoConfiguration(after = ArgentQueueAutoConfiguration.class,
        afterName = "org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration")
@ConditionalOnProperty(prefix = "argent.mail", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ArgentMailProperties.class)
public class ArgentMailAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ArgentMailService.class)
    ArgentMailService argentMailService(ObjectProvider<JavaMailSender> mailSender,
            ArgentMailProperties properties, UserRepository users, RoleRepository roles,
            UserRoleRepository userRoles) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) throw new IllegalStateException(
                "Argent Mail включён, но JavaMailSender отсутствует. Настройте spring.mail.host и SMTP-параметры");
        return new ArgentMailService(sender, properties, users, roles, userRoles);
    }

    @Bean
    @ConditionalOnBean(ArgentQueueService.class)
    @ConditionalOnMissingBean(ArgentQueuedMailService.class)
    @ConditionalOnProperty(prefix = "argent.queue", name = "enabled", havingValue = "true", matchIfMissing = true)
    ArgentQueuedMailService argentQueuedMailService(ArgentMailService mail, ArgentQueueService queue) {
        return new ArgentQueuedMailService(mail, queue);
    }
}
