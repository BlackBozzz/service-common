package com.argent.servicecommon.mail;

import com.argent.servicecommon.auth.entity.User;
import com.argent.servicecommon.auth.repository.UserRepository;
import com.argent.servicecommon.mail.config.ArgentMailProperties;
import com.argent.servicecommon.security.entity.Role;
import com.argent.servicecommon.security.repository.RoleRepository;
import com.argent.servicecommon.security.repository.UserRoleRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ArgentMailServiceTest {
    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final UserRepository users = mock(UserRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final UserRoleRepository userRoles = mock(UserRoleRepository.class);
    private ArgentMailService service;

    @BeforeEach
    void init() {
        ArgentMailProperties config = new ArgentMailProperties();
        config.setFrom("noreply@example.com");
        config.setFromName("Argent");
        service = new ArgentMailService(sender, config, users, roles, userRoles);
        when(sender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
    }

    @Test
    void duplicateAddressesAreNotSentTwice() {
        ArgentMailResult result = service.sendToEmails(
                List.of("FIRST@example.com", "first@example.com", "second@example.com"),
                ArgentMailMessage.text("Тема", "Текст"));
        assertEquals(new ArgentMailResult(3, 2, 1, 0), result);
        verify(sender, times(2)).send(any(MimeMessage.class));
    }

    @Test
    void unknownUserIsSkipped() {
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.empty());
        assertEquals(new ArgentMailResult(1, 0, 1, 0),
                service.sendToUserId(id, ArgentMailMessage.text("Событие", "Текст")));
        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test
    void roleRecipientsSkipDisabledAndMissingEmails() {
        Role role = new Role(); role.setCode("MANAGER");
        when(roles.findByCode("MANAGER")).thenReturn(Optional.of(role));
        User first = user("admin@example.com", true);
        User disabled = user("disabled@example.com", false);
        User missing = user(null, true);
        when(userRoles.findUsersByRoleCodes(Set.of("MANAGER"))).thenReturn(List.of(first, disabled, missing));
        ArgentMailResult result = service.sendToRole("MANAGER", ArgentMailMessage.html("Тема", "<b>Текст</b>"));
        assertEquals(new ArgentMailResult(3, 1, 2, 0), result);
        verify(sender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void multipleIdsUseUserRepository() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        when(users.findByIdIn(Set.of(firstId, secondId)))
                .thenReturn(List.of(user("first@example.com", true), user("second@example.com", true)));

        ArgentMailResult result = service.sendToUserIds(
                List.of(firstId, secondId), ArgentMailMessage.text("Тема", "Текст"));

        assertEquals(new ArgentMailResult(2, 2, 0, 0), result);
        verify(sender, times(2)).send(any(MimeMessage.class));
    }

    @Test
    void allEnabledUsersUseRepository() {
        when(users.findByEnabledTrueAndEmailIsNotNull())
                .thenReturn(List.of(user("valid@example.com", true), user("", true)));

        ArgentMailResult result = service.sendToAllEnabledUsers(ArgentMailMessage.text("Тема", "Текст"));

        assertEquals(new ArgentMailResult(2, 1, 1, 0), result);
        verify(sender).send(any(MimeMessage.class));
    }

    @Test
    void invalidRecipientStopsBeforeAnyDelivery() {
        assertThrows(IllegalArgumentException.class, () -> service.sendToEmails(
                List.of("ok@example.com", "not-an-email"), ArgentMailMessage.text("Тема", "Текст")));
        verify(sender, never()).send(any(MimeMessage.class));
    }

    private static User user(String email, boolean enabled) {
        User result = new User();
        result.setId(UUID.randomUUID());
        result.setEmail(email);
        result.setEnabled(enabled);
        return result;
    }
}
