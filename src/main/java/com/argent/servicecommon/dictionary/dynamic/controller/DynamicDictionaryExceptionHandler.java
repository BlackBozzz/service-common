package com.argent.servicecommon.dictionary.dynamic.controller;

import com.argent.servicecommon.dictionary.dynamic.exception.DynamicDictionaryBadRequestException;
import com.argent.servicecommon.dictionary.dynamic.exception.DynamicDictionaryNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.List;
import java.util.Map;

/**
 * Единое представление ошибок библиотечных REST-контроллеров.
 *
 * <p>Не раскрывает клиенту текст ошибок SQL и внутренние детали исключений.</p>
 */
@RestControllerAdvice(basePackages = "com.argent.servicecommon.dictionary")
public class DynamicDictionaryExceptionHandler {

    @ExceptionHandler(DynamicDictionaryNotFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(DynamicDictionaryNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "Справочник не найден", exception.getMessage());
    }

    @ExceptionHandler({DynamicDictionaryBadRequestException.class, IllegalArgumentException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ProblemDetail> badRequest(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "Некорректный запрос",
                exception instanceof HttpMessageNotReadableException
                        ? "Некорректный JSON или тип поля запроса."
                        : exception.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> conflict(DataIntegrityViolationException exception) {
        return error(HttpStatus.CONFLICT, "Конфликт данных",
                "Запись нарушает ограничение целостности базы данных.");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> validation(ConstraintViolationException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Данные справочника не прошли серверную проверку.");
        detail.setTitle("Ошибка валидации");
        List<Map<String, String>> violations = exception.getConstraintViolations()
                .stream().map(violation -> Map.of(
                        "field", violation.getPropertyPath().toString(),
                        "message", violation.getMessage()))
                .toList();
        detail.setProperty("errors", violations);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(detail);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> invalidArgument(MethodArgumentNotValidException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Параметры запроса не прошли проверку.");
        detail.setTitle("Ошибка валидации");
        detail.setProperty("errors", exception.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "message",
                        error.getDefaultMessage() == null ? "Недопустимое значение." : error.getDefaultMessage()))
                .toList());
        return ResponseEntity.badRequest().body(detail);
    }

    private static ResponseEntity<ProblemDetail> error(HttpStatus status, String title, String message) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status,
                message == null || message.isBlank() ? "Ошибка обработки запроса." : message);
        detail.setTitle(title);
        return ResponseEntity.status(status).body(detail);
    }
}
