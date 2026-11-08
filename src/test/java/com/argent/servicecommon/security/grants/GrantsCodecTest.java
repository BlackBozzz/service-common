package com.argent.servicecommon.security.grants;

import com.argent.servicecommon.error.ValidationErrorsException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Проверяет разбор, строгую валидацию и запись JSON-грантов роли.
 */
class GrantsCodecTest {

    @Test
    void shouldParseValidGrants() {
        RoleGrants grants = GrantsCodec.parse("""
                {"tables": {"dict_country": ["read"], "documents": ["read", "write"], "*": ["read"]},
                 "actions": ["documents.approve", "admin"]}
                """);

        assertThat(grants.tables()).isEqualTo(Map.of(
                "dict_country", Set.of(GrantOp.READ),
                "documents", Set.of(GrantOp.READ, GrantOp.WRITE),
                "*", Set.of(GrantOp.READ)));
        assertThat(grants.actions()).containsExactlyInAnyOrder("documents.approve", "admin");
    }

    @Test
    void shouldParseEmptyAndNullSectionsAsEmpty() {
        assertThat(GrantsCodec.parse("{}")).isEqualTo(RoleGrants.empty());
        assertThat(GrantsCodec.parse("{\"tables\":null,\"actions\":null}")).isEqualTo(RoleGrants.empty());
        assertThat(GrantsCodec.parse(null)).isEqualTo(RoleGrants.empty());
    }

    @Test
    void shouldRejectUnknownKey() {
        assertErrors("{\"tables\":{},\"foo\":1}", Map.of("grants", "Неизвестное поле грантов: foo"));
    }

    @Test
    void shouldRejectInvalidTableName() {
        assertErrors("{\"tables\":{\"Dict_Country\":[\"read\"]}}",
                Map.of("grants.tables", "Недопустимое имя таблицы: Dict_Country"));
        assertErrors("{\"tables\":{\"dict-x\":[\"read\"]}}",
                Map.of("grants.tables", "Недопустимое имя таблицы: dict-x"));
    }

    @Test
    void shouldRejectInvalidOperation() {
        assertErrors("{\"tables\":{\"documents\":[\"read\",\"delete\"]}}",
                Map.of("grants.tables", "Недопустимая операция: delete"));
        assertErrors("{\"tables\":{\"documents\":[\"READ\"]}}",
                Map.of("grants.tables", "Недопустимая операция: READ"));
    }

    @Test
    void shouldRejectEmptyOperationSet() {
        assertErrors("{\"tables\":{\"documents\":[]}}",
                Map.of("grants.tables", "Пустой набор операций для таблицы: documents"));
    }

    @Test
    void shouldRejectInvalidActionCode() {
        assertErrors("{\"actions\":[\"Bad Code\"]}",
                Map.of("grants.actions", "Недопустимый код действия: Bad Code"));
    }

    @Test
    void shouldReportErrorsOfBothSections() {
        assertErrors("{\"tables\":{\"Bad\":[\"read\"]},\"actions\":[\"Bad Code\"]}", Map.of(
                "grants.tables", "Недопустимое имя таблицы: Bad",
                "grants.actions", "Недопустимый код действия: Bad Code"));
    }

    @Test
    void shouldRejectMalformedJson() {
        assertThatThrownBy(() -> GrantsCodec.parse("{not json"))
                .isInstanceOf(ValidationErrorsException.class)
                .satisfies(error -> assertThat(((ValidationErrorsException) error).getErrors())
                        .containsOnlyKeys("grants"));
        assertThatThrownBy(() -> GrantsCodec.parse("[]"))
                .isInstanceOf(ValidationErrorsException.class)
                .satisfies(error -> assertThat(((ValidationErrorsException) error).getErrors())
                        .containsOnlyKeys("grants"));
    }

    @Test
    void shouldWriteAndParseBack() {
        RoleGrants grants = GrantsCodec.parse(GrantConstants.ADMIN_GRANTS);

        String json = GrantsCodec.write(grants);

        assertThat(json).isEqualTo(GrantConstants.ADMIN_GRANTS);
        assertThat(GrantsCodec.parse(json)).isEqualTo(grants);
        assertThat(GrantsCodec.write(RoleGrants.empty())).isEqualTo("{\"tables\":{},\"actions\":[]}");
    }

    @Test
    void shouldBeImmutable() {
        RoleGrants grants = GrantsCodec.parse("{\"tables\":{\"a\":[\"read\"]},\"actions\":[\"x\"]}");

        assertThatThrownBy(() -> grants.tables().put("b", Set.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> grants.tables().get("a").add(GrantOp.WRITE))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> grants.actions().add("y")).isInstanceOf(UnsupportedOperationException.class);
    }

    private static void assertErrors(String json, Map<String, String> expected) {
        assertThatThrownBy(() -> GrantsCodec.parse(json))
                .isInstanceOf(ValidationErrorsException.class)
                .satisfies(error -> assertThat(((ValidationErrorsException) error).getErrors())
                        .isEqualTo(expected));
    }
}
