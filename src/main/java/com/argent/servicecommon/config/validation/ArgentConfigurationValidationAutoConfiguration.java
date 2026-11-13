package com.argent.servicecommon.config.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** Проверка настроек на раннем этапе, до создания обычных бинов service-common. */
@AutoConfiguration
@ConditionalOnProperty(prefix = "argent.config-validation", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ArgentConfigurationValidationAutoConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(ArgentConfigurationValidationAutoConfiguration.class);

    @Bean
    public static BeanFactoryPostProcessor argentConfigurationValidator(Environment environment) {
        return beanFactory -> {
            var issues = ArgentConfigurationInspector.inspect(environment);
            var errors = issues.stream().filter(issue ->
                    issue.severity() == ArgentConfigurationIssue.Severity.ERROR).toList();
            if (!errors.isEmpty()) throw new ArgentConfigurationException(errors);
            issues.forEach(issue -> LOG.warn("\n{}", issue.display()));
            if (environment.getProperty("argent.config-validation.report-on-start", Boolean.class, true)) {
                LOG.info("Проверка настроек AtomSkills завершена: {} ошибок, {} предупреждений. "
                        + "Для полного перечня ключей см. docs/CONFIGURATION_RU.md.",
                        errors.size(), issues.size());
            }
        };
    }
}
