package com.argent.servicecommon.report;

import net.sf.jasperreports.engine.JasperReportsContext;
import net.sf.jasperreports.engine.type.WhenNoDataTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Компиляция и кэширование шаблонов JRXML без Spring-контекста. */
class ReportTemplateCacheTest {

    private final ResourceLoader loader = new DefaultResourceLoader();
    private final JasperReportsContext context = ReportJasperContext.create("DejaVu Sans");
    private final ReportTemplateCache cache = new ReportTemplateCache(loader, context, true);

    @Test
    void compilesClasspathTemplateOnce() {
        var first = cache.get("classpath:reports/hello.jrxml");
        assertThat(first.name()).isEqualTo("hello");
        assertThat(first.hasQuery()).isFalse();
        assertThat(first.parameters()).containsOnlyKeys("title");
        assertThat(cache.get("classpath:reports/hello.jrxml").report()).isSameAs(first.report());
    }

    @Test
    void recompilesChangedFileWhenWatching(@TempDir Path dir) throws Exception {
        Path file = copyHello(dir);
        var watching = new ReportTemplateCache(loader, context, true);
        var before = watching.get(file.toString());
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        assertThat(watching.get(file.toString()).report()).isNotSameAs(before.report());
    }

    @Test
    void doesNotRecompileWhenNotWatching(@TempDir Path dir) throws Exception {
        Path file = copyHello(dir);
        var notWatching = new ReportTemplateCache(loader, context, false);
        var before = notWatching.get(file.toString());
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        assertThat(notWatching.get(file.toString()).report()).isSameAs(before.report());
    }

    @Test
    void failsOnBrokenTemplate() {
        assertThatThrownBy(() -> cache.get("classpath:reports/broken.jrxml"))
                .isInstanceOf(ReportTemplateException.class).hasMessageContaining("broken.jrxml");
    }

    @Test
    void shouldFailOnMissingTemplate() {
        assertThatThrownBy(() -> cache.get("classpath:reports/nope.jrxml"))
                .isInstanceOf(ReportTemplateException.class).hasMessageContaining("не найден");
    }

    @Test
    void rejectsRemoteLocation() {
        assertThatThrownBy(() -> cache.get("https://example.com/x.jrxml"))
                .isInstanceOf(ReportTemplateException.class).hasMessageContaining("не найден");
    }

    @Test
    void shouldRejectPatternAndBlankLocation() {
        assertThatThrownBy(() -> cache.get("classpath*:reports/*.jrxml")).isInstanceOf(ReportParameterException.class);
        assertThatThrownBy(() -> cache.get(" ")).isInstanceOf(ReportParameterException.class);
    }

    @Test
    void preloadCompilesPatternAndFailsOnBroken() {
        assertThat(cache.preload(List.of("classpath*:reports/hello.jrxml"))).isEqualTo(1);
        assertThatThrownBy(() -> cache.preload(List.of("classpath*:reports/*.jrxml")))
                .isInstanceOf(ReportTemplateException.class);
    }

    @Test
    void setsAllSectionsNoDetailWhenTemplateSaysNoPages() {
        assertThat(cache.get("classpath:reports/hello.jrxml").report().getWhenNoDataType())
                .isEqualTo(WhenNoDataTypeEnum.ALL_SECTIONS_NO_DETAIL);
    }

    private static Path copyHello(Path dir) throws Exception {
        Path file = dir.resolve("hello.jrxml");
        try (InputStream in = new ClassPathResource("reports/hello.jrxml").getInputStream()) {
            Files.copy(in, file);
        }
        return file;
    }
}
