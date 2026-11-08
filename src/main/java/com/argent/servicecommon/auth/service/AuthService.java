package com.argent.servicecommon.auth.service;

import com.argent.servicecommon.auth.AuthProperties;
import com.argent.servicecommon.auth.dto.CurrentUserDto;
import com.argent.servicecommon.auth.dto.GrantsDto;
import com.argent.servicecommon.auth.dto.LoginRequest;
import com.argent.servicecommon.auth.entity.User;
import com.argent.servicecommon.auth.repository.UserRepository;
import com.argent.servicecommon.error.ValidationErrorsException;
import com.argent.servicecommon.security.CurrentUser;
import com.argent.servicecommon.security.grants.GrantsAuthorities;
import com.argent.servicecommon.security.grants.GrantsCodec;
import com.argent.servicecommon.security.grants.RoleGrants;
import com.argent.servicecommon.security.repository.RoleGrantsRow;
import com.argent.servicecommon.security.repository.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Вход пользователя и сведения о текущем пользователе.
 *
 * <p>При {@code argent.auth.password-required=false} вход выполняется только по логину,
 * иначе — по логину и паролю.</p>
 */
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * BCrypt-хеш строки {@code argent-dummy}. Сверяется с паролем, когда пользователь не найден,
     * заблокирован или не имеет пароля: проверка длится столько же, сколько для существующего
     * пользователя, и по времени ответа нельзя узнать, какие логины существуют.
     */
    private static final String DUMMY_HASH = "$2a$10$j/BHdoanc699YLk9t.RsjemgEIdFXbPfSfJ3VJ8QUENC1RyEtYIV.";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final AuthProperties properties;
    private final RoleRepository roleRepository;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, TokenService tokenService,
            AuthProperties properties, RoleRepository roleRepository) {
        this.roleRepository = Objects.requireNonNull(roleRepository);
        this.userRepository = Objects.requireNonNull(userRepository);
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder);
        this.tokenService = Objects.requireNonNull(tokenService);
        this.properties = Objects.requireNonNull(properties);
    }

    /**
     * Проверяет логин (и пароль, если он требуется) и выдаёт токен новой сессии.
     *
     * @param request логин и пароль; пробелы по краям логина отбрасываются
     * @return токен для заголовка {@code Authorization: Bearer <token>}
     * @throws BadCredentialsException если пользователь не найден, заблокирован, пароль не задан или неверен
     */
    public String login(LoginRequest request) {
        String login = request.login().trim();
        User user = properties.passwordRequired() ? checkPassword(login, request.password()) : findEnabled(login);
        return tokenService.issue(user);
    }

    /**
     * Возвращает текущего пользователя с эффективными грантами — объединением грантов всех его ролей.
     * Роли с некорректными грантами пропускаются так же, как при построении authorities.
     *
     * @throws AuthenticationCredentialsNotFoundException если запрос анонимный или пользователь удалён
     */
    public CurrentUserDto me() {
        User user = CurrentUser.id()
                .flatMap(userRepository::findById)
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Требуется вход"));
        return new CurrentUserDto(user.getId(), user.getLogin(), user.getDisplayName(),
                GrantsDto.from(effectiveGrants(user)));
    }

    private RoleGrants effectiveGrants(User user) {
        List<RoleGrants> grants = new ArrayList<>();
        for (RoleGrantsRow row : roleRepository.findGrantsByUserId(user.getId())) {
            try {
                grants.add(GrantsCodec.parse(row.grants()));
            } catch (ValidationErrorsException exception) {
                log.error("Некорректные гранты роли {} пропущены для пользователя {}: {}",
                        row.code(), user.getId(), exception.getErrors());
            }
        }
        return GrantsAuthorities.merge(grants);
    }

    private User findEnabled(String login) {
        return userRepository.findByLogin(login)
                .filter(User::isEnabled)
                .orElseThrow(() -> new BadCredentialsException("Пользователь не найден"));
    }

    private User checkPassword(String login, String password) {
        String rawPassword = password == null ? "" : password;
        User user = userRepository.findByLogin(login).filter(User::isEnabled).orElse(null);
        if (user == null || user.getPasswordHash() == null) {
            passwordEncoder.matches(rawPassword, DUMMY_HASH);
            if (user == null) {
                throw invalidCredentials();
            }
            throw new BadCredentialsException("Пароль не задан, обратитесь к администратору");
        }
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw invalidCredentials();
        }
        return user;
    }

    private static BadCredentialsException invalidCredentials() {
        return new BadCredentialsException("Неверный логин или пароль");
    }
}
