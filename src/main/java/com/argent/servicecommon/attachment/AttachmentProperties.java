package com.argent.servicecommon.attachment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Locale;

/** Настройки вложений {@code argent.attachments.*}; лимиты multipart Spring приложение поднимает отдельно. */
@ConfigurationProperties("argent.attachments")
public record AttachmentProperties(
        @DefaultValue("./data/attachments") String root,
        @DefaultValue("20971520") long maxSizeBytes,
        @DefaultValue("") List<String> allowedTypes
) {

    public AttachmentProperties {
        if (root == null || root.isBlank()) {
            throw new IllegalArgumentException("argent.attachments.root не задан");
        }
        if (maxSizeBytes <= 0) {
            throw new IllegalArgumentException("argent.attachments.max-size-bytes должен быть больше нуля");
        }
        allowedTypes = allowedTypes == null ? List.of() : allowedTypes.stream()
                .filter(type -> type != null && !type.isBlank())
                .map(type -> type.strip().toLowerCase(Locale.ROOT))
                .toList();
    }
}
