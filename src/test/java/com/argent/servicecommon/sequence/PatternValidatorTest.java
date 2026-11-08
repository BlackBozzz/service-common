package com.argent.servicecommon.sequence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Проверка шаблонов номеров пробным форматированием. */
class PatternValidatorTest {

    @Test
    void shouldAcceptPatternsWithCounter() {
        for (String pattern : new String[]{"ORD-%1$s-%2$05d", "%s/%d", "INV-%2$d", "N%2$06d (%1$s)"}) {
            assertThatCode(() -> PatternValidator.validate(pattern)).as(pattern).doesNotThrowAnyException();
        }
    }

    @Test
    void shouldRejectInvalidPatterns() {
        for (String pattern : new String[]{"NO-COUNTER", "ORD-%d", "ORD-%1$s", "%%d", "ORD-%2$s", "%q", "%3$d",
                "%1$s-%2$05d-%"}) {
            assertThatThrownBy(() -> PatternValidator.validate(pattern)).as(pattern)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Недопустимый шаблон номера: " + pattern);
        }
    }
}
