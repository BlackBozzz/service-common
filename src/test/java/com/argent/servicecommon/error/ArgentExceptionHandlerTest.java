package com.argent.servicecommon.error;

import com.argent.servicecommon.dictionary.dynamic.exception.DynamicDictionaryNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

/**
 * Проверка единого обработчика ошибок на автономном MockMvc.
 */
class ArgentExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new StubController())
                .setControllerAdvice(new ArgentExceptionHandler())
                .build();
    }

    @Test
    void shouldMapValidationToFieldErrors() throws Exception {
        mvc.perform(post("/valid").contentType("application/json").content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Ошибка валидации"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void shouldMapMalformedJsonTo400() throws Exception {
        mvc.perform(post("/valid").contentType("application/json").content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Некорректный запрос"));
    }

    @Test
    void shouldMapManualValidationErrors() throws Exception {
        mvc.perform(get("/manual"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("login"))
                .andExpect(jsonPath("$.errors[0].message").value("Логин уже занят"));
    }

    @Test
    void shouldMapNotFound() throws Exception {
        mvc.perform(get("/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Не найдено"));
    }

    @Test
    void shouldMapAccessDenied() throws Exception {
        mvc.perform(get("/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Недостаточно прав"));
    }

    @Test
    void shouldMapAuthentication() throws Exception {
        mvc.perform(get("/auth"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Требуется вход"))
                .andExpect(jsonPath("$.detail").value("плохо"));
    }

    @Test
    void shouldMapConflict() throws Exception {
        mvc.perform(get("/state")).andExpect(status().isConflict());
        mvc.perform(get("/integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Конфликт данных"))
                .andExpect(jsonPath("$.detail").value(not(containsString("SQL"))));
    }

    @Test
    void shouldHideInternalDetails() throws Exception {
        mvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Внутренняя ошибка сервера"))
                .andExpect(jsonPath("$.detail").value("Внутренняя ошибка сервера"));
    }

    record Body(@NotBlank String name) {
    }

    @RestController
    static class StubController {

        @PostMapping("/valid")
        String valid(@Valid @RequestBody Body body) {
            return body.name();
        }

        @GetMapping("/manual")
        String manual() {
            throw new ValidationErrorsException(Map.of("login", "Логин уже занят"));
        }

        @GetMapping("/not-found")
        String notFound() {
            throw new DynamicDictionaryNotFoundException("x");
        }

        @GetMapping("/denied")
        String denied() {
            throw new AccessDeniedException("нет");
        }

        @GetMapping("/auth")
        String auth() {
            throw new BadCredentialsException("плохо");
        }

        @GetMapping("/state")
        String state() {
            throw new IllegalStateException("состояние");
        }

        @GetMapping("/integrity")
        String integrity() {
            throw new DataIntegrityViolationException("SQL: insert into secret_table");
        }

        @GetMapping("/boom")
        String boom() {
            throw new RuntimeException("секрет");
        }
    }
}
