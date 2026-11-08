package com.argent.servicecommon.auth.service;

import com.argent.servicecommon.auth.entity.User;
import com.argent.servicecommon.auth.repository.UserRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Импорт пользователей из внешних источников.
 *
 * <p>Создаёт пользователя без пароля и ролей или обновляет отображаемое имя существующего;
 * роли, пароль и признак активности существующего пользователя не меняются.</p>
 */
public class UserProvisioningService {

    private final UserRepository userRepository;

    public UserProvisioningService(UserRepository userRepository) {
        this.userRepository = Objects.requireNonNull(userRepository);
    }

    /**
     * Создаёт пользователя или обновляет его отображаемое имя.
     *
     * @param login       логин; пробелы по краям отбрасываются
     * @param displayName отображаемое имя
     * @return сохранённый пользователь
     */
    @Transactional
    public User upsert(String login, String displayName) {
        String normalizedLogin = login.trim();
        User user = userRepository.findByLogin(normalizedLogin).orElseGet(() -> {
            User created = new User();
            created.setLogin(normalizedLogin);
            created.setEnabled(true);
            return created;
        });
        user.setDisplayName(displayName);
        return userRepository.save(user);
    }
}
