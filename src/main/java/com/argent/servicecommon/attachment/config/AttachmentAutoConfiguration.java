package com.argent.servicecommon.attachment.config;

import com.argent.servicecommon.attachment.AttachmentController;
import com.argent.servicecommon.attachment.AttachmentProperties;
import com.argent.servicecommon.attachment.AttachmentService;
import com.argent.servicecommon.attachment.AttachmentStorage;
import com.argent.servicecommon.attachment.repository.AttachmentRepository;
import com.argent.servicecommon.audit.AuditWriter;
import com.argent.servicecommon.audit.config.AuditAutoConfiguration;
import com.argent.servicecommon.config.ServiceCommonAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Автоконфигурация вложений: хранилище, {@link AttachmentService} и REST {@link AttachmentController};
 * включается при {@link AttachmentRepository} и {@code argent.attachments.enabled != false}. Аудит пишется,
 * только если есть {@link AuditWriter}.
 */
@AutoConfiguration(
        after = {ServiceCommonAutoConfiguration.class, AuditAutoConfiguration.class},
        afterName = {
                "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration"
        }
)
@ConditionalOnBean(AttachmentRepository.class)
@ConditionalOnProperty(prefix = "argent.attachments", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AttachmentProperties.class)
public class AttachmentAutoConfiguration {

    /** @throws IllegalStateException папка хранилища недоступна для создания или записи */
    @Bean
    @ConditionalOnMissingBean(AttachmentStorage.class)
    public AttachmentStorage attachmentStorage(AttachmentProperties properties) {
        Path root;
        try {
            root = Path.of(properties.root()).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            throw new IllegalStateException("Папка вложений недоступна: " + properties.root(), exception);
        }
        try {
            Path tmp = Files.createDirectories(root.resolve("tmp"));
            // Пробная запись: папка может существовать, но быть только для чтения.
            Files.delete(Files.createTempFile(tmp, "probe-", ".tmp"));
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Папка вложений недоступна: " + root, exception);
        }
        return new AttachmentStorage(root);
    }

    @Bean
    @ConditionalOnMissingBean(AttachmentService.class)
    public AttachmentService attachmentService(AttachmentRepository attachmentRepository,
                                               AttachmentStorage attachmentStorage,
                                               AttachmentProperties attachmentProperties,
                                               ObjectProvider<AuditWriter> auditWriter) {
        return new AttachmentService(attachmentRepository, attachmentStorage, attachmentProperties, auditWriter);
    }

    @Bean
    @ConditionalOnMissingBean(AttachmentController.class)
    public AttachmentController attachmentController(AttachmentService attachmentService) {
        return new AttachmentController(attachmentService);
    }
}
