package com.argent.servicecommon.mail;

import com.argent.servicecommon.queue.service.ArgentQueueHandler;
import com.argent.servicecommon.queue.service.ArgentQueueService;
import jakarta.mail.MessagingException;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Персистентная массовая почтовая рассылка: одна задача очереди = одно письмо одному адресату. */
public class ArgentQueuedMailService {
    public static final String QUEUE_NAME = "argent-mail";
    public static final String HANDLER_KEY = "argent.mail.send";

    private final ArgentMailService mail;
    private final ArgentQueueService queue;

    public ArgentQueuedMailService(ArgentMailService mail, ArgentQueueService queue) {
        this.mail = Objects.requireNonNull(mail);
        this.queue = Objects.requireNonNull(queue);
    }

    public ArgentQueuedMailResult queueToEmail(String email, ArgentMailMessage message) {
        return enqueue(mail.resolveEmail(email), message, null);
    }

    public ArgentQueuedMailResult queueToEmails(Collection<String> emails, ArgentMailMessage message) {
        return enqueue(mail.resolveEmails(emails), message, null);
    }

    public ArgentQueuedMailResult queueToUserId(UUID userId, ArgentMailMessage message) {
        return enqueue(mail.resolveUserId(userId), message, null);
    }

    public ArgentQueuedMailResult queueToUserIds(Collection<UUID> userIds, ArgentMailMessage message) {
        return enqueue(mail.resolveUserIds(userIds), message, null);
    }

    public ArgentQueuedMailResult queueToRole(String roleCode, ArgentMailMessage message) {
        return enqueue(mail.resolveRole(roleCode), message, null);
    }

    public ArgentQueuedMailResult queueToRoles(Collection<String> roleCodes, ArgentMailMessage message) {
        return enqueue(mail.resolveRoles(roleCodes), message, null);
    }

    public ArgentQueuedMailResult queueToAllEnabledUsers(ArgentMailMessage message) {
        return enqueue(mail.resolveAllEnabledUsers(), message, null);
    }

    /** Повтор вызова с тем же campaignKey не создаст повторных задач для тех же адресатов. */
    public ArgentQueuedMailResult queueToRole(String roleCode, ArgentMailMessage message, String campaignKey) {
        return enqueue(mail.resolveRole(roleCode), message, campaignKey);
    }

    public ArgentQueuedMailResult queueToEmails(Collection<String> emails, ArgentMailMessage message, String campaignKey) {
        return enqueue(mail.resolveEmails(emails), message, campaignKey);
    }

    public ArgentQueuedMailResult queueToUserIds(Collection<UUID> userIds, ArgentMailMessage message, String campaignKey) {
        return enqueue(mail.resolveUserIds(userIds), message, campaignKey);
    }

    private ArgentQueuedMailResult enqueue(ArgentMailRecipients.RecipientBatch batch,
                                           ArgentMailMessage message, String campaignKey) {
        Objects.requireNonNull(message, "Письмо обязательно");
        if (campaignKey != null && (campaignKey.isBlank() || campaignKey.length() > 100)) {
            throw new IllegalArgumentException("campaignKey: от 1 до 100 символов");
        }
        List<UUID> tasks = new ArrayList<>();
        for (String email : batch.emails()) {
            String key = campaignKey == null ? null : campaignKey + ":" + sha256(email);
            tasks.add(queue.createQueueTask(QUEUE_NAME, HANDLER_KEY,
                    new MailTask(email, message.subject(), message.body(), message.html()), 0, key));
        }
        return new ArgentQueuedMailResult(batch.selected(), tasks.size(), batch.skipped(), tasks);
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Важно: обработчик ОБЩЕДОСТУПЕН как метод Spring-бина, DTO восстанавливается из JSON. */
    @ArgentQueueHandler(HANDLER_KEY)
    public void sendQueuedMail(MailTask task) throws MessagingException, UnsupportedEncodingException {
        mail.deliver(task.email(), new ArgentMailMessage(task.subject(), task.body(), task.html()));
    }

    public record MailTask(String email, String subject, String body, boolean html) { }
}
