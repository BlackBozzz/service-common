package com.argent.servicecommon.attachment;

import com.argent.servicecommon.attachment.config.AttachmentAutoConfiguration;
import com.argent.servicecommon.attachment.repository.AttachmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Свойство {@code argent.attachments.enabled} включает и отключает автоконфигурацию вложений. */
class AttachmentAutoConfigurationTest {

    @TempDir
    Path root;

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AttachmentAutoConfiguration.class))
                .withBean(AttachmentRepository.class, () -> mock(AttachmentRepository.class))
                .withPropertyValues("argent.attachments.root=" + root);
    }

    @Test
    void shouldCreateServiceByDefault() {
        runner().run(context -> assertThat(context).hasSingleBean(AttachmentService.class));
    }

    @Test
    void shouldNotCreateServiceWhenDisabled() {
        runner().withPropertyValues("argent.attachments.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AttachmentService.class);
                    assertThat(context).doesNotHaveBean(AttachmentStorage.class);
                });
    }
}
