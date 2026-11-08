package com.argent.servicecommon.security.config;

import com.argent.servicecommon.dictionary.service.DictionaryScannerService;
import com.argent.servicecommon.security.SecurityProperties;
import com.argent.servicecommon.security.controller.GrantsCatalogController;
import com.argent.servicecommon.security.controller.RoleAdminController;
import com.argent.servicecommon.security.controller.UserAdminController;
import com.argent.servicecommon.security.service.RoleAdminService;
import com.argent.servicecommon.security.service.UserAdminService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * REST администрирования пользователей и ролей и каталог грантов для сервлетного приложения.
 *
 * <p>Отключается вместе с остальной безопасностью свойством {@code argent.security.enabled=false}.</p>
 */
@AutoConfiguration(after = SecurityAutoConfiguration.class)
@ConditionalOnProperty(name = "argent.security.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(UserAdminController.class)
    public UserAdminController userAdminController(UserAdminService userAdminService) {
        return new UserAdminController(userAdminService);
    }

    @Bean
    @ConditionalOnMissingBean(RoleAdminController.class)
    public RoleAdminController roleAdminController(RoleAdminService roleAdminService) {
        return new RoleAdminController(roleAdminService);
    }

    @Bean
    @ConditionalOnMissingBean(GrantsCatalogController.class)
    public GrantsCatalogController grantsCatalogController(DictionaryScannerService dictionaryScannerService,
            SecurityProperties securityProperties) {
        return new GrantsCatalogController(dictionaryScannerService, securityProperties);
    }

}
