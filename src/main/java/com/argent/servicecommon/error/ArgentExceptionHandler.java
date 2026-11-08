package com.argent.servicecommon.error;

import com.argent.servicecommon.auth.exception.RegistrationDisabledException;
import com.argent.servicecommon.dictionary.dynamic.exception.DynamicDictionaryBadRequestException;
import com.argent.servicecommon.dictionary.dynamic.exception.DynamicDictionaryNotFoundException;
import com.argent.servicecommon.security.exception.GrantsAccessDeniedException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;

/**
 * Единый обработчик ошибок REST-контроллеров в формате {@link ProblemDetail}.
 *
 * <p>Ошибки валидации содержат список {@code errors} из пар {@code field}/{@code message}.
 * Текст SQL, стек вызовов и внутренние детали исключений клиенту не передаются.</p>
 */
@RestControllerAdvice
public class ArgentExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ArgentExceptionHandler.class);

    private static final String VALIDATION_TITLE = "Ошибка валидации";
    private static final String BAD_REQUEST_TITLE = "Некорректный запрос";
    private static final String CONFLICT_TITLE = "Конфликт данных";
    private static final String INTERNAL_MESSAGE = "Внутренняя ошибка сервера";

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> fieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return handleExceptionInternal(exception, validation("Параметры запроса не прошли проверку.", errors),
                headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(exception,
                problem(HttpStatus.BAD_REQUEST, BAD_REQUEST_TITLE, "Некорректный JSON или тип поля запроса."),
                headers, HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail constraintViolation(ConstraintViolationException exception) {
        List<Map<String, String>> errors = exception.getConstraintViolations().stream()
                .map(violation -> fieldError(violation.getPropertyPath().toString(), violation.getMessage()))
                .toList();
        return validation("Данные не прошли серверную проверку.", errors);
    }

    @ExceptionHandler(ValidationErrorsException.class)
    public ProblemDetail validationErrors(ValidationErrorsException exception) {
        List<Map<String, String>> errors = exception.getErrors().entrySet().stream()
                .map(entry -> fieldError(entry.getKey(), entry.getValue()))
                .toList();
        return validation("Данные не прошли проверку.", errors);
    }

    @ExceptionHandler({IllegalArgumentException.class, DynamicDictionaryBadRequestException.class,
            MethodArgumentTypeMismatchException.class})
    public ProblemDetail badRequest(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, BAD_REQUEST_TITLE, exception.getMessage());
    }

    /** Для {@link BadCredentialsException} сообщение исключения передаётся клиенту как {@code detail}. */
    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail unauthorized(AuthenticationException exception) {
        String message = exception instanceof BadCredentialsException ? exception.getMessage() : null;
        return problem(HttpStatus.UNAUTHORIZED, "Требуется вход",
                message == null || message.isBlank() ? "Требуется аутентификация." : message);
    }

    /**
     * Для {@link RegistrationDisabledException} и {@link GrantsAccessDeniedException} сообщение исключения
     * передаётся клиенту как {@code detail}.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail forbidden(AccessDeniedException exception) {
        String message = exception instanceof RegistrationDisabledException
                || exception instanceof GrantsAccessDeniedException ? exception.getMessage() : null;
        return problem(HttpStatus.FORBIDDEN, "Недостаточно прав",
                message == null || message.isBlank() ? "Недостаточно прав для выполнения операции." : message);
    }

    @ExceptionHandler({EntityNotFoundException.class, DynamicDictionaryNotFoundException.class})
    public ProblemDetail notFound(RuntimeException exception) {
        return problem(HttpStatus.NOT_FOUND, "Не найдено", exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail illegalState(IllegalStateException exception) {
        return problem(HttpStatus.CONFLICT, CONFLICT_TITLE, exception.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail conflict(DataIntegrityViolationException exception) {
        return problem(HttpStatus.CONFLICT, CONFLICT_TITLE,
                "Запись нарушает ограничение целостности базы данных.");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail internal(Exception exception) {
        log.error("Необработанная ошибка запроса", exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_MESSAGE, INTERNAL_MESSAGE);
    }

    private static ProblemDetail validation(String message, List<Map<String, String>> errors) {
        ProblemDetail detail = problem(HttpStatus.BAD_REQUEST, VALIDATION_TITLE, message);
        detail.setProperty("errors", errors);
        return detail;
    }

    private static Map<String, String> fieldError(String field, String message) {
        return Map.of("field", field, "message", message == null ? "Недопустимое значение." : message);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String message) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status,
                message == null || message.isBlank() ? "Ошибка обработки запроса." : message);
        detail.setTitle(title);
        return detail;
    }
}
