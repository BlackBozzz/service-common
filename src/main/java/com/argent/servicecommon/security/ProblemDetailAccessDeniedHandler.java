package com.argent.servicecommon.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * Отвечает 403 в формате ProblemDetail, когда цепочка фильтров отказывает вошедшему пользователю.
 *
 * <p>Отказы {@code @PreAuthorize} внутри контроллеров обрабатывает
 * {@link com.argent.servicecommon.error.ArgentExceptionHandler} с тем же форматом ответа.</p>
 */
public class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemDetailResponseWriter writer;

    public ProblemDetailAccessDeniedHandler(JsonMapper jsonMapper) {
        this.writer = new ProblemDetailResponseWriter(jsonMapper);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException exception) throws IOException {
        writer.write(request, response, HttpStatus.FORBIDDEN, "Недостаточно прав",
                "Недостаточно прав для выполнения операции.");
    }
}
