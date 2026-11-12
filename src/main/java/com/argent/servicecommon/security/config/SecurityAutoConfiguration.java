package com.argent.servicecommon.security.config;

import com.argent.servicecommon.auth.AuthProperties;
import com.argent.servicecommon.error.ValidationErrorsException;
import com.argent.servicecommon.auth.repository.UserRepository;
import com.argent.servicecommon.auth.repository.UserSessionRepository;
import com.argent.servicecommon.auth.service.TokenService;
import com.argent.servicecommon.security.ProblemDetailAccessDeniedHandler;
import com.argent.servicecommon.security.ProblemDetailAuthenticationEntryPoint;
import com.argent.servicecommon.security.SecurityProperties;
import com.argent.servicecommon.security.grants.ArgentAccess;
import com.argent.servicecommon.security.grants.GrantsAuthorities;
import com.argent.servicecommon.security.grants.GrantsCodec;
import com.argent.servicecommon.security.grants.RoleGrants;
import com.argent.servicecommon.security.jwt.JwtKeyProvider;
import com.argent.servicecommon.security.jwt.UserSessionJwtValidator;
import com.argent.servicecommon.security.repository.RoleGrantsRow;
import com.argent.servicecommon.security.repository.RoleRepository;
import com.argent.servicecommon.security.repository.UserRoleRepository;
import com.argent.servicecommon.security.service.RoleAdminService;
import com.argent.servicecommon.security.service.UserAdminService;
import com.argent.servicecommon.security.web.DictionaryAuthorizationManager;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import jakarta.servlet.DispatcherType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Аутентификация по JWT с сессиями в БД: ключ, кодировщик и декодер JWT, authorities из грантов, бин
 * {@code argentAccess}, администрирование и цепочка фильтров с ответами ProblemDetail. Выключается
 * {@code argent.security.enabled=false}.
 */
