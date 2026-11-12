package com.argent.servicecommon.mail;

import com.argent.servicecommon.queue.service.ArgentQueueService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArgentQueuedMailServiceTest {
    @Test
    void createsOneTaskPerRecipientAndReturnsTaskIds() {
        ArgentMailService sender = mock(ArgentMailService.class);
        ArgentQueueService queue = mock(ArgentQueueService.class);
        when(sender.resolveEmails(anyCollection())).thenReturn(
                new ArgentMailRecipients.RecipientBatch(3, List.of("first@example.com", "second@example.com")));
        when(queue.createQueueTask(eq("argent-mail"), eq("argent.mail.send"), any(), eq(0), anyString()))
                .thenAnswer(invocation -> UUID.randomUUID());

        ArgentQueuedMailResult result = new ArgentQueuedMailService(sender, queue).queueToEmails(
                List.of("first@example.com", "FIRST@example.com", "second@example.com"),
                ArgentMailMessage.text("Сообщение", "Привет"), "campaign-2026-11");

        assertEquals(3, result.selected());
        assertEquals(2, result.queued());
        assertEquals(1, result.skipped());
        assertEquals(2, result.taskIds().size());
        verify(queue, times(2)).createQueueTask(eq("argent-mail"), eq("argent.mail.send"), any(), eq(0), anyString());
    }
}
