package com.argent.servicecommon.transfer;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки импорта и экспорта {@code argent.transfer.*}; лимиты должны быть больше нуля. Лимиты multipart Spring
 * приложение поднимает до {@code max-upload-bytes} отдельно.
 */
@ConfigurationProperties("argent.transfer")
public record TransferProperties(
        @DefaultValue("20971520") long maxUploadBytes,
        @DefaultValue("100000") int maxRows,
        @DefaultValue("fail-all") ImportOnError importOnError,
        @DefaultValue("1000") @Positive int exportMaxColumns,
        @DefaultValue("2000000") @Positive long exportMaxCells
) {

    public TransferProperties {
        if (maxUploadBytes <= 0) {
            throw new IllegalArgumentException("argent.transfer.max-upload-bytes должен быть больше нуля");
        }
        if (maxRows <= 0) {
            throw new IllegalArgumentException("argent.transfer.max-rows должен быть больше нуля");
        }
        if (exportMaxColumns <= 0) {
            throw new IllegalArgumentException("argent.transfer.export-max-columns должен быть больше нуля");
        }
        if (exportMaxCells <= 0) {
            throw new IllegalArgumentException("argent.transfer.export-max-cells должен быть больше нуля");
        }
        importOnError = importOnError == null ? ImportOnError.FAIL_ALL : importOnError;
    }

    /** Что делать со строками, которые не удалось импортировать. */
    public enum ImportOnError {
        /** Одна транзакция на лист: при любой ошибке лист откатывается, отчёт содержит все ошибки. */
        FAIL_ALL,
        /** Ошибочные строки пропускаются и попадают в отчёт, остальные сохраняются. */
        SKIP_ROW
    }
}
