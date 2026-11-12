package com.argent.servicecommon.mail;

import com.argent.servicecommon.auth.entity.User;
import com.argent.servicecommon.auth.repository.UserRepository;
import com.argent.servicecommon.security.repository.RoleRepository;
import com.argent.servicecommon.security.repository.UserRoleRepository;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Определяет адресатов: отключённых и пользователей без email пропускает, адреса дедуплицирует. */
final class ArgentMailRecipients {
    private final UserRepository users;
    private final RoleRepository roles;
    private final UserRoleRepository userRoles;
    private final int limit;

    ArgentMailRecipients(UserRepository users, RoleRepository roles, UserRoleRepository userRoles, int limit) {
        this.users = Objects.requireNonNull(users);
        this.roles = Objects.requireNonNull(roles);
        this.userRoles = Objects.requireNonNull(userRoles);
        this.limit = limit;
    }

    RecipientBatch email(String email) { return emails(List.of(email)); }

    RecipientBatch emails(Collection<String> emails) {
        requireCollection(emails);
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String email : emails) unique.add(normalize(email));
        return new RecipientBatch(emails.size(), List.copyOf(unique));
    }

    RecipientBatch userId(UUID id) {
        Objects.requireNonNull(id, "ID пользователя обязателен");
        return fromUsers(users.findById(id).map(List::of).orElseGet(List::of), 1);
    }

    RecipientBatch userIds(Collection<UUID> ids) {
        requireCollection(ids);
        if (ids.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("ID пользователя не может быть null");
        return fromUsers(users.findByIdIn(new LinkedHashSet<>(ids)), ids.size());
    }

    RecipientBatch role(String code) { return roles(List.of(code)); }

    RecipientBatch roles(Collection<String> codes) {
        requireCollection(codes);
        Set<String> unique = new LinkedHashSet<>();
        for (String code : codes) {
            if (code == null || code.isBlank()) throw new IllegalArgumentException("Код роли обязателен");
            String trimmed = code.trim();
            if (roles.findByCode(trimmed).isEmpty()) throw new IllegalArgumentException("Неизвестная роль: " + trimmed);
            unique.add(trimmed);
        }
        return fromUsers(userRoles.findUsersByRoleCodes(unique), -1);
    }

    RecipientBatch allUsers() { return fromUsers(users.findByEnabledTrueAndEmailIsNotNull(), -1); }

    private RecipientBatch fromUsers(List<User> users, int requested) {
        if (requested < 0) requested = users.size();
        checkLimit(requested);
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (User user : users) {
            if (!user.isEnabled() || user.getEmail() == null || user.getEmail().isBlank()) continue;
            try {
                unique.add(normalize(user.getEmail()));
            } catch (IllegalArgumentException ignored) {
                // Исторически ошибочные адреса из базы не блокируют всю рассылку.
            }
        }
        return new RecipientBatch(requested, List.copyOf(unique));
    }

    private void requireCollection(Collection<?> values) {
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("Список адресатов не должен быть пустым");
        checkLimit(values.size());
    }

    private void checkLimit(int count) {
        if (count > limit) throw new IllegalArgumentException("Слишком много адресатов в одном вызове (лимит " + limit + ")");
    }

    static String normalize(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Email обязателен");
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.length() > 254 || value.contains("\r") || value.contains("\n") || value.contains(" ")) {
            throw new IllegalArgumentException("Некорректный email");
        }
        try {
            InternetAddress address = new InternetAddress(value, true);
            address.validate();
            if (!value.equals(address.getAddress()) || !value.contains("@")) throw new AddressException("Некорректный адрес");
        } catch (AddressException exception) {
            throw new IllegalArgumentException("Некорректный email", exception);
        }
        return value;
    }

    record RecipientBatch(int selected, List<String> emails) {
        int skipped() { return selected - emails.size(); }
    }
}
