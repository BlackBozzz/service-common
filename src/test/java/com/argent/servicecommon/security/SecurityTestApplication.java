package com.argent.servicecommon.security;

import com.argent.servicecommon.attachment.entity.Attachment;
import com.argent.servicecommon.attachment.repository.AttachmentRepository;
import com.argent.servicecommon.audit.entity.AuditEntry;
import com.argent.servicecommon.audit.repository.AuditEntryRepository;
import com.argent.servicecommon.auth.entity.User;
import com.argent.servicecommon.auth.repository.UserRepository;
import com.argent.servicecommon.dictionary.entity.CommonDictionary;
import com.argent.servicecommon.dictionary.repository.CommonDictionaryRepository;
import com.argent.servicecommon.security.entity.Role;
import com.argent.servicecommon.security.repository.RoleRepository;
import com.argent.servicecommon.sequence.entity.CommonSequence;
import com.argent.servicecommon.sequence.repository.CommonSequenceRepository;
import com.argent.servicecommon.status.entity.CommonStatus;
import com.argent.servicecommon.status.repository.CommonStatusRepository;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Общая тестовая конфигурация для интеграционных тестов аутентификации и безопасности.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan(basePackageClasses = {
        CommonDictionary.class, User.class, Role.class,
        CommonStatus.class, CommonSequence.class, AuditEntry.class, Attachment.class
})
@EnableJpaRepositories(basePackageClasses = {
        CommonDictionaryRepository.class, UserRepository.class, RoleRepository.class,
        CommonStatusRepository.class, CommonSequenceRepository.class, AuditEntryRepository.class,
        AttachmentRepository.class
})
public class SecurityTestApplication {
}
