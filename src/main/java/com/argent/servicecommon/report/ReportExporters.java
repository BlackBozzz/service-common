package com.argent.servicecommon.report;

import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRRuntimeException;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReportsContext;
import net.sf.jasperreports.engine.export.ooxml.JRDocxExporter;
import net.sf.jasperreports.engine.export.ooxml.JRXlsxExporter;
import net.sf.jasperreports.export.SimpleDocxReportConfiguration;
import net.sf.jasperreports.export.SimpleExporterInput;
import net.sf.jasperreports.export.SimpleOutputStreamExporterOutput;
import net.sf.jasperreports.export.SimpleXlsxReportConfiguration;
import net.sf.jasperreports.pdf.JRPdfExporter;
import net.sf.jasperreports.pdf.SimplePdfExporterConfiguration;

import java.io.ByteArrayOutputStream;

/** Экспорт заполненного отчёта в PDF, XLSX или DOCX с общими настройками экспортёров. */
final class ReportExporters {

    private ReportExporters() {
    }

    /** Возвращает байты файла; {@code title} — заголовок PDF, {@code sheetName} уже очищен и нужен только XLSX. */
    static byte[] export(JasperReportsContext ctx, JasperPrint print, ReportFormat format,
                         String title, String sheetName) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SimpleExporterInput input = new SimpleExporterInput(print);
        SimpleOutputStreamExporterOutput output = new SimpleOutputStreamExporterOutput(out);
        try {
            switch (format) {
                case PDF -> {
                    SimplePdfExporterConfiguration configuration = new SimplePdfExporterConfiguration();
                    configuration.setMetadataTitle(title);
                    JRPdfExporter exporter = new JRPdfExporter(ctx);
                    exporter.setExporterInput(input);
                    exporter.setExporterOutput(output);
                    exporter.setConfiguration(configuration);
                    exporter.exportReport();
                }
                case XLSX -> {
                    SimpleXlsxReportConfiguration configuration = new SimpleXlsxReportConfiguration();
                    configuration.setOnePagePerSheet(false);
                    configuration.setRemoveEmptySpaceBetweenRows(true);
                    configuration.setRemoveEmptySpaceBetweenColumns(true);
                    configuration.setDetectCellType(true);
                    configuration.setWhitePageBackground(false);
                    configuration.setSheetNames(new String[] {sheetName});
                    JRXlsxExporter exporter = new JRXlsxExporter(ctx);
                    exporter.setExporterInput(input);
                    exporter.setExporterOutput(output);
                    exporter.setConfiguration(configuration);
                    exporter.exportReport();
                }
                case DOCX -> {
                    SimpleDocxReportConfiguration configuration = new SimpleDocxReportConfiguration();
                    configuration.setFlexibleRowHeight(true);
                    JRDocxExporter exporter = new JRDocxExporter(ctx);
                    exporter.setExporterInput(input);
                    exporter.setExporterOutput(output);
                    exporter.setConfiguration(configuration);
                    exporter.exportReport();
                }
            }
        } catch (JRException | JRRuntimeException e) {
            throw new ReportRenderException(
                    "Не удалось выгрузить отчёт " + print.getName() + " в " + format + ": " + e.getMessage(), e);
        }
        return out.toByteArray();
    }
}
