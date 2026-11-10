package com.argent.servicecommon.report;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportRequestTest {

    @Test
    void shouldCollectAllFields() {
        var rows = List.of("a");
        var request = ReportRequest.template("classpath:reports/x.jrxml")
                .data(rows).param("a", 1).params(Map.of("b", 2))
                .fileName("Заказ").locale(Locale.ENGLISH).format(ReportFormat.XLSX);
        assertThat(request.location()).isEqualTo("classpath:reports/x.jrxml");
        assertThat(request.resource()).isNull();
        assertThat(request.data()).isEqualTo(rows);
        assertThat(request.params()).containsEntry("a", 1).containsEntry("b", 2);
        assertThat(request.fileName()).isEqualTo("Заказ");
        assertThat(request.locale()).isEqualTo(Locale.ENGLISH);
        assertThat(request.format()).isEqualTo(ReportFormat.XLSX);
    }

    @Test
    void shouldRejectBlankParamName() {
        var request = ReportRequest.template("x");
        assertThatThrownBy(() -> request.param("", 1)).isInstanceOf(ReportParameterException.class);
    }

    @Test
    void shouldRejectBlankTemplate() {
        assertThatThrownBy(() -> ReportRequest.template(" ")).isInstanceOf(ReportParameterException.class);
        assertThatThrownBy(() -> ReportRequest.template((org.springframework.core.io.Resource) null))
                .isInstanceOf(ReportParameterException.class);
    }

    @Test
    void shouldExposeImmutableParams() {
        var request = ReportRequest.template("x").param("a", 1);
        assertThatThrownBy(() -> request.params().put("b", 2)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldNotMutateOriginal() {
        var original = ReportRequest.template("x");
        original.param("a", 1);
        assertThat(original.params()).isEmpty();
    }
}