@AutoConfiguration(beforeName = {
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration"
})
@ConditionalOnProperty(name = "argent.security.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({AuthProperties.class, SecurityProperties.class})
@EnableMethodSecurity
public class SecurityAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityAutoConfiguration.class);

    /** Ключ подписи JWT из {@code argent.auth.jwt.key-file}; создаётся при первом запуске. */
    @Bean
    @ConditionalOnMissingBean(RSAKey.class)
    public RSAKey argentJwtKey(AuthProperties properties) {
        return JwtKeyProvider.load(properties.jwt().keyFile());
    }

    /** Кодировщик JWT, подписывающий токены ключом {@link RSAKey}. */
    @Bean
    @ConditionalOnMissingBean(JwtEncoder.class)
    public JwtEncoder jwtEncoder(RSAKey argentJwtKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(argentJwtKey)));
    }

    /**
     * Декодер JWT: проверяет подпись RS256, срок действия, существование и активность сессии,
     * существование и активность пользователя.
     */
    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    public JwtDecoder jwtDecoder(RSAKey argentJwtKey, UserSessionRepository sessionRepository,
            UserRepository userRepository) throws JOSEException {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(argentJwtKey.toRSAPublicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                new UserSessionJwtValidator(sessionRepository, userRepository)));
        return decoder;
    }

    /**
     * Преобразует проверенный токен в аутентификацию: имя — идентификатор пользователя,
     * authorities — объединённые гранты его ролей ({@code table:<t>:<op>}, {@code action:<a>}),
     * читаемые из базы данных на каждый запрос.
     *
     * <p>Роль с некорректными грантами в базе пропускается с записью ERROR в журнал,
     * чтобы не ломать вход пользователю.</p>
     */
    @Bean
    @ConditionalOnMissingBean(JwtAuthenticationConverter.class)
    public JwtAuthenticationConverter jwtAuthenticationConverter(RoleRepository roleRepository) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName("sub");
        converter.setJwtGrantedAuthoritiesConverter(jwt -> userAuthorities(roleRepository, jwt.getSubject()));
        return converter;
    }

    /** Проверки прав для SpEL: {@code @argentAccess.action('...')}, {@code @argentAccess.table('...', '...')}. */
    @Bean(name = "argentAccess")
    @ConditionalOnMissingBean(name = "argentAccess")
    public ArgentAccess argentAccess() {
        return new ArgentAccess();
    }

    static Collection<GrantedAuthority> userAuthorities(RoleRepository roleRepository, String subject) {
        UUID userId;
        try {
            userId = UUID.fromString(subject);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return List.of();
        }
        List<RoleGrants> grants = new ArrayList<>();
        for (RoleGrantsRow row : roleRepository.findGrantsByUserId(userId)) {
            try {
                grants.add(GrantsCodec.parse(row.grants()));
            } catch (ValidationErrorsException exception) {
                LOG.error("Некорректные гранты роли {} пропущены для пользователя {}: {} ({})",
                        row.code(), userId, row.grants(), exception.getErrors());
            }
        }
        return GrantsAuthorities.toAuthorities(GrantsAuthorities.merge(grants)).stream()
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();
    }

    /** Выдача и отзыв токенов с временем жизни сессии {@code argent.auth.session-ttl}. */
    @Bean
    @ConditionalOnMissingBean(TokenService.class)
    public TokenService tokenService(UserSessionRepository sessionRepository, JwtEncoder jwtEncoder,
            AuthProperties properties) {
        return new TokenService(sessionRepository, jwtEncoder, properties.sessionTtl());
    }

    /** Администрирование пользователей: создание, роли, пароль, сессии. */
    @Bean
    @ConditionalOnMissingBean(UserAdminService.class)
    public UserAdminService userAdminService(UserRepository userRepository, RoleRepository roleRepository,
            UserRoleRepository userRoleRepository, PasswordEncoder passwordEncoder, TokenService tokenService) {
        return new UserAdminService(userRepository, roleRepository, userRoleRepository, passwordEncoder,
                tokenService);
    }

    /** Администрирование ролей. */
    @Bean
    @ConditionalOnMissingBean(RoleAdminService.class)
    public RoleAdminService roleAdminService(RoleRepository roleRepository, UserRoleRepository userRoleRepository) {
        return new RoleAdminService(roleRepository, userRoleRepository);
    }

    /**
     * Цепочка фильтров для сервлетного приложения: всё, кроме открытых путей, требует JWT;
     * сессии HTTP не создаются, CSRF отключён, ошибки 401/403 возвращаются как ProblemDetail.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class WebSecurityConfiguration {

        /** Пути, открытые без входа всегда, независимо от {@code argent.security.permit-paths}. */
        static final List<String> DEFAULT_PERMIT_PATHS = List.of(
                "/api/auth/login",
                "/api/auth/register",
                "/api/auth/config",
                "/v3/api-docs/**",
                "/swagger-ui/**",
                "/swagger-ui.html",
                "/actuator/health");

        /** Список справочников: доступен любому вошедшему, фильтрует фронт. */
        static final String DICTIONARY_LIST_PATH = "/api/argent-dictionary";

        /** Операции справочника: проверяются {@link DictionaryAuthorizationManager} по коду из пути. */
        static final String DICTIONARY_PATH = "/api/argent-dictionary/{code}/**";

        @Bean
        @ConditionalOnMissingBean(ProblemDetailAuthenticationEntryPoint.class)
        public ProblemDetailAuthenticationEntryPoint problemDetailAuthenticationEntryPoint(
                ObjectProvider<JsonMapper> jsonMapper) {
            return new ProblemDetailAuthenticationEntryPoint(jsonMapper.getIfAvailable(JsonMapper::shared));
        }

        @Bean
        @ConditionalOnMissingBean(ProblemDetailAccessDeniedHandler.class)
        public ProblemDetailAccessDeniedHandler problemDetailAccessDeniedHandler(
                ObjectProvider<JsonMapper> jsonMapper) {
            return new ProblemDetailAccessDeniedHandler(jsonMapper.getIfAvailable(JsonMapper::shared));
        }

        /** Правило доступа к справочникам по URL: {@code GET} — грант {@code read}, иначе — {@code write}. */
        @Bean
        @ConditionalOnMissingBean(DictionaryAuthorizationManager.class)
        public DictionaryAuthorizationManager dictionaryAuthorizationManager() {
            return new DictionaryAuthorizationManager();
        }

        /**
         * Основная цепочка фильтров: открытые пути, справочники по грантам таблиц, остальное — после входа.
         * Приложение может заменить её бином с тем же именем
         * или добавить свою цепочку с собственным {@code securityMatcher} и более высоким приоритетом.
         */
        @Bean
        @ConditionalOnMissingBean(name = "argentSecurityFilterChain")
        public SecurityFilterChain argentSecurityFilterChain(HttpSecurity http, SecurityProperties properties,
                JwtAuthenticationConverter jwtAuthenticationConverter,
                ProblemDetailAuthenticationEntryPoint authenticationEntryPoint,
                ProblemDetailAccessDeniedHandler accessDeniedHandler,
                DictionaryAuthorizationManager dictionaryAuthorizationManager) {
            List<String> permitPaths = new ArrayList<>(DEFAULT_PERMIT_PATHS);
            permitPaths.addAll(properties.permitPaths());

            http.authorizeHttpRequests(requests -> requests
                            .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                            .requestMatchers(permitPaths.toArray(String[]::new)).permitAll()
                            // Browser WebSocket cannot set Authorization. The one-use ticket is validated
                            // by the WebSocket handshake interceptor before accepting a connection.
                            .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/argent-live/ws").permitAll()
                            .requestMatchers(DICTIONARY_LIST_PATH).authenticated()
                            .requestMatchers(DICTIONARY_PATH).access(dictionaryAuthorizationManager)
                            .anyRequest().authenticated())
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .exceptionHandling(exceptions -> exceptions
                            .authenticationEntryPoint(authenticationEntryPoint)
                            .accessDeniedHandler(accessDeniedHandler))
                    .oauth2ResourceServer(resourceServer -> resourceServer
                            .authenticationEntryPoint(authenticationEntryPoint)
                            .accessDeniedHandler(accessDeniedHandler)
                            .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));

            List<String> allowedOrigins = properties.cors().allowedOrigins();
            if (!allowedOrigins.isEmpty()) {
                http.cors(cors -> cors.configurationSource(corsConfigurationSource(allowedOrigins)));
            }
            return http.build();
        }

        private static CorsConfigurationSource corsConfigurationSource(List<String> allowedOrigins) {
            CorsConfiguration configuration = new CorsConfiguration();
            configuration.setAllowedOrigins(allowedOrigins);
            configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
            configuration.setAllowedHeaders(List.of("*"));
            UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
            source.registerCorsConfiguration("/api/**", configuration);
            return source;
        }
    }
}
