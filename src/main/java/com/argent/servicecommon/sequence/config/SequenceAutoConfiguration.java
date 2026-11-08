package com.argent.servicecommon.sequence.config;

import com.argent.servicecommon.config.ServiceCommonAutoConfiguration;
import com.argent.servicecommon.sequence.NumberGenerator;
import com.argent.servicecommon.sequence.repository.CommonSequenceRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

/**
 * Автоконфигурация нумерации документов: {@link NumberGenerator} и системные часы {@link Clock}.
 *
 * <p>Включается, только если в контексте есть {@link CommonSequenceRepository} (приложение,
 * задающее свой {@code @EnableJpaRepositories} без пакета {@code sequence}, нумерацию не
 * получает). Свой бин {@link Clock} (например, фиксированные часы в тестах) заменяет системный.</p>
 */
@AutoConfiguration(
        after = ServiceCommonAutoConfiguration.class,
        afterName = {
                "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration"
        }
)
@ConditionalOnBean(CommonSequenceRepository.class)
public class SequenceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    @ConditionalOnMissingBean(NumberGenerator.class)
    public NumberGenerator numberGenerator(JdbcTemplate jdbcTemplate, Clock clock) {
        return new NumberGenerator(jdbcTemplate, clock);
    }
}
