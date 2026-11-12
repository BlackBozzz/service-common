package com.argent.servicecommon.mail;

import com.argent.servicecommon.auth.repository.UserRepository;
import com.argent.servicecommon.mail.config.ArgentMailProperties;
import com.argent.servicecommon.security.repository.RoleRepository;
import com.argent.servicecommon.security.repository.UserRoleRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.io.UnsupportedEncodingException;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

/** Публичный сервис SMTP. Массовые sendTo* — синхронные; для рассылок предпочтительнее ArgentQueuedMailService. */
public class ArgentMailService {
    private static final Logger log = LoggerFactory.getLogger(ArgentMailService.class);

    private final JavaMailSender sender;
    private final ArgentMailProperties properties;
    private final ArgentMailRecipients recipients;

    public ArgentMailService(JavaMailSender sender, ArgentMailProperties properties,
                             UserRepository users, RoleRepository roles, UserRoleRepository userRoles) {
        this.sender = Objects.requireNonNull(sender);
        this.properties = Objects.requireNonNull(properties);
        this.recipients = new ArgentMailRecipients(users, roles, userRoles, properties.getMaxRecipientsPerCall());
        ArgentMailRecipients.normalize(properties.getFrom());
        if (properties.getReplyTo() != null && !properties.getReplyTo().isBlank()) {
            ArgentMailRecipients.normalize(properties.getReplyTo());
        }
    }

    /** Отправка одному адресу (включая внешнего адресата, не зарегистрированного в системе). */
    public ArgentMailResult sendToEmail(String email, ArgentMailMessage message) {
        return send(recipients.email(email), message);
    }

    public ArgentMailResult sendToEmails(Collection<String> emails, ArgentMailMessage message) {
        return send(recipients.emails(emails), message);
    }

    public ArgentMailResult sendToUserId(UUID id, ArgentMailMessage message) {
        return send(recipients.userId(id), message);
    }

    public ArgentMailResult sendToUserIds(Collection<UUID> ids, ArgentMailMessage message) {
        return send(recipients.userIds(ids), message);
    }

    /** По коду роли (например, ADMIN), пользователь с несколькими ролями получает одно письмо. */
    public ArgentMailResult sendToRole(String roleCode, ArgentMailMessage message) {
        return send(recipients.role(roleCode), message);
    }

    /** Объединение нескольких ролей без дублирования адресатов. */
    public ArgentMailResult sendToRoles(Collection<String> roleCodes, ArgentMailMessage message) {
        return send(recipients.roles(roleCodes), message);
    }

    /** Рассылка всем АКТИВНЫМ пользователям, у которых указан email. */
    public ArgentMailResult sendToAllEnabledUsers(ArgentMailMessage message) {
        return send(recipients.allUsers(), message);
    }

    private ArgentMailResult send(ArgentMailRecipients.RecipientBatch batch, ArgentMailMessage message) {
        Objects.requireNonNull(message, "Письмо обязательно");
        int delivered = 0;
        int failures = 0;
        for (String email : batch.emails()) {
            try {
                deliver(email, message);
                delivered++;
            } catch (MailException | MessagingException | UnsupportedEncodingException exception) {
                failures++;
                log.warn("Не удалось отправить письмо адресату {}: {}", email, exception.getMessage());
            }
        }
        return new ArgentMailResult(batch.selected(), delivered, batch.skipped(), failures);
    }

    /** Один SMTP-запрос. Ошибка выбрасывается, чтобы Argent Queue мог выполнить повтор. */
    void deliver(String email, ArgentMailMessage message) throws MessagingException, UnsupportedEncodingException {
        Objects.requireNonNull(message, "Письмо обязательно");
        MimeMessage mime = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mime, false, "UTF-8");
        String from = ArgentMailRecipients.normalize(properties.getFrom());
        if (properties.getFromName() == null || properties.getFromName().isBlank()) helper.setFrom(from);
        else helper.setFrom(from, properties.getFromName());
        helper.setTo(ArgentMailRecipients.normalize(email));
        if (properties.getReplyTo() != null && !properties.getReplyTo().isBlank()) {
            helper.setReplyTo(ArgentMailRecipients.normalize(properties.getReplyTo()));
        }
        helper.setSubject(message.subject());
        helper.setText(message.body(), message.html());
        sender.send(mime);
    }

    ArgentMailRecipients.RecipientBatch resolveEmails(Collection<String> emails) { return recipients.emails(emails); }
    ArgentMailRecipients.RecipientBatch resolveEmail(String email) { return recipients.email(email); }
    ArgentMailRecipients.RecipientBatch resolveUserId(UUID id) { return recipients.userId(id); }
    ArgentMailRecipients.RecipientBatch resolveUserIds(Collection<UUID> ids) { return recipients.userIds(ids); }
    ArgentMailRecipients.RecipientBatch resolveRole(String role) { return recipients.role(role); }
    ArgentMailRecipients.RecipientBatch resolveRoles(Collection<String> roles) { return recipients.roles(roles); }
    ArgentMailRecipients.RecipientBatch resolveAllEnabledUsers() { return recipients.allUsers(); }
}
