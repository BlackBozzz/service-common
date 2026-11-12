package com.argent.servicecommon.mail;

/** Содержимое письма: простой текст или HTML. Адресат задаётся методом отправки. */
public record ArgentMailMessage(String subject, String body, boolean html) {
    public ArgentMailMessage {
        if (subject == null || subject.isBlank() || subject.length() > 255 || subject.contains("\r") || subject.contains("\n")) {
            throw new IllegalArgumentException("Тема письма обязательна, не длиннее 255 символов и без переводов строк");
        }
        if (body == null) throw new IllegalArgumentException("Текст письма не может быть null");
    }

    public static ArgentMailMessage text(String subject, String body) {
        return new ArgentMailMessage(subject, body, false);
    }

    public static ArgentMailMessage html(String subject, String body) {
        return new ArgentMailMessage(subject, body, true);
    }
}
