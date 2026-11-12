package com.argent.servicecommon.mail.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Имя отправителя, адрес и ограничения. Параметры SMTP хранятся в spring.mail.*. */
@ConfigurationProperties(prefix = "argent.mail")
public class ArgentMailProperties {
    private String from;
    private String fromName;
    private String replyTo;
    private int maxRecipientsPerCall = 5000;

    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }
    public String getFromName() { return fromName; }
    public void setFromName(String fromName) { this.fromName = fromName; }
    public String getReplyTo() { return replyTo; }
    public void setReplyTo(String replyTo) { this.replyTo = replyTo; }
    public int getMaxRecipientsPerCall() { return Math.max(1, maxRecipientsPerCall); }
    public void setMaxRecipientsPerCall(int value) { this.maxRecipientsPerCall = value; }
}
