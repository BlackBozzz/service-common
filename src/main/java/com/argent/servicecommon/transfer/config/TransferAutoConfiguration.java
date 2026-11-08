package com.argent.servicecommon.transfer.config;

import com.argent.servicecommon.config.ServiceCommonAutoConfiguration;
import com.argent.servicecommon.dictionary.config.DictionaryDtoAutoConfiguration;
import com.argent.servicecommon.dictionary.dynamic.config.DynamicDictionaryAutoConfiguration;
import com.argent.servicecommon.dictionary.dynamic.metadata.DynamicDictionaryCatalog;
import com.argent.servicecommon.dictionary.dynamic.metadata.DynamicDictionarySchemaService;
import com.argent.servicecommon.dictionary.dynamic.service.DynamicDictionaryQueryService;
import com.argent.servicecommon.dictionary.service.DictionaryDtoCrudService;
import com.argent.servicecommon.transfer.TransferProperties;
import com.argent.servicecommon.transfer.exp.DictionaryExportService;
import com.argent.servicecommon.transfer.exp.ExportController;
import com.argent.servicecommon.transfer.exp.TableExporter;
import com.argent.servicecommon.transfer.imp.AuditRecorder;
import com.argent.servicecommon.transfer.imp.DictionaryImportService;
import com.argent.servicecommon.transfer.imp.ImportController;
import com.argent.servicecommon.transfer.imp.ImportExceptionHandler;
import com.argent.servicecommon.transfer.imp.TableReader;
import com.argent.servicecommon.transfer.imp.ValueConverter;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Автоконфигурация импорта и экспорта таблиц: чтение XLSX/CSV, преобразование значений, сервис
 * импорта, REST {@code /api/import/dictionaries} и ответ на отменённый импорт; выгрузка таблиц и
 * справочников в XLSX/CSV/DOCX и REST {@code /api/export}.
 *
 * <p>{@link AuditRecorder} по умолчанию ничего не делает; модуль аудита заменяет его своим бином.</p>
 */
@AutoConfiguration(after = {
        ServiceCommonAutoConfiguration.class,
        DictionaryDtoAutoConfiguration.class,
        DynamicDictionaryAutoConfiguration.class
})
@EnableConfigurationProperties(TransferProperties.class)
public class TransferAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(TableReader.class)
    public TableReader tableReader(TransferProperties properties) {
        return new TableReader(properties.maxUploadBytes(), properties.maxRows());
    }

    @Bean
    @ConditionalOnMissingBean(ValueConverter.class)
    public ValueConverter importValueConverter(DynamicDictionaryQueryService queries) {
        return new ValueConverter(queries);
    }

    @Bean
    @ConditionalOnMissingBean(AuditRecorder.class)
    public AuditRecorder noOpAuditRecorder() {
        return (table, deletedRows) -> { };
    }

    @Bean
    @ConditionalOnMissingBean(DictionaryImportService.class)
    public DictionaryImportService dictionaryImportService(DynamicDictionaryCatalog catalog,
            DynamicDictionarySchemaService schemas, DynamicDictionaryQueryService queries,
            DictionaryDtoCrudService crud, ValueConverter importValueConverter, EntityManager entityManager,
            JdbcTemplate jdbcTemplate, Validator validator, AuditRecorder auditRecorder,
            TransferProperties properties) {
        return new DictionaryImportService(catalog, schemas, queries, crud, importValueConverter, entityManager,
                jdbcTemplate, validator, auditRecorder, properties);
    }

    @Bean
    @ConditionalOnMissingBean(ImportController.class)
    public ImportController importController(TableReader tableReader, DictionaryImportService importService,
            DynamicDictionarySchemaService schemas, DynamicDictionaryCatalog catalog,
            TransferProperties properties) {
        return new ImportController(tableReader, importService, schemas, catalog, properties);
    }

    @Bean
    @ConditionalOnMissingBean(ImportExceptionHandler.class)
    public ImportExceptionHandler importExceptionHandler() {
        return new ImportExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean(TableExporter.class)
    public TableExporter tableExporter(TransferProperties properties) {
        return new TableExporter(properties.exportMaxColumns(), properties.exportMaxCells());
    }

    @Bean
    @ConditionalOnMissingBean(DictionaryExportService.class)
    public DictionaryExportService dictionaryExportService(DynamicDictionaryCatalog catalog,
            DynamicDictionarySchemaService schemas, DynamicDictionaryQueryService queries,
            TransferProperties properties) {
        return new DictionaryExportService(catalog, schemas, queries, properties.maxRows());
    }

    @Bean
    @ConditionalOnMissingBean(ExportController.class)
    public ExportController exportController(TableExporter tableExporter,
            DictionaryExportService dictionaryExportService, TransferProperties properties) {
        return new ExportController(tableExporter, dictionaryExportService, properties.maxRows());
    }
}
