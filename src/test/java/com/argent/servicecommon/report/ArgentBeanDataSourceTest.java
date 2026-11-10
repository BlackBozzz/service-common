package com.argent.servicecommon.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import net.sf.jasperreports.engine.JRField;
import net.sf.jasperreports.engine.design.JRDesignField;
import org.junit.jupiter.api.Test;

class ArgentBeanDataSourceTest {

    record Customer(String name) {
    }

    record OrderRow(String number, BigDecimal total, Customer customer) {
    }

    record DatedRow(Instant createdAt) {
    }

    public static class LegacyBean {
        public String code = "C-7";

        public String getLabel() {
            return "метка";
        }

        public boolean isActive() {
            return true;
        }
    }

    private static JRField field(String name, Class<?> clazz) {
        JRDesignField f = new JRDesignField();
        f.setName(name);
        f.setValueClass(clazz);
        return f;
    }

    @Test
    void readsRecordComponentsAndNestedPath() {
        var row = new OrderRow("A-1", null, new Customer("Иван"));
        assertThat(ArgentBeanDataSource.readProperty(row, "number")).isEqualTo("A-1");
        assertThat(ArgentBeanDataSource.readProperty(row, "customer.name")).isEqualTo("Иван");
    }

    @Test
    void readsGettersPublicFieldsAndMaps() {
        var bean = new LegacyBean();
        assertThat(ArgentBeanDataSource.readProperty(bean, "label")).isEqualTo("метка");
        assertThat(ArgentBeanDataSource.readProperty(bean, "active")).isEqualTo(true);
        assertThat(ArgentBeanDataSource.readProperty(bean, "code")).isEqualTo("C-7");
        assertThat(ArgentBeanDataSource.readProperty(Map.of("k", 1), "k")).isEqualTo(1);
    }

    @Test
    void shouldReturnNullForNullProperty() {
        assertThat(ArgentBeanDataSource.readProperty(new OrderRow("A", null, null), "customer.name")).isNull();
        assertThat(ArgentBeanDataSource.readProperty(new OrderRow("A", null, null), "total")).isNull();
    }

    @Test
    void failsWithFieldNameWhenPropertyMissing() throws Exception {
        var ds = new ArgentBeanDataSource(List.of(new OrderRow("A", null, null)));
        ds.next();
        assertThatThrownBy(() -> ds.getFieldValue(field("missing", String.class)))
                .isInstanceOf(ReportRenderException.class)
                .hasMessageContaining("missing")
                .hasMessageContaining("OrderRow");
    }

    @Test
    void convertsFieldValueToDeclaredClass() throws Exception {
        Instant now = Instant.parse("2026-01-02T03:04:05Z");
        var ds = new ArgentBeanDataSource(List.of(new DatedRow(now)));
        ds.next();
        assertThat(ds.getFieldValue(field("createdAt", Date.class))).isEqualTo(Date.from(now));
    }

    @Test
    void wrapsConversionFailureWithFieldName() throws Exception {
        var ds = new ArgentBeanDataSource(List.of(new OrderRow("A", null, null)));
        ds.next();
        assertThatThrownBy(() -> ds.getFieldValue(field("number", Date.class)))
                .isInstanceOf(ReportRenderException.class)
                .hasMessageContaining("Поле \"number\"");
    }

    @Test
    void iteratesAndRewinds() throws Exception {
        var ds = new ArgentBeanDataSource(List.of(new Customer("a"), new Customer("b")));
        assertThat(ds.next()).isTrue();
        assertThat(ds.next()).isTrue();
        assertThat(ds.next()).isFalse();
        ds.moveFirst();
        assertThat(ds.next()).isTrue();
    }
}
