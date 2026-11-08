package com.argent.servicecommon.attachment;

import com.argent.servicecommon.attachment.dto.AttachmentDto;
import com.argent.servicecommon.attachment.repository.AttachmentRepository;
import com.argent.servicecommon.audit.AuditWriter;
import com.argent.servicecommon.security.SecurityTestApplication;
import com.argent.servicecommon.security.SecurityTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Вложения: запись файла и строки, список, удаление с диска после коммита, лимиты, блок-лист типов,
 * нормализация имени, определение типа по сигнатуре и аудит ATTACH/DETACH.
 */
@SpringBootTest(classes = SecurityTestApplication.class)
class AttachmentServiceIntegrationTest {

    @TempDir
    static Path root;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SecurityTestContainer.register(registry, "it_attachment");
        registry.add("argent.attachments.root", () -> root.toString());
    }

    @Autowired private AttachmentService service;
    @Autowired private AttachmentRepository repository;
    @Autowired private ObjectProvider<AuditWriter> auditWriter;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void prepare() throws IOException {
        jdbc.update("DELETE FROM common_attachment");
        jdbc.update("DELETE FROM common_audit_entry");
        for (Path file : regularFiles()) {
            Files.delete(file);
        }
    }

    @Test
    void shouldStoreListAndDelete() {
        byte[] content = "%PDF-1.4 test".getBytes(StandardCharsets.US_ASCII);
        AttachmentDto dto = service.store("orders", "42",
                new MockMultipartFile("file", "a.pdf", "application/pdf", content));

        assertThat(dto.id()).isNotNull();
        assertThat(dto.entityType()).isEqualTo("orders");
        assertThat(dto.entityId()).isEqualTo("42");
        assertThat(dto.fileName()).isEqualTo("a.pdf");
        assertThat(dto.contentType()).isEqualTo("application/pdf");
        assertThat(dto.sizeBytes()).isEqualTo(content.length);
        assertThat(dto.createdAt()).isNotNull();

        String storageKey = service.find(dto.id()).getStorageKey();
        Path stored = root.resolve(storageKey);
        assertThat(stored).exists().hasBinaryContent(content);
        assertThat(service.find(dto.id()).getSha256()).hasSize(64);
        assertThat(service.list("orders", "42")).extracting(AttachmentDto::id).containsExactly(dto.id());

        service.delete(dto.id());

        assertThat(service.list("orders", "42")).isEmpty();
        assertThat(stored).doesNotExist();
        assertThat(jdbc.queryForList("SELECT action FROM common_audit_entry "
                        + "WHERE entity_type = 'orders' AND entity_id = '42'", String.class))
                .containsExactlyInAnyOrder("ATTACH", "DETACH");
        assertThat(jdbc.queryForList("SELECT new_value FROM common_audit_entry WHERE action = 'ATTACH'",
                String.class)).containsExactly("a.pdf");
        assertThat(jdbc.queryForList("SELECT old_value FROM common_audit_entry WHERE action = 'DETACH'",
                String.class)).containsExactly("a.pdf");
    }

    @Test
    void shouldRejectTooLarge() throws IOException {
        AttachmentService limited = new AttachmentService(repository, new AttachmentStorage(root),
                new AttachmentProperties(root.toString(), 10, List.of()), auditWriter);

        assertThatThrownBy(() -> limited.store("orders", "42",
                new MockMultipartFile("file", "big.txt", "text/plain", "01234567890".getBytes())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Файл превышает допустимый размер");
        assertThat(regularFiles()).isEmpty();
        assertThat(repository.count()).isZero();
    }

    @Test
    void shouldRejectEmptyFile() {
        assertThatThrownBy(() -> service.store("orders", "42",
                new MockMultipartFile("file", "a.txt", "text/plain", new byte[0])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Файл пуст");
    }

    @Test
    void shouldRejectBlockedType() throws IOException {
        assertThatThrownBy(() -> service.store("orders", "42",
                new MockMultipartFile("file", "x.html", "text/html", "<html></html>".getBytes())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Тип файла не разрешён: text/html");
        assertThat(regularFiles()).isEmpty();
    }

    @Test
    void shouldRejectBlockedDeclaredType() throws IOException {
        assertThatThrownBy(() -> service.store("orders", "42",
                new MockMultipartFile("file", "notes.txt", "text/html", "<b>hi</b>".getBytes())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Тип файла не разрешён: text/html");
        assertThat(regularFiles()).isEmpty();
    }

    @Test
    void shouldRejectWindowsExecutableDisguisedAsPdf() throws IOException {
        byte[] exe = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0};
        assertThatThrownBy(() -> service.store("orders", "42",
                new MockMultipartFile("file", "invoice.pdf", "application/pdf", exe)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Тип файла не разрешён: application/x-msdownload");
        assertThat(regularFiles()).isEmpty();
    }

    @Test
    void shouldRejectElfExecutable() throws IOException {
        byte[] elf = {0x7f, 'E', 'L', 'F', 2, 1, 1, 0};
        assertThatThrownBy(() -> service.store("orders", "42",
                new MockMultipartFile("file", "photo.png", "image/png", elf)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Тип файла не разрешён: application/x-executable");
        assertThat(regularFiles()).isEmpty();
    }

    @Test
    void shouldRejectTooLongDeclaredType() throws IOException {
        String type = "application/" + "x".repeat(300);
        assertThatThrownBy(() -> service.store("orders", "42",
                new MockMultipartFile("file", "a.txt", type, "abc".getBytes())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Тип файла не разрешён");
        assertThat(regularFiles()).isEmpty();
    }

    @Test
    void shouldRejectSvg() throws IOException {
        assertThatThrownBy(() -> service.store("orders", "42", new MockMultipartFile("file", "logo.svg",
                "image/svg+xml", "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".getBytes())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Тип файла не разрешён: image/svg+xml");
        assertThat(regularFiles()).isEmpty();
    }

    @Test
    void shouldNotTrustDeclaredTypeWithoutDetection() {
        AttachmentDto dto = service.store("orders", "42",
                new MockMultipartFile("file", "data.bin", "text/csv", "a;b,1;2".getBytes()));

        assertThat(dto.contentType()).isEqualTo("application/octet-stream");
    }

    @Test
    void shouldRejectStreamLongerThanDeclaredSize() throws IOException {
        AttachmentService limited = new AttachmentService(repository, new AttachmentStorage(root),
                new AttachmentProperties(root.toString(), 10, List.of()), auditWriter);
        AtomicLong bytesRead = new AtomicLong();
        MockMultipartFile lying = new MockMultipartFile("file", "big.txt", "text/plain", new byte[1_000_000]) {
            @Override
            public long getSize() {
                return 5;
            }

            @Override
            public InputStream getInputStream() throws IOException {
                return new FilterInputStream(super.getInputStream()) {
                    @Override
                    public int read() throws IOException {
                        int value = super.read();
                        if (value >= 0) {
                            bytesRead.incrementAndGet();
                        }
                        return value;
                    }

                    @Override
                    public int read(byte[] buffer, int offset, int length) throws IOException {
                        int count = super.read(buffer, offset, length);
                        if (count > 0) {
                            bytesRead.addAndGet(count);
                        }
                        return count;
                    }
                };
            }
        };

        assertThatThrownBy(() -> limited.store("orders", "42", lying))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Файл превышает допустимый размер");
        assertThat(regularFiles()).isEmpty();
        assertThat(repository.count()).isZero();
        // Заголовок для определения типа (8 байт) + не больше лимита + 1 байт при копировании.
        assertThat(bytesRead.get()).isLessThanOrEqualTo(8 + 11);
    }

    @Test
    void shouldStripBidiControlsFromFileName() {
        // Имя .exe проверяется напрямую: сам файл с таким расширением отклонялся бы по типу.
        assertThat(AttachmentService.sanitizeFileName("report" + (char) 0x202E + "txt.exe"))
                .isEqualTo("reporttxt.exe");
    }

    @Test
    void shouldRejectTypeOutsideAllowList() {
        AttachmentService imagesOnly = new AttachmentService(repository, new AttachmentStorage(root),
                new AttachmentProperties(root.toString(), 1000, List.of("image/", "application/pdf")),
                auditWriter);

        assertThatThrownBy(() -> imagesOnly.store("orders", "42",
                new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Тип файла не разрешён: text/plain");
    }

    @Test
    void shouldRejectInvalidEntityType() {
        assertThatThrownBy(() -> service.list("Orders; drop", "42"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Недопустимое имя таблицы: Orders; drop");
    }

    @Test
    void shouldSanitizeFileName() {
        AttachmentDto dto = service.store("orders", "42", new MockMultipartFile("file",
                "..\\..\\etc\\pass<wd>.txt", "text/plain", "hello".getBytes()));

        assertThat(dto.fileName()).isEqualTo("passwd.txt");
        assertThat(service.find(dto.id()).getStorageKey()).matches("^\\d{4}/\\d{2}/[0-9a-f-]{36}$");
    }

    @Test
    void shouldNotKeepRowWhenFileWriteFails() throws IOException {
        Path fileRoot = Files.writeString(root.resolve("not-a-directory"), "x");
        AttachmentService broken = new AttachmentService(repository, new AttachmentStorage(fileRoot),
                new AttachmentProperties(fileRoot.toString(), 1000, List.of()), auditWriter);

        assertThatThrownBy(() -> broken.store("orders", "42",
                new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes())))
                .isInstanceOfAny(UncheckedIOException.class, IllegalStateException.class);
        assertThat(repository.count()).isZero();
    }

    @Test
    void shouldDeleteFileWhenRowSaveFails() throws IOException {
        AttachmentRepository failing = mock(AttachmentRepository.class);
        when(failing.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("сбой"));
        AttachmentService broken = new AttachmentService(failing, new AttachmentStorage(root),
                new AttachmentProperties(root.toString(), 1000, List.of()), auditWriter);

        assertThatThrownBy(() -> broken.store("orders", "42",
                new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(regularFiles()).isEmpty();
    }

    @Test
    void shouldDeleteAllForEntity() throws IOException {
        for (int i = 0; i < 3; i++) {
            service.store("orders", "42", new MockMultipartFile("file", "f" + i + ".txt", "text/plain",
                    ("content " + i).getBytes()));
        }
        service.store("orders", "43", new MockMultipartFile("file", "other.txt", "text/plain",
                "other".getBytes()));
        assertThat(regularFiles()).hasSize(4);

        assertThat(service.deleteAll("orders", "42")).isEqualTo(3);

        assertThat(service.list("orders", "42")).isEmpty();
        assertThat(service.list("orders", "43")).hasSize(1);
        assertThat(regularFiles()).hasSize(1);
        assertThat(jdbc.queryForList("SELECT old_value FROM common_audit_entry "
                        + "WHERE action = 'DETACH' AND entity_type = 'orders' AND entity_id = '42'", String.class))
                .containsExactlyInAnyOrder("f0.txt", "f1.txt", "f2.txt");
    }

    @Test
    void shouldDetectTypeBySignature() {
        AttachmentDto dto = service.store("orders", "42", new MockMultipartFile("file", "report.bin",
                "application/octet-stream", "%PDF-1.7 body".getBytes(StandardCharsets.US_ASCII)));

        assertThat(dto.contentType()).isEqualTo("application/pdf");
    }

    private static List<Path> regularFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).toList();
        }
    }
}
