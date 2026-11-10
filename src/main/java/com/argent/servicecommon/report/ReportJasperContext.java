package com.argent.servicecommon.report;

import net.sf.jasperreports.engine.DefaultJasperReportsContext;
import net.sf.jasperreports.engine.JasperReportsContext;
import net.sf.jasperreports.engine.SimpleJasperReportsContext;

/** Контекст JasperReports со шрифтом по умолчанию и встраиванием шрифтов в PDF (кириллица). */
public final class ReportJasperContext {

    private ReportJasperContext() {
    }

    /** Создаёт контекст поверх глобального с заданным шрифтом для текста и PDF. */
    public static JasperReportsContext create(String defaultFont) {
        SimpleJasperReportsContext context = new SimpleJasperReportsContext(DefaultJasperReportsContext.getInstance());
        context.setProperty("net.sf.jasperreports.default.font.name", defaultFont);
        context.setProperty("net.sf.jasperreports.default.pdf.font.name", defaultFont);
        context.setProperty("net.sf.jasperreports.default.pdf.encoding", "Identity-H");
        context.setProperty("net.sf.jasperreports.default.pdf.embedded", "true");
        context.setProperty("net.sf.jasperreports.awt.ignore.missing.font", "true");
        return context;
    }
}
