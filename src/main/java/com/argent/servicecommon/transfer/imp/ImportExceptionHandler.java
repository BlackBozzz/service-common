package com.argent.servicecommon.transfer.imp;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Ответ на отменённый импорт: 400 ProblemDetail с заголовком «Ошибка импорта» и полным отчётом
 * ({@link ImportReport}) в свойстве {@code report}.
 *
 * <p>Превышение лимита multipart Spring ({@code spring.servlet.multipart.max-file-size}) отвечает тем же
 * 400 «Файл превышает допустимый размер», что и лимит {@code argent.transfer.max-upload-bytes}.</p>
 *
 * <p>Порядок выше общего обработчика, чтобы его обработчик {@code Exception} не перехватил ошибку.</p>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ImportExceptionHandler {

    @ExceptionHandler(ImportFailedException.class)
    public ProblemDetail importFailed(ImportFailedException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        detail.setTitle("Ошибка импорта");
        detail.setProperty("report", exception.report());
        return detail;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail uploadTooLarge(MaxUploadSizeExceededException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, TableReader.TOO_LARGE);
        detail.setTitle("Некорректный запрос");
        return detail;
    }
}
