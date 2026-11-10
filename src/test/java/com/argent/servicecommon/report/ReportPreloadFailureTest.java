package com.argent.servicecommon.report;

import com.argent.servicecommon.report.config.ReportAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Битый шаблон из {@code argent.reports.preload} прерывает запуск контекста. */
class ReportPreloadFailureTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ReportAutoConfiguration.class));

    @Test
    void brokenTemplateFailsStartup() {
        runner.withPropertyValues("argent.reports.preload=classpath*:reports/broken.jrxml").run(context -> {
            assertThat(context).hasFailed();
            StringBuilder messages = new StringBuilder();
            for (Throwable t = context.getStartupFailure(); t != null; t = t.getCause()) {
                messages.append(t.getMessage()).append('\n');
            }
            assertThat(messages).contains("broken.jrxml");
        });
    }

    @Test
    void validTemplateStarts() {
        runner.withPropertyValues("argent.reports.preload=classpath*:reports/hello.jrxml")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ReportService.class));
    }
}
