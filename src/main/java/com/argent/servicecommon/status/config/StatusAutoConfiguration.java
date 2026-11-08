package com.argent.servicecommon.status.config;

import com.argent.servicecommon.config.ServiceCommonAutoConfiguration;
import com.argent.servicecommon.status.StatusController;
import com.argent.servicecommon.status.StatusTransitionService;
import com.argent.servicecommon.status.repository.CommonStatusRepository;
import com.argent.servicecommon.status.repository.CommonStatusTransitionRepository;
import com.argent.servicecommon.status.repository.CommonStatusTypeRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Автоконфигурация статусной машины: {@link StatusTransitionService} и REST
 * {@code /api/status/{type}/transitions}.
 *
 * <p>Включается, только если в контексте есть репозитории статусов (приложение, задающее свой
 * {@code @EnableJpaRepositories} без пакета {@code status}, статусную машину не получает).</p>
 */
@AutoConfiguration(
        after = ServiceCommonAutoConfiguration.class,
        afterName = "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"
)
@ConditionalOnBean({
        CommonStatusTypeRepository.class, CommonStatusRepository.class, CommonStatusTransitionRepository.class
})
public class StatusAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(StatusTransitionService.class)
    public StatusTransitionService statusTransitionService(CommonStatusTypeRepository types,
            CommonStatusRepository statuses, CommonStatusTransitionRepository transitions) {
        return new StatusTransitionService(types, statuses, transitions);
    }

    @Bean
    @ConditionalOnMissingBean(StatusController.class)
    public StatusController statusController(StatusTransitionService statusTransitionService) {
        return new StatusController(statusTransitionService);
    }
}
